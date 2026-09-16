/*
 * Copyright 2026 DVARA Labs, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dvarahq.policy.guardrail;

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.guardrail.GuardrailCategory;
import com.dvarahq.core.guardrail.GuardrailDetection;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailEnforcer;
import com.dvarahq.core.guardrail.GuardrailMetricsListener;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.settings.SettingsBooleans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Guardrail enforcer that scans requests and responses for injection,
 * jailbreak, and content policy violations. Takes the configured action
 * (BLOCK, FLAG, or LOG) per-workspace. Follows the PiiScanService pattern.
 */
public class GuardrailScanService implements GuardrailEnforcer {

    private static final Logger log = LoggerFactory.getLogger(GuardrailScanService.class);

    private final GuardrailDetector detector;
    private final AuditWriter auditWriter;
    private final WorkspaceRepository workspaceRepository;
    private final GuardrailProperties properties;
    private final SystemPromptLeakDetector leakDetector;
    /**
     * Everything that wants to hear about a guardrail decision, which is usually nothing.
     *
     * <p>A list rather than one bean with a no-op default, so a module or the application can
     * register a listener without replacing another. An empty list is the no-op.
     */
    private final java.util.List<GuardrailMetricsListener> metricsListeners;

    /** The typed guardrail store, or null to read the legacy metadata keys. */
    private com.dvarahq.core.workspace.settings.GuardrailSettingsRepository guardrailSettings;

    public void setGuardrailSettings(com.dvarahq.core.workspace.settings.GuardrailSettingsRepository guardrailSettings) {
        this.guardrailSettings = guardrailSettings;
    }

    /** The nested-map store, or null to read the legacy maps. */
    private com.dvarahq.core.workspace.settings.WorkspaceSettingEntryRepository settingEntries;

    public void setSettingEntries(com.dvarahq.core.workspace.settings.WorkspaceSettingEntryRepository settingEntries) {
        this.settingEntries = settingEntries;
    }

    public GuardrailScanService(GuardrailDetector detector, AuditWriter auditWriter,
                                 WorkspaceRepository workspaceRepository, GuardrailProperties properties) {
        // The convenience constructor tests use: no listeners.
        this(detector, auditWriter, workspaceRepository, properties, new SystemPromptLeakDetector(),
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(GuardrailMetricsListener.class));
    }

    public GuardrailScanService(GuardrailDetector detector, AuditWriter auditWriter,
                                 WorkspaceRepository workspaceRepository, GuardrailProperties properties,
                                 SystemPromptLeakDetector leakDetector,
                                 org.springframework.beans.factory.ObjectProvider<GuardrailMetricsListener>
                                         metricsListeners) {
        this.detector = detector;
        this.auditWriter = auditWriter;
        this.workspaceRepository = workspaceRepository;
        this.properties = properties;
        this.leakDetector = leakDetector;
        this.metricsListeners = metricsListeners.orderedStream().toList();
        this.guardrailSettings = null;
    }

