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
package com.dvarahq.policy.pii;

import com.dvarahq.core.id.Ids;
import com.dvarahq.pii.PiiProperties;
import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.pii.PiiEdit;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiDegradedAction;
import com.dvarahq.core.pii.PiiTokenizationUnavailableException;
import com.dvarahq.core.pii.PiiScanResult;
import com.dvarahq.core.pii.PiiTokenizationService;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.dvarahq.core.workspace.settings.PiiSettingsResolver;
import com.dvarahq.core.workspace.settings.PiiSettingsRepository;
import java.util.Comparator;

/**
 * PII enforcer that scans requests and responses, then takes the workspace's configured action
 * (BLOCK, REDACT, TOKENIZE or LOG).
 */
public class PiiScanService implements PiiEnforcer {

    private static final Logger log = LoggerFactory.getLogger(PiiScanService.class);

    private final PiiDetector detector;
    /**
     * The reversible-token seam, or {@code null} when this build has none.
     *
     * <p>Null is the ordinary state of this build, not a misconfiguration: with no token vault,
     * {@code pii.action=TOKENIZE} is refused with {@code PII_TOKENIZE_UNAVAILABLE} and the message
     * names REDACT as the irreversible alternative that always works. BLOCK, LOG and REDACT do not
     * consult this field at all.</p>
     */
    private final PiiTokenizationService tokenization;
    private final AuditWriter auditWriter;
    private final WorkspaceRepository workspaceRepository;
    private final PiiProperties properties;
    private final PiiSettingsResolver settingsResolver;

    public PiiScanService(PiiDetector detector, PiiTokenizationService tokenization,
                          AuditWriter auditWriter, WorkspaceRepository workspaceRepository,
                          PiiProperties properties) {
        this(detector, tokenization, auditWriter, workspaceRepository, properties, null);
    }

    /**
     * @param settingsRepository the typed PII settings store, or {@code null} to read the
     *                           legacy {@code Workspace.metadata} keys. Null is not a degraded mode:
     *                           it is what a context without a datasource has, and the resolver
     *                           falls through to the map.
     */
    public PiiScanService(PiiDetector detector, PiiTokenizationService tokenization,
                          AuditWriter auditWriter, WorkspaceRepository workspaceRepository,
                          PiiProperties properties,
                          PiiSettingsRepository settingsRepository) {
        this.detector = detector;
        this.tokenization = tokenization;
        this.auditWriter = auditWriter;
        this.workspaceRepository = workspaceRepository;
        this.properties = properties;
        this.settingsResolver = new PiiSettingsResolver(
                settingsRepository, workspaceRepository);
    }

    @Override
    public ChatRequest enforceRequest(ChatRequest request, String workspaceId) {
        if (!properties.isEnabled()) {
            return request;
        }

        WorkspacePiiConfig config = resolveWorkspaceConfig(workspaceId);
        if (!config.enabled) {
            return request;
        }

        List<PiiScanResult> results = detector.scanRequest(request, config.customPatterns);
        if (results.isEmpty() || results.stream().noneMatch(PiiScanResult::hasPii)) {
            return request;
        }

        PiiAction action = config.action;
        List<PiiEntity> allEntities = results.stream()
                .flatMap(r -> r.entities().stream())
                .toList();

        switch (action) {
            case BLOCK -> {
                auditPiiDetected(workspaceId, "PII_DETECTED", allEntities, "request");
                throw new GatewayException("PII_DETECTED",
                        "Request blocked: PII detected (" + summarizeEntityTypes(allEntities) + ")");
            }
            case REDACT -> {
                // No try/catch and no degraded path: redaction cannot fail. It needs no key, no
                // store and no control plane. pii.degraded-action applies to TOKENIZE alone.
                ChatRequest redacted = transformRequest(request, workspaceId, config.customPatterns,
                        this::redactValues);
                auditPiiDetected(workspaceId, "PII_REDACTED", allEntities, "request");
                return redacted;
            }
            case TOKENIZE -> {
                try {
                    ChatRequest tokenized = transformRequest(request, workspaceId,
                            config.customPatterns, this::tokenizeValues);
                    auditPiiDetected(workspaceId, "PII_TOKENIZED", allEntities, "request");
                    return tokenized;
                } catch (PiiTokenizationUnavailableException e) {
                    // Audited *after* the attempt, not before: emitting the success event first
                    // would put a claim in the trail on the exact request where it did not happen.
                    return degradeTokenize(request, workspaceId, config, allEntities, "request", e);
                }
            }
            case LOG -> {
                auditPiiDetected(workspaceId, "PII_DETECTED", allEntities, "request");
                return request;
            }
            default -> {
                return request;
            }
        }
    }

