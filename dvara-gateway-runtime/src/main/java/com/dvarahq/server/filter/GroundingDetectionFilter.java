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
package com.dvarahq.server.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.ChatFilter;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.FilterOrder;
import com.dvarahq.core.guardrail.GroundingConfig;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GroundingResult;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.core.workspace.settings.SettingsBooleans;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GroundingDetectionFilter implements ChatFilter {

    private static final Logger log = LoggerFactory.getLogger(GroundingDetectionFilter.class);

    private final GroundingDetector detector;
    private final GroundingConfig config;
    private final AuditWriter auditWriter;
    private final GatewayMetrics metrics;
    private final WorkspaceRepository workspaceRepository;

    /** Without a typed settings store; the resolver reads the workspace metadata map instead. */
    public GroundingDetectionFilter(GroundingDetector detector, GroundingConfig config,
                                     AuditWriter auditWriter, GatewayMetrics metrics,
                                     WorkspaceRepository workspaceRepository) {
        this(detector, config, auditWriter, metrics, workspaceRepository,
                (com.dvarahq.core.workspace.settings.GuardrailSettingsRepository) null);
    }

    /**
     * The bean path. ObjectProvider, not a required dependency: the typed store registers with a
     * datasource, and this filter must keep working without one.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public GroundingDetectionFilter(
                                     org.springframework.beans.factory.ObjectProvider<GroundingDetector>
                                             detectorProvider,
                                     GroundingConfig config,
                                     AuditWriter auditWriter, GatewayMetrics metrics,
                                     WorkspaceRepository workspaceRepository,
                                     org.springframework.beans.factory.ObjectProvider<
                                             com.dvarahq.core.workspace.settings.GuardrailSettingsRepository>
                                             guardrailSettingsProvider) {
        this(detectorProvider.getIfAvailable(), config, auditWriter, metrics, workspaceRepository,
                guardrailSettingsProvider.getIfAvailable());
    }

    private GroundingDetectionFilter(GroundingDetector detector, GroundingConfig config,
                                      AuditWriter auditWriter, GatewayMetrics metrics,
                                      WorkspaceRepository workspaceRepository,
                                      com.dvarahq.core.workspace.settings.GuardrailSettingsRepository
                                              guardrailSettings) {
        this.guardrailSettings = guardrailSettings;
        this.detector = detector;
        this.config = config;
        this.auditWriter = auditWriter;
        this.metrics = metrics;
        this.workspaceRepository = workspaceRepository;
    }

    @Override public int order() { return FilterOrder.GROUNDING_DETECTION; }

    /**
     * Refuses before the provider is called when grounding is on and no detector is registered, so
     * the caller is not charged for an answer it would never receive.
     */
    @Override
    public ChatRequest preDispatch(ChatRequest request, FilterContext ctx) {
        if (detector == null && request.getMetadata() != null
                && request.getMetadata().get("grounding.sources") instanceof List<?> sources && !sources.isEmpty()
                && resolveConfig(ctx.getWorkspaceId()).enabled()) {
            throw unavailable();
        }
        return request;
    }

    private static GatewayException unavailable() {
        return new GatewayException("GROUNDING_UNAVAILABLE",
                "Grounding is enabled for this workspace, but this build has no grounding "
                        + "detector: verifying a response against its sources needs an "
                        + "embedding model, which is not present here. Disable grounding for "
                        + "the workspace, or run a build that provides a detector.");
    }

    @Override
    public ChatResponse postDispatch(ChatRequest request, ChatResponse response, FilterContext ctx) {
        GroundingConfig effective = resolveConfig(ctx.getWorkspaceId());
        if (!effective.enabled() || request.getMetadata() == null) {
            return response;
        }

        Object sourcesObj = request.getMetadata().get("grounding.sources");
        if (!(sourcesObj instanceof List<?> rawList) || rawList.isEmpty()) {
            return response;
        }

        List<String> sources = rawList.stream()
                .filter(e -> e instanceof String)
                .map(e -> (String) e)
                .filter(s -> effective.maxSourceLength() <= 0 || s.length() <= effective.maxSourceLength())
                .limit(effective.maxSources() > 0 ? effective.maxSources() : Long.MAX_VALUE)
                .toList();

        if (sources.isEmpty()) return response;

        // Enabled, with sources to check, and no detector to check them: refuse rather than report
        // "grounded" for an answer nothing verified.
        if (detector == null) {
            throw unavailable();   // normally refused in preDispatch; kept for a caller that skips it
        }

        GroundingResult result = detector.check(request, response, sources);
        String actionName = effective.action() != null ? effective.action().name() : "LOG";
        metrics.recordGroundingCheck(String.valueOf(result.grounded()), actionName);
        if (!result.grounded()) {
            auditGrounding(ctx.getWorkspaceId(), result);
            if (effective.action() == GuardrailAction.BLOCK) {
                throw new GatewayException("HALLUCINATION_DETECTED",
                        "Response contains ungrounded claims: "
                                + String.join("; ", result.ungroundedClaims()));
            }
        }
        return response;
    }

    /** The typed guardrail store, or null to read the legacy metadata keys. */
    private final com.dvarahq.core.workspace.settings.GuardrailSettingsRepository guardrailSettings;

    private GroundingConfig resolveConfig(String workspaceId) {
        if (workspaceId == null) return config;
        // Typed row first. An unrecognised stored action inherits the default instead of throwing.
        if (guardrailSettings != null) {
            var stored = guardrailSettings.findByWorkspaceId(workspaceId).orElse(null);
            if (stored != null) {
                return new GroundingConfig(
                        stored.groundingEnabled() == null ? config.enabled() : stored.groundingEnabled(),
                        actionOrDefault(stored.groundingAction(), workspaceId),
                        stored.groundingMaxSources() == null
                                ? config.maxSources() : stored.groundingMaxSources(),
                        stored.groundingMaxSourceLength() == null
                                ? config.maxSourceLength() : stored.groundingMaxSourceLength());
            }
        }
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null || workspace.getMetadata() == null) return config;
        Map<String, Object> meta = workspace.getMetadata();

        // SettingsBooleans rather than parseBoolean: an unreadable value falls back to the
        // configured default instead of silently disabling grounding detection.
        Boolean groundingEnabled = SettingsBooleans.yaml(meta.get("grounding.enabled"));
        boolean enabled = groundingEnabled != null ? groundingEnabled : config.enabled();
        // Parsed like the typed path: a value that does not read warns and inherits the default.
        GuardrailAction action = meta.containsKey("grounding.action")
                ? actionOrDefault(String.valueOf(meta.get("grounding.action")), workspaceId) : config.action();
        int maxSources = intOrDefault(meta.get("grounding.max-sources"), config.maxSources(),
                "grounding.max-sources", workspaceId);
        int maxSourceLength = intOrDefault(meta.get("grounding.max-source-length"), config.maxSourceLength(),
                "grounding.max-source-length", workspaceId);

        return new GroundingConfig(enabled, action, maxSources, maxSourceLength);
    }

    private int intOrDefault(Object value, int fallback, String key, String workspaceId) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            log.warn("Invalid {} '{}' for workspace {}, using default {}", key, value, workspaceId, fallback);
            return fallback;
        }
    }

    private GuardrailAction actionOrDefault(String stored, String workspaceId) {
        if (stored == null || stored.isBlank()) {
            return config.action();
        }
        try {
            return GuardrailAction.valueOf(stored.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            // Inherit the default, and say so: a workspace that misspelt BLOCK is getting LOG, and
            // would otherwise have no way to find out.
            log.warn("Invalid grounding.action '{}' for workspace {}, using default {}",
                    stored, workspaceId, config.action());
            return config.action();
        }
    }

    private void auditGrounding(String workspaceId, GroundingResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grounded", result.grounded());
        payload.put("confidence", result.confidence());
        payload.put("overall_similarity", result.overallSimilarity());
        payload.put("ungrounded_claim_count", result.ungroundedClaims().size());
        // Hashes, not the sentences: the claims are the model's text before PII redaction, and the audit
        // chain cannot be edited afterwards.
        payload.put("ungrounded_claim_hashes", com.dvarahq.core.guardrail.ClaimDigests.of(result.ungroundedClaims()));

        auditWriter.write(new AuditEvent(
                Ids.newId(), Instant.now(),
                workspaceId, "HALLUCINATION_DETECTED", payload));
    }
}