    @Override
    public ChatRequest enforceRequest(ChatRequest request, String workspaceId) {
        if (!properties.isEnabled()) {
            return request;
        }

        // Request context for system prompt leak detection is passed explicitly through
        // enforceResponse(response, workspaceId, originalRequest).

        WorkspaceGuardrailConfig config = resolveWorkspaceConfig(workspaceId);
        if (!config.enabled) {
            return applyResponseTokenCap(request, config);
        }

        // Input size validation (OWASP LLM10)
        enforceInputSizeLimits(request, workspaceId, config);

        List<GuardrailScanResult> results = detector.scanRequest(request, workspaceId);
        if (results.isEmpty() || results.stream().noneMatch(GuardrailScanResult::hasDetections)) {
            return applyResponseTokenCap(request, config);
        }

        List<GuardrailDetection> allDetections = results.stream()
                .flatMap(r -> r.detections().stream())
                .filter(d -> d.riskScore() >= config.riskScoreThreshold)
                .toList();

        if (allDetections.isEmpty()) {
            return applyResponseTokenCap(request, config);
        }

        // Resolve effective action: check per-category overrides first
        GuardrailAction effectiveAction = resolveAction(allDetections, config);

        switch (effectiveAction) {
            case BLOCK -> {
                auditGuardrail(workspaceId, "GUARDRAIL_BLOCKED", allDetections, "request", effectiveAction);
                countDecision(workspaceId, allDetections, effectiveAction);
                throw new GatewayException("GUARDRAIL_BLOCKED",
                        "Request blocked: guardrail violation detected ("
                                + summarizeCategories(allDetections) + ")");
            }
            case FLAG -> {
                auditGuardrail(workspaceId, "GUARDRAIL_FLAGGED", allDetections, "request", effectiveAction);
                countDecision(workspaceId, allDetections, effectiveAction);
                return applyResponseTokenCap(request, config);
            }
            case LOG -> {
                auditGuardrail(workspaceId, "GUARDRAIL_DETECTED", allDetections, "request", effectiveAction);
                return applyResponseTokenCap(request, config);
            }
            default -> {
                return applyResponseTokenCap(request, config);
            }
        }
    }

    @Override
    public ChatResponse enforceResponse(ChatResponse response, String workspaceId) {
        return doEnforceResponse(response, workspaceId, null);
    }

    @Override
    public ChatResponse enforceResponse(ChatResponse response, String workspaceId, ChatRequest originalRequest) {
        return doEnforceResponse(response, workspaceId, originalRequest);
    }