    @Override
    public String enforceBlob(String content, String workspaceId) {
        if (!properties.isEnabled() || content == null || content.isEmpty()) {
            return content;
        }

        WorkspacePiiConfig config = resolveWorkspaceConfig(workspaceId);
        if (!config.enabled) {
            return content;
        }

        // Batch API: the uploaded JSONL is scanned as one blob.
        PiiScanResult result = detector.scan(content, config.customPatterns);
        if (!result.hasPii()) {
            return content;
        }

        List<PiiEntity> entities = result.entities();
        switch (config.action) {
            case BLOCK -> {
                auditPiiDetected(workspaceId, "PII_DETECTED", entities, "batch_file");
                throw new GatewayException("PII_DETECTED",
                        "Batch input file blocked: PII detected (" + summarizeEntityTypes(entities) + ")");
            }
            case REDACT -> {
                // Cannot fail — see the request path.
                String redacted = redactValues(content, entities, workspaceId);
                auditPiiDetected(workspaceId, "PII_REDACTED", entities, "batch_file");
                return redacted;
            }
            case TOKENIZE -> {
                try {
                    String tokenized = tokenizeValues(content, entities, workspaceId);
                    auditPiiDetected(workspaceId, "PII_TOKENIZED", entities, "batch_file");
                    return tokenized;
                } catch (PiiTokenizationUnavailableException e) {
                    PiiDegradedAction degraded = resolveDegradedAction(workspaceId);
                    auditTokenizeDegraded(workspaceId, entities, "batch_file", degraded, e);
                    if (degraded == PiiDegradedAction.BLOCK) {
                        throw new GatewayException("PII_REDACT_UNAVAILABLE",
                                "Batch input file blocked: PII detected and tokenization is "
                                        + "unavailable on this pod (" + summarizeEntityTypes(entities)
                                        + "). REDACT needs no key and would have removed the value "
                                        + "instead.");
                    }
                    return content;
                }
            }
            case LOG -> {
                auditPiiDetected(workspaceId, "PII_DETECTED", entities, "batch_file");
                return content;
            }
            default -> {
                return content;
            }
        }
    }

    @Override
    public ChatResponse enforceResponse(ChatResponse response, String workspaceId) {
        if (!properties.isEnabled()) {
            return response;
        }

        WorkspacePiiConfig config = resolveWorkspaceConfig(workspaceId);
        if (!config.enabled || !config.scanResponses) {
            return response;
        }

        List<PiiScanResult> results = detector.scanResponse(response, config.customPatterns);
        if (results.isEmpty() || results.stream().noneMatch(PiiScanResult::hasPii)) {
            return response;
        }

        List<PiiEntity> allEntities = results.stream()
                .flatMap(r -> r.entities().stream())
                .toList();

        auditPiiDetected(workspaceId, "PII_OUTPUT_LEAK", allEntities, "response");

        // A leak in the response is never tokenized, whichever action the workspace chose: the
        // caller is the party the value is being withheld from, so handing them a token they can
        // trade back defeats the scan. TOKENIZE protects the value on the way out to a provider.
        if (config.action == PiiAction.BLOCK) {
            // BLOCK refuses, as the streaming path does (terminateStream, PII_BLOCKED_STREAMING).
            // PII_OUTPUT_LEAK is already recorded above, so the evidence survives the refusal.
            throw new GatewayException("PII_DETECTED",
                    "Response blocked: PII detected in the model response ("
                            + summarizeEntityTypes(allEntities) + ")");
        }

        if (config.action == PiiAction.REDACT || config.action == PiiAction.TOKENIZE) {
            return redactResponse(response, workspaceId, config.customPatterns, this::redactValues);
        }

        return response;
    }

    /**
     * The outbound counterpart of {@link #enforceBlob}: refuse under BLOCK, otherwise remove the
     * value irreversibly. TOKENIZE is deliberately treated as REDACT here — see the interface.
     */
    @Override
    public String enforceResponseBlob(String content, String workspaceId) {
        if (!properties.isEnabled() || content == null || content.isEmpty()) {
            return content;
        }
        WorkspacePiiConfig config = resolveWorkspaceConfig(workspaceId);
        if (!config.enabled) {
            return content;
        }
        PiiScanResult result = detector.scan(content, config.customPatterns);
        if (!result.hasPii()) {
            return content;
        }
        List<PiiEntity> entities = result.entities();
        if (config.action == PiiAction.BLOCK) {
            auditPiiDetected(workspaceId, "PII_OUTPUT_LEAK", entities, "response");
            throw new GatewayException("PII_DETECTED",
                    "Response blocked: PII detected (" + summarizeEntityTypes(entities) + ")");
        }
        if (config.action == PiiAction.LOG) {
            auditPiiDetected(workspaceId, "PII_OUTPUT_LEAK", entities, "response");
            return content;
        }
        auditPiiDetected(workspaceId, "PII_OUTPUT_LEAK", entities, "response");
        return detector.redact(content, entities);
    }

    /**
     * The response decision as spans, so a structured caller can place each replacement itself.
     *
     * <p>Shares {@link #enforceResponseBlob}'s rules exactly — same config, same BLOCK throw, same
     * single audit event — and differs only in what it hands back. The replacement is the same
     * irreversible placeholder: outbound text is never tokenized.</p>
     */
    @Override
    public List<PiiEdit> enforceResponseEdits(String content, String workspaceId) {
        if (!properties.isEnabled() || content == null || content.isEmpty()) {
            return List.of();
        }
        WorkspacePiiConfig config = resolveWorkspaceConfig(workspaceId);
        if (!config.enabled) {
            return List.of();
        }
        PiiScanResult result = detector.scan(content, config.customPatterns);
        if (!result.hasPii()) {
            return List.of();
        }
        List<PiiEntity> entities = result.entities();
        auditPiiDetected(workspaceId, "PII_OUTPUT_LEAK", entities, "response");
        if (config.action == PiiAction.BLOCK) {
            throw new GatewayException("PII_DETECTED",
                    "Response blocked: PII detected (" + summarizeEntityTypes(entities) + ")");
        }
        if (config.action == PiiAction.LOG) {
            return List.of();
        }
        return entities.stream()
                .sorted(Comparator.comparingInt(PiiEntity::start))
                .map(e -> new PiiEdit(e.start(), e.end(), "[REDACTED_" + e.type().name() + "]"))
                .toList();
    }

    @Override
    public PiiAction resolvedAction(String workspaceId) {
        WorkspacePiiConfig config = resolveWorkspaceConfig(workspaceId);
        return config.enabled ? config.action : PiiAction.LOG;
    }

    /**
     * Restores {@code {{PII_TYPE_hex}}} tokens in the response to their original values. Gated by
     * {@code pii.auto-detokenize-response} (default off) and only under TOKENIZE. Only tokens the
     * request itself carried are restored. Call this only after the response has been written to
     * any cache; restoring values first would leak raw PII to future cache hits.
     */
    @Override
    public ChatResponse detokenizeResponse(ChatResponse response, ChatRequest request, String workspaceId) {
        if (!properties.isEnabled()) {
            return response;
        }
        WorkspacePiiConfig config = resolveWorkspaceConfig(workspaceId);
        if (!config.enabled || !config.autoDetokenizeResponse) {
            return response;
        }
        // Restoring a value requires having kept one, and only TOKENIZE does. Without this
        // gate a LOG or REDACT workspace would still resolve token-shaped strings a caller supplied
        // — restoring data this request never tokenized.
        if (config.action != PiiAction.TOKENIZE || tokenization == null) {
            return response;
        }
        if (response == null || response.getChoices() == null) {
            return response;
        }
        // Finding tokens in the response, deciding which of them this request may restore,
        // resolving them, rebuilding the response and reporting what could not be resolved all
        // belong to the tokenization service, which owns the token format.
        return tokenization.detokenizeResponse(response, request, workspaceId);
    }

    /**
     * Gateway-emitted response signal, so {@code X-Gateway-*} rather than {@code X-Dvara-*}: in
     * this codebase {@code X-Dvara-*} marks headers a caller sends, and everything the gateway puts
     * on a response goes out as {@code X-Gateway-*}.
     */
    public static final String UNRESOLVED_HEADER = "X-Gateway-Pii-Unresolved";