    private ChatResponse doEnforceResponse(ChatResponse response, String workspaceId, ChatRequest originalRequest) {
        if (!properties.isEnabled() || !properties.isScanResponses()) {
            return response;
        }

        WorkspaceGuardrailConfig config = resolveWorkspaceConfig(workspaceId);
        if (!config.enabled || !config.scanResponses) {
            return response;
        }

        List<GuardrailScanResult> results = new ArrayList<>(detector.scanResponse(response, workspaceId));

        // System prompt leak detection (OWASP LLM07)
        if (originalRequest != null) {
            String systemPrompt = SystemPromptLeakDetector.extractSystemPrompt(originalRequest);
            if (systemPrompt != null && response.getChoices() != null) {
                for (var choice : response.getChoices()) {
                    if (choice.getMessage() != null && choice.getMessage().getContent() != null) {
                        for (ContentBlock block : choice.getMessage().getContent()) {
                            if (block instanceof ContentBlock.TextBlock tb) {
                                GuardrailScanResult leakResult = leakDetector.scanForLeakedPrompt(
                                        systemPrompt, tb.text());
                                if (leakResult.hasDetections()) {
                                    results.add(leakResult);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (results.isEmpty() || results.stream().noneMatch(GuardrailScanResult::hasDetections)) {
            return response;
        }

        List<GuardrailDetection> allDetections = results.stream()
                .flatMap(r -> r.detections().stream())
                .filter(d -> d.riskScore() >= config.riskScoreThreshold)
                .toList();

        if (allDetections.isEmpty()) {
            return response;
        }

        GuardrailAction effectiveAction = resolveAction(allDetections, config);

        switch (effectiveAction) {
            case BLOCK -> {
                auditGuardrail(workspaceId, "GUARDRAIL_BLOCKED", allDetections, "response", effectiveAction);
                countDecision(workspaceId, allDetections, effectiveAction);
                throw new GatewayException("GUARDRAIL_BLOCKED",
                        "Response blocked: guardrail violation detected ("
                                + summarizeCategories(allDetections) + ")");
            }
            case FLAG -> {
                auditGuardrail(workspaceId, "GUARDRAIL_FLAGGED", allDetections, "response", effectiveAction);
                countDecision(workspaceId, allDetections, effectiveAction);
                return response;
            }
            case LOG -> {
                auditGuardrail(workspaceId, "GUARDRAIL_DETECTED", allDetections, "response", effectiveAction);
                return response;
            }
            default -> {
                return response;
            }
        }
    }

    /**
     * Workspaces already told that one of their category actions cannot apply. This resolve runs per
     * request, so the set is what keeps a standing misconfiguration from becoming a log per call.
     */
    private final java.util.Set<String> warnedInertCategory =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Says so when a workspace has given an action to a category no detector produces.
     *
     * <p>{@code resolveWorkspaceConfig} reads {@code guardrail.content.<category>.action} for every
     * value of the enum. {@code HALLUCINATION} is the one value nothing attaches to a detection:
     * grounding has its own filter, error code, {@code grounding.action} and counter, so that key
     * parses, validates, stores and can never fire.
     *
     * <p>Warned rather than refused, and once per workspace: the value is not wrong, it is pointed
     * at the wrong mechanism.
     */
    private void warnIfCategoryCannotFire(GuardrailCategory category, String key, String workspaceId) {
        if (category != GuardrailCategory.HALLUCINATION) {
            return;
        }
        if (warnedInertCategory.add(workspaceId == null ? "" : workspaceId)) {
            log.warn("{} is set for workspace {} and cannot take effect: no detector produces the "
                            + "HALLUCINATION category. Ungrounded answers are governed by "
                            + "guardrail.grounding.action (per workspace) or "
                            + "dvara.llm-gateway.guardrail.grounding.action, which is a separate "
                            + "filter with its own error code.",
                    key, workspaceId);
        }
    }

    /**
     * The action for a set of detections. A set whose every member came from a classifier answers
     * to {@code classifier-action}; that is a property of the set, so a regex hit alongside a
     * classifier hit still resolves through the category actions.
     */
    private GuardrailAction resolveAction(List<GuardrailDetection> detections,
                                           WorkspaceGuardrailConfig config) {
        boolean classifierOnly = !detections.isEmpty()
                && detections.stream().allMatch(GuardrailDetection::fromClassifier);
        if (classifierOnly && config.classifierAction != null) {
            return config.classifierAction;
        }

        // Each detection's own action, then the most restrictive across the set. The accumulator is
        // not seeded with defaultAction: a per-category override is the action for that category,
        // not a floor, so a workspace on BLOCK that sets guardrail.content.profanity.action=LOG gets
        // LOG for profanity. A request carrying both profanity at LOG and an injection at BLOCK is
        // still refused on the injection.
        GuardrailAction mostRestrictive = null;
        for (GuardrailDetection detection : detections) {
            GuardrailAction categoryAction = config.categoryActions.getOrDefault(
                    detection.category(), config.defaultAction);
            if (mostRestrictive == null || categoryAction.ordinal() < mostRestrictive.ordinal()) {
                mostRestrictive = categoryAction;
            }
        }
        return mostRestrictive == null ? config.defaultAction : mostRestrictive;
    }

    /** {@code null} means inherit — an unset value, {@code INHERIT}, and a typo all land here. */
    private GuardrailAction parseClassifierAction(Object value, String workspaceId) {
        if (value == null) {
            return null;
        }
        String raw = value.toString().trim();
        if (raw.isEmpty() || "INHERIT".equalsIgnoreCase(raw)) {
            return null;
        }
        try {
            return GuardrailAction.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid guardrail classifier action '{}'{} — classifier detections will use the "
                            + "same action as every other detection. Valid: BLOCK, FLAG, LOG, INHERIT.",
                    raw, workspaceId == null ? "" : " for workspace " + workspaceId);
            return null;
        }
    }

    private void auditGuardrail(String workspaceId, String eventType,
                                  List<GuardrailDetection> detections, String source,
                                  GuardrailAction action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source);
        payload.put("action", action.name());
        payload.put("detection_count", detections.size());
        payload.put("categories", summarizeCategories(detections));
        payload.put("detections", detections.stream()
                .map(d -> Map.of(
                        "category", d.category().name(),
                        "label", d.label(),
                        "risk_score", d.riskScore(),
                        "rule_id", d.ruleId()))
                .toList());

        auditWriter.write(new AuditEvent(
                Ids.newId(),
                Instant.now(),
                workspaceId,
                eventType,
                payload));

        // Emit a dedicated ML audit event when classifier detections are present. Keyed on the flag
        // the detector sets, not on a rule-id prefix, so every classifier integration produces the
        // event and the metric. The provider name in the payload is the rule id.
        List<GuardrailDetection> mlDetections = detections.stream()
                .filter(GuardrailDetection::fromClassifier)
                .toList();
        if (!mlDetections.isEmpty()) {
            for (GuardrailDetection d : mlDetections) {
                metricsListeners.forEach(l -> l.onMlDetection(d.ruleId(), d.category().name(), action.name()));
            }
            Map<String, Object> mlPayload = new LinkedHashMap<>();
            mlPayload.put("source", source);
            mlPayload.put("action", action.name());
            mlPayload.put("detections", mlDetections.stream()
                    .map(d -> Map.of(
                            "provider", d.ruleId(),
                            "category", d.category().name(),
                            "label", d.label(),
                            "confidence", d.confidence()))
                    .toList());
            auditWriter.write(new AuditEvent(
                    Ids.newId(),
                    Instant.now(),
                    workspaceId,
                    "ML_INJECTION_DETECTED",
                    mlPayload));
        }
    }

    /**
     * One count per distinct category the decision was taken for. The listener is the seam because
     * the counters live in the runtime module, which this one cannot see.
     */
    private void countDecision(String workspaceId, List<GuardrailDetection> detections,
                               GuardrailAction action) {
        detections.stream()
                .map(d -> d.category().name())
                .distinct()
                .forEach(category -> {
                    if (action == GuardrailAction.BLOCK) {
                        metricsListeners.forEach(l -> l.onBlocked(workspaceId, category));
                    } else if (action == GuardrailAction.FLAG) {
                        metricsListeners.forEach(l -> l.onFlagged(workspaceId, category));
                    }
                });
    }

    private String summarizeCategories(List<GuardrailDetection> detections) {
        return detections.stream()
                .map(d -> d.category().name())
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    @SuppressWarnings("unchecked")
    WorkspaceGuardrailConfig resolveWorkspaceConfig(String workspaceId) {
        boolean enabled = properties.isEnabled();
        GuardrailAction defaultAction = properties.getDefaultAction();
        boolean scanResponses = properties.isScanResponses();
        double riskScoreThreshold = properties.getRiskScoreThreshold();
        Map<GuardrailCategory, GuardrailAction> categoryActions = new HashMap<>();
        int maxInputTokens = properties.getMaxInputTokens();
        int maxMessagesPerRequest = properties.getMaxMessagesPerRequest();
        int maxMessageLength = properties.getMaxMessageLength();
        int defaultMaxResponseTokens = properties.getDefaultMaxResponseTokens();
        GuardrailAction classifierAction = parseClassifierAction(properties.getClassifierAction(), null);

        if (workspaceId != null) {
            Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
            // The scalars come from the typed table when it has a row; the map-shaped settings
            // below (per-category actions, custom denylists, competitor keywords, topic
            // restrictions) are read from metadata.
            var typed = guardrailSettings == null || workspace == null
                    ? null
                    : guardrailSettings.findByWorkspaceId(workspaceId).orElse(null);
            if (typed != null) {
                if (typed.enabled() != null) {
                    enabled = typed.enabled();
                }
                if (typed.action() != null) {
                    try {
                        defaultAction = GuardrailAction.valueOf(typed.action().toUpperCase());
                    } catch (IllegalArgumentException ignored) {
                        // A stored value this build does not know inherits, rather than throwing on
                        // the request path. Validation happens where it is written.
                    }
                }
                if (typed.riskScoreThreshold() != null) {
                    riskScoreThreshold = typed.riskScoreThreshold();
                }
                if (typed.maxInputTokens() != null) {
                    maxInputTokens = typed.maxInputTokens();
                }
                if (typed.maxMessagesPerRequest() != null) {
                    maxMessagesPerRequest = typed.maxMessagesPerRequest();
                }
                if (typed.maxMessageLength() != null) {
                    maxMessageLength = typed.maxMessageLength();
                }
                if (typed.defaultMaxResponseTokens() != null) {
                    defaultMaxResponseTokens = typed.defaultMaxResponseTokens();
                }
                // The Portal writes its general "scan responses" toggle into this field, which is
                // named for streaming. Applying it here is what the operator asked for; renaming a
                // persisted column is a migration.
                if (typed.scanStreamingResponses() != null) {
                    scanResponses = typed.scanStreamingResponses();
                }
            }
            if (workspace != null && workspace.getMetadata() != null) {
                Map<String, Object> meta = workspace.getMetadata();

                // YAML 1.1 via SettingsBooleans, not Boolean.parseBoolean: under that parser "yes"
                // reads as false and would switch the guardrail off, as would an unreadable value.
                // Null means inherit, so a typo leaves the install-wide setting in place.
                Boolean guardrailEnabled = typed != null
                        ? null : SettingsBooleans.yaml(meta.get("guardrail.enabled"));
                if (guardrailEnabled != null) {
                    enabled = guardrailEnabled;
                }

                Object guardrailAction = typed != null ? null : meta.get("guardrail.action");
                if (guardrailAction != null) {
                    try {
                        defaultAction = GuardrailAction.valueOf(guardrailAction.toString().toUpperCase());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid guardrail.action '{}' for workspace {}, using default",
                                guardrailAction, workspaceId);
                    }
                }

                Object threshold = typed != null ? null : meta.get("guardrail.risk-score-threshold");
                if (threshold != null) {
                    try {
                        riskScoreThreshold = Double.parseDouble(threshold.toString());
                    } catch (NumberFormatException e) {
                        log.warn("Invalid guardrail.risk-score-threshold '{}' for workspace {}",
                                threshold, workspaceId);
                    }
                }

                // Per-category action overrides: CATEGORY_ACTION rows in the typed store, or flat
                // keys shaped `guardrail.content.<category>.action` in the map. Read by category
                // either way, so the decision cannot change with the storage.
                java.util.Map<String, String> typedCategoryActions = settingEntries == null
                        ? java.util.Map.<String, String>of()
                        : settingEntries.mapOf(workspaceId, com.dvarahq.core.workspace.settings.WorkspaceSettingEntry.Kind.CATEGORY_ACTION);
                for (GuardrailCategory category : GuardrailCategory.values()) {
                    String key = "guardrail.content." + category.name().toLowerCase() + ".action";
                    Object catAction = typedCategoryActions.containsKey(category.name().toLowerCase())
                            ? typedCategoryActions.get(category.name().toLowerCase())
                            : (typedCategoryActions.isEmpty() ? meta.get(key) : null);
                    if (catAction != null) {
                        try {
                            categoryActions.put(category, GuardrailAction.valueOf(
                                    catAction.toString().toUpperCase()));
                            warnIfCategoryCannotFire(category, key, workspaceId);
                        } catch (IllegalArgumentException e) {
                            log.warn("Invalid {} '{}' for workspace {}", key, catAction, workspaceId);
                        }
                    }
                }

                // A workspace can say what a classifier verdict costs it, the same way it can say
                // what a category costs it. There is no typed column for this one.
                if (meta.get("guardrail.classifier-action") != null) {
                    classifierAction = parseClassifierAction(
                            meta.get("guardrail.classifier-action"), workspaceId);
                }

                // Per-workspace size limit overrides
                maxInputTokens = parseIntOrDefault(typed != null ? null : meta.get("guardrail.max-input-tokens"), maxInputTokens);
                maxMessagesPerRequest = parseIntOrDefault(typed != null ? null : meta.get("guardrail.max-messages-per-request"), maxMessagesPerRequest);
                maxMessageLength = parseIntOrDefault(typed != null ? null : meta.get("guardrail.max-message-length"), maxMessageLength);
                defaultMaxResponseTokens = parseIntOrDefault(typed != null ? null : meta.get("guardrail.default-max-response-tokens"), defaultMaxResponseTokens);
            }
        }

        return new WorkspaceGuardrailConfig(enabled, defaultAction, scanResponses,
                riskScoreThreshold, categoryActions, maxInputTokens, maxMessagesPerRequest,
                maxMessageLength, defaultMaxResponseTokens, classifierAction);
    }

    private static int parseIntOrDefault(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private void enforceInputSizeLimits(ChatRequest request, String workspaceId,
                                          WorkspaceGuardrailConfig config) {
        if (request.getMessages() == null) return;

        // Check max messages per request
        if (config.maxMessagesPerRequest > 0 && request.getMessages().size() > config.maxMessagesPerRequest) {
            auditInputSizeExceeded(workspaceId, "max_messages_per_request",
                    request.getMessages().size(), config.maxMessagesPerRequest);
            throw new GatewayException("INPUT_TOO_LARGE",
                    "Request exceeds maximum messages limit: " + request.getMessages().size()
                            + " > " + config.maxMessagesPerRequest);
        }

        // Check individual message length
        if (config.maxMessageLength > 0) {
            for (var msg : request.getMessages()) {
                if (msg.getContent() == null) continue;
                for (var block : msg.getContent()) {
                    String text = null;
                    if (block instanceof com.dvarahq.core.model.ContentBlock.TextBlock tb) {
                        text = tb.text();
                    }
                    if (text != null && text.length() > config.maxMessageLength) {
                        auditInputSizeExceeded(workspaceId, "max_message_length",
                                text.length(), config.maxMessageLength);
                        throw new GatewayException("INPUT_TOO_LARGE",
                                "Message exceeds maximum length: " + text.length()
                                        + " > " + config.maxMessageLength);
                    }
                }
            }
        }

        // Check max input tokens (estimated)
        if (config.maxInputTokens > 0) {
            int estimatedChars = 0;
            for (var msg : request.getMessages()) {
                if (msg.getContent() == null) continue;
                for (var block : msg.getContent()) {
                    if (block instanceof com.dvarahq.core.model.ContentBlock.TextBlock tb) {
                        estimatedChars += tb.text() != null ? tb.text().length() : 0;
                    }
                }
            }
            // Use chars/4 approximation (same as SimpleTokenEstimator) for fast pre-check
            int estimatedTokens = estimatedChars / 4;
            if (estimatedTokens > config.maxInputTokens) {
                auditInputSizeExceeded(workspaceId, "max_input_tokens",
                        estimatedTokens, config.maxInputTokens);
                throw new GatewayException("INPUT_TOO_LARGE",
                        "Request exceeds estimated token limit: ~" + estimatedTokens
                                + " > " + config.maxInputTokens);
            }
        }
    }

    private ChatRequest applyResponseTokenCap(ChatRequest request, WorkspaceGuardrailConfig config) {
        if (config.defaultMaxResponseTokens <= 0) {
            return request;
        }
        if (request.getMaxTokens() != null && request.getMaxTokens() > 0) {
            return request; // Client already specified max_tokens
        }
        // Apply the default cap. toBuilder(), never a hand-rolled copy that could drop fields such
        // as topP, tools and toolChoice.
        return request.toBuilder().maxTokens(config.defaultMaxResponseTokens).build();
    }

    private void auditInputSizeExceeded(String workspaceId, String limitType,
                                          int actual, int limit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("limit_type", limitType);
        payload.put("actual", actual);
        payload.put("limit", limit);

        auditWriter.write(new AuditEvent(
                Ids.newId(),
                Instant.now(),
                workspaceId,
                "INPUT_SIZE_EXCEEDED",
                payload));
    }

    record WorkspaceGuardrailConfig(
            boolean enabled,
            GuardrailAction defaultAction,
            boolean scanResponses,
            double riskScoreThreshold,
            Map<GuardrailCategory, GuardrailAction> categoryActions,
            int maxInputTokens,
            int maxMessagesPerRequest,
            int maxMessageLength,
            int defaultMaxResponseTokens,
            /** {@code null} means a classifier detection takes the same action as any other. */
            GuardrailAction classifierAction) {
    }
}