    @Override
    public ChatRequest stripForCache(ChatRequest request, String workspaceId) {
        if (!properties.isEnabled() || !properties.isStripBeforeCache()) {
            return request;
        }

        WorkspacePiiConfig config = resolveWorkspaceConfig(workspaceId);
        if (!config.enabled) {
            return request;
        }

        List<PiiScanResult> results = detector.scanRequest(request, config.customPatterns);
        if (results.isEmpty() || results.stream().noneMatch(PiiScanResult::hasPii)) {
            return request;
        }

        // Always irreversible on the cache path, whatever the workspace's action is, and never
        // block. A cache key built from tokens would leave a shared cache holding values
        // recoverable by anyone with the workspace key; a placeholder is recoverable by nobody.
        return transformRequest(request, workspaceId, config.customPatterns, this::redactValues);
    }

    /** Irreversible: the detector's own placeholder, and the value is gone. Never fails. */
    private String redactValues(String text, List<PiiEntity> entities, String workspaceId) {
        return detector.redact(text, entities);
    }

    /**
     * Reversible: handed wholesale to the tokenization service, which owns the traversal, the
     * ordering and the token format. What is decided here is only that this workspace asked for
     * TOKENIZE, and either something can do it or the request is refused.
     *
     * <p>Refusing rather than quietly redacting is deliberate. TOKENIZE is chosen precisely when
     * the value has to come back, so substituting the irreversible action would give the workspace
     * the opposite of what it asked for while appearing to succeed.</p>
     */
    private String tokenizeValues(String text, List<PiiEntity> entities, String workspaceId) {
        if (tokenization == null) {
            throw new GatewayException("PII_TOKENIZE_UNAVAILABLE",
                    "pii.action=TOKENIZE needs a reversible token store, which is not part of this "
                            + "build. Use REDACT for irreversible removal, which needs no key "
                            + "material and always works.");
        }
        return tokenization.tokenize(text, entities, workspaceId);
    }

    /**
     * Walk the request, replacing each detected value with whatever {@code replace} returns.
     *
     * <p>The traversal is shared by REDACT and TOKENIZE because it is the part that must not differ:
     * which blocks are visited, how offsets are applied, what happens to a tool result. Only the
     * replacement differs — a placeholder that discards the value, or a token that keeps it.</p>
     */
    private ChatRequest transformRequest(ChatRequest request, String workspaceId,
                                       Map<String, String> customPatterns, Replacement replace) {
        List<MultimodalMessage> redactedMessages = new ArrayList<>();
        for (var message : request.getMessages()) {
            redactedMessages.add(redactMessage(message, workspaceId, customPatterns, replace));
        }
        // Every field, by construction. A rebuild that names fields individually silently drops
        // any it forgets, and any added later; toBuilder() carries whatever ChatRequest has.
        return request.toBuilder().messages(redactedMessages).build();
    }

    /** How a detected run of text becomes its replacement. */
    @FunctionalInterface
    private interface Replacement {
        String apply(String text, List<PiiEntity> entities, String workspaceId);
    }

    private MultimodalMessage redactMessage(MultimodalMessage message, String workspaceId,
                                             Map<String, String> customPatterns, Replacement replace) {
        List<ToolCall> transformedCalls = transformToolCalls(message.getToolCalls(),
                workspaceId, customPatterns, replace);

        if (message.getContent() == null) {
            // A message can be tool calls and nothing else, such as an assistant turn that only
            // calls a function. Its arguments are where the personal data is.
            if (transformedCalls == message.getToolCalls()) {
                return message;
            }
            return message.toBuilder()
                    .content(null)
                    .toolCalls(transformedCalls)
                    .build();
        }

        List<ContentBlock> redactedBlocks = new ArrayList<>();
        for (var block : message.getContent()) {
            redactedBlocks.add(redactBlock(block, workspaceId, customPatterns, replace));
        }

        // toolCalls and toolCallId tie an assistant's tool call to the result that answers it.
        // Dropping them would leave a call without arguments or a result without the id it replies
        // to, which providers reject or mis-associate.
        return message.toBuilder()
                .content(redactedBlocks)
                .toolCalls(transformedCalls)
                .build();
    }

    /**
     * Applies the action to each tool call's arguments, keeping its id and name. The request scan
     * already covers these, so BLOCK refuses on personal data in a function call; this is where
     * REDACT and TOKENIZE rewrite it.
     *
     * <p>The arguments are a JSON string, treated as opaque text. Replacing a value inside it keeps
     * the JSON well-formed, because a placeholder and a token are both ordinary strings with no
     * quotes or backslashes, and parsing to walk the structure would mean re-serializing something
     * a provider is about to parse itself.</p>
     *
     * <p>Returns the original list unchanged when nothing matched, so an untouched message stays
     * identical rather than merely equal.</p>
     */
    private List<ToolCall> transformToolCalls(List<ToolCall> toolCalls, String workspaceId,
                                              Map<String, String> customPatterns, Replacement replace) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return toolCalls;
        }
        List<ToolCall> out = new ArrayList<>(toolCalls.size());
        boolean changed = false;
        for (ToolCall call : toolCalls) {
            String args = call.getArguments();
            if (args == null || args.isEmpty()) {
                out.add(call);
                continue;
            }
            PiiScanResult scan = detector.scan(args, customPatterns);
            if (!scan.hasPii()) {
                out.add(call);
                continue;
            }
            changed = true;
            out.add(ToolCall.builder()
                    .id(call.getId())
                    .name(call.getName())
                    .arguments(replace.apply(args, scan.entities(), workspaceId))
                    .build());
        }
        return changed ? out : toolCalls;
    }

    private ContentBlock redactBlock(ContentBlock block, String workspaceId,
                                      Map<String, String> customPatterns, Replacement replace) {
        if (block instanceof ContentBlock.TextBlock tb) {
            PiiScanResult result = detector.scan(tb.text(), customPatterns);
            if (result.hasPii()) {
                String redacted = replace.apply(tb.text(), result.entities(), workspaceId);
                return new ContentBlock.TextBlock(redacted);
            }
        }
        // A tool result is a `tool`-role message whose output is a text block, so the branch above
        // covers it. There is no separate tool-result block kind to handle.
        return block;
    }

    private ChatResponse redactResponse(ChatResponse response, String workspaceId,
                                         Map<String, String> customPatterns, Replacement replace) {
        List<ChatResponse.Choice> redactedChoices = new ArrayList<>();
        for (var choice : response.getChoices()) {
            if (choice.getMessage() != null) {
                MultimodalMessage redactedMsg =
                        redactMessage(choice.getMessage(), workspaceId, customPatterns, replace);
                redactedChoices.add(ChatResponse.Choice.builder()
                        .index(choice.getIndex())
                        .message(redactedMsg)
                        .finishReason(choice.getFinishReason())
                        .build());
            } else {
                redactedChoices.add(choice);
            }
        }

        return ChatResponse.builder()
                .id(response.getId())
                .model(response.getModel())
                .object(response.getObject())
                .created(response.getCreated())
                .choices(redactedChoices)
                .usage(response.getUsage())
                .gatewayHeaders(response.getGatewayHeaders())
                .build();
    }

    private void auditPiiDetected(String workspaceId, String eventType, List<PiiEntity> entities,
                                   String source) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", source);
        payload.put("entity_count", entities.size());
        payload.put("entity_types", summarizeEntityTypes(entities));
        payload.put("entity_type_counts", entities.stream()
                .collect(Collectors.groupingBy(e -> e.type().name(), Collectors.counting())));
        // Never log actual PII values

        auditWriter.write(new AuditEvent(
                Ids.newId(),
                Instant.now(),
                workspaceId,
                eventType,
                payload));
    }

    private String summarizeEntityTypes(List<PiiEntity> entities) {
        return entities.stream()
                .map(e -> e.type().name())
                .distinct()
                .sorted()
                .collect(Collectors.joining(", "));
    }

    @SuppressWarnings("unchecked")
    /**
     * {@code TOKENIZE} could not be honoured. Apply the posture the workspace chose in advance.
     *
     * <p>Default {@code BLOCK}. Refusing is recoverable (the client retries and nothing is
     * disclosed), whereas serving sends raw personal data to a third-party provider with no
     * recall, so serving takes an explicit opt-in.</p>
     *
     * <p>Reachable only from {@code TOKENIZE}: {@code REDACT} stores nothing and needs no key, so
     * it cannot be unavailable. The emitted {@code PII_REDACT_DEGRADED} event type and the
     * {@code PII_REDACT_UNAVAILABLE} error code are part of the public contract that SIEM rules,
     * dashboards and client code key on, so they keep their names; {@code attempted_action} in
     * the payload says which action could not be honoured.</p>
     */
    private ChatRequest degradeTokenize(ChatRequest request, String workspaceId, WorkspacePiiConfig config,
                                      List<PiiEntity> entities, String stage,
                                      PiiTokenizationUnavailableException cause) {
        PiiDegradedAction degraded = resolveDegradedAction(workspaceId);
        auditTokenizeDegraded(workspaceId, entities, stage, degraded, cause);
        if (degraded == PiiDegradedAction.BLOCK) {
            throw new GatewayException("PII_REDACT_UNAVAILABLE",
                    "Request blocked: PII detected and tokenization is unavailable on this pod ("
                            + summarizeEntityTypes(entities) + "). REDACT needs no key and would "
                            + "have removed the value instead.");
        }
        return request;
    }

    /** {@code Workspace.metadata["pii.degraded-action"]}; anything unreadable means {@code BLOCK}. */
    private PiiDegradedAction resolveDegradedAction(String workspaceId) {
        if (workspaceId == null) {
            return PiiDegradedAction.BLOCK;
        }
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            return PiiDegradedAction.BLOCK;
        }
        // Typed store first, the metadata map as the bridge. The default is BLOCK: this path does
        // not fail open, because sending raw personal data upstream cannot be recalled.
        PiiDegradedAction stored = settingsResolver.resolve(workspace.getId()).degradedAction();
        return stored == null ? PiiDegradedAction.BLOCK : stored;
    }

    /**
     * A distinct event, not a reused {@code PII_DETECTED}.
     *
     * <p>Under {@code LOG} this records an actual disclosure of personal data, and it has to be
     * findable as that rather than blended into the ordinary detection stream. Under {@code BLOCK} it
     * is the reason a request the workspace expected to succeed did not. Entity types and counts only —
     * never values — the same rule the rest of this class follows.
     */
    private void auditTokenizeDegraded(String workspaceId, List<PiiEntity> entities, String stage,
                                     PiiDegradedAction degraded,
                                     PiiTokenizationUnavailableException cause) {
        log.warn("PII tokenization unavailable for workspace {} at {}: {}. Degrading to {} "
                        + "(pii.degraded-action).", workspaceId, stage, cause.getMessage(), degraded);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", stage);
        // Which action could not be honoured. Always TOKENIZE, since REDACT cannot fail, but the
        // event type says REDACT, so stating it here stops a compliance reader inferring the wrong
        // one from the name.
        payload.put("attempted_action", PiiAction.TOKENIZE.name());
        payload.put("degraded_action", degraded.name());
        payload.put("entity_types", summarizeEntityTypes(entities));
        payload.put("entity_count", entities.size());
        payload.put("reason", cause.getMessage());
        payload.put("exposed", degraded == PiiDegradedAction.LOG);
        auditWriter.write(new AuditEvent(
                Ids.newId(),
                Instant.now(),
                workspaceId,
                "PII_REDACT_DEGRADED",
                payload));
    }

    private WorkspacePiiConfig resolveWorkspaceConfig(String workspaceId) {
        boolean enabled = properties.isEnabled();
        PiiAction action = properties.getDefaultAction();
        boolean scanResponses = properties.isScanResponses();
        boolean autoDetokenizeResponse = properties.isAutoDetokenizeResponse();
        Map<String, String> customPatterns = Map.of();

        // One lookup through the typed store, with the legacy metadata map as the bridge. Every
        // field there is nullable because absent means "inherit", and a boolean column cannot say
        // that: a workspace that never mentioned scanning must not read as one that switched it off.
        if (workspaceId != null) {
            var stored = settingsResolver.resolve(workspaceId);
            if (stored.enabled() != null) {
                enabled = stored.enabled();
            }
            if (stored.action() != null) {
                action = stored.action();
            }
            if (stored.scanResponses() != null) {
                scanResponses = stored.scanResponses();
            }
            if (stored.autoDetokenizeResponse() != null) {
                autoDetokenizeResponse = stored.autoDetokenizeResponse();
            }
            if (!stored.customPatterns().isEmpty()) {
                customPatterns = stored.customPatterns();
            }
        }

        return new WorkspacePiiConfig(enabled, action, scanResponses, autoDetokenizeResponse, customPatterns);
    }

    private record WorkspacePiiConfig(boolean enabled, PiiAction action, boolean scanResponses,
                                    boolean autoDetokenizeResponse, Map<String, String> customPatterns) {}
}