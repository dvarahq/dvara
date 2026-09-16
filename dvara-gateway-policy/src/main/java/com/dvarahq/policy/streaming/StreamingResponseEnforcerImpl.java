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
package com.dvarahq.policy.streaming;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.guardrail.GroundingConfig;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.policy.guardrail.GuardrailProperties;
import com.dvarahq.pii.PiiProperties;

import java.util.Iterator;

/**
 * The scanning implementation of {@link StreamingResponseEnforcer}.
 * Resolves per-workspace configuration and creates a {@link GuardedSseIterator}
 * that buffers and scans SSE chunks for PII and guardrail violations.
 */
public class StreamingResponseEnforcerImpl implements StreamingResponseEnforcer {

    private final AuditWriter auditWriter;
    private final PiiProperties piiProperties;
    private final GuardrailProperties guardrailProperties;
    private final com.dvarahq.core.enforcement.StreamingEnforcementEngine engine;
    private final com.dvarahq.core.enforcement.StreamingPostureProvider postureProvider;
    private final com.dvarahq.core.enforcement.StreamingEnforcementTelemetry telemetry;

    public StreamingResponseEnforcerImpl(PiiDetector piiDetector,
                                          GuardrailDetector guardrailDetector,
                                          AuditWriter auditWriter,
                                          WorkspaceRepository workspaceRepository,
                                          PiiProperties piiProperties,
                                          GuardrailProperties guardrailProperties) {
        this(piiDetector, guardrailDetector, auditWriter, workspaceRepository,
                piiProperties, guardrailProperties,
                (req, resp, sources) -> com.dvarahq.core.guardrail.GroundingResult.GROUNDED,
                GroundingConfig.DISABLED);
    }

    public StreamingResponseEnforcerImpl(PiiDetector piiDetector,
                                          GuardrailDetector guardrailDetector,
                                          AuditWriter auditWriter,
                                          WorkspaceRepository workspaceRepository,
                                          PiiProperties piiProperties,
                                          GuardrailProperties guardrailProperties,
                                          GroundingDetector groundingDetector,
                                          GroundingConfig groundingConfig) {
        this(piiDetector, guardrailDetector, auditWriter, workspaceRepository, piiProperties,
                guardrailProperties, groundingDetector, groundingConfig, null, null);
    }

    /**
     * With the typed settings stores. Both nullable: a pod with no datasource has neither, and absent
     * must mean "inherit", never "this workspace turned everything off".
     */
    public StreamingResponseEnforcerImpl(PiiDetector piiDetector,
                                          GuardrailDetector guardrailDetector,
                                          AuditWriter auditWriter,
                                          WorkspaceRepository workspaceRepository,
                                          PiiProperties piiProperties,
                                          GuardrailProperties guardrailProperties,
                                          GroundingDetector groundingDetector,
                                          GroundingConfig groundingConfig,
                                          com.dvarahq.core.workspace.settings.PiiSettingsRepository piiSettings,
                                          com.dvarahq.core.workspace.settings.GuardrailSettingsRepository guardrailSettings) {
        this(piiDetector, guardrailDetector, auditWriter, workspaceRepository, piiProperties,
                guardrailProperties, groundingDetector, groundingConfig, piiSettings,
                guardrailSettings, com.dvarahq.core.enforcement.StreamingEnforcementTelemetry.NOOP);
    }

    public StreamingResponseEnforcerImpl(PiiDetector piiDetector,
                                          GuardrailDetector guardrailDetector,
                                          AuditWriter auditWriter,
                                          WorkspaceRepository workspaceRepository,
                                          PiiProperties piiProperties,
                                          GuardrailProperties guardrailProperties,
                                          GroundingDetector groundingDetector,
                                          GroundingConfig groundingConfig,
                                          com.dvarahq.core.workspace.settings.PiiSettingsRepository piiSettings,
                                          com.dvarahq.core.workspace.settings.GuardrailSettingsRepository guardrailSettings,
                                          com.dvarahq.core.enforcement.StreamingEnforcementTelemetry telemetry) {
        this(auditWriter, piiProperties, guardrailProperties,
                new DefaultStreamingEnforcementEngine(piiDetector, guardrailDetector, groundingDetector),
                new DefaultStreamingPostureProvider(workspaceRepository, piiSettings, guardrailSettings,
                        piiProperties, guardrailProperties, groundingConfig), telemetry);
    }

    /** Runtime form: the same engine and posture provider beans are shared with the A2A plane. */
    public StreamingResponseEnforcerImpl(
            AuditWriter auditWriter,
            PiiProperties piiProperties,
            GuardrailProperties guardrailProperties,
            com.dvarahq.core.enforcement.StreamingEnforcementEngine engine,
            com.dvarahq.core.enforcement.StreamingPostureProvider postureProvider,
            com.dvarahq.core.enforcement.StreamingEnforcementTelemetry telemetry) {
        this.auditWriter = auditWriter;
        this.piiProperties = piiProperties;
        this.guardrailProperties = guardrailProperties;
        this.engine = java.util.Objects.requireNonNull(engine, "engine");
        this.postureProvider = java.util.Objects.requireNonNull(postureProvider, "postureProvider");
        this.telemetry = telemetry == null
                ? com.dvarahq.core.enforcement.StreamingEnforcementTelemetry.NOOP : telemetry;
    }

    @Override
    public Iterator<SseChunk> wrap(Iterator<SseChunk> upstream, String workspaceId) {
        return wrap(upstream, workspaceId, null);
    }

    @Override
    public Iterator<SseChunk> wrap(Iterator<SseChunk> upstream, String workspaceId, ChatRequest request) {
        java.util.List<String> sourceDocuments = extractSourceDocuments(request);
        var posture = postureProvider.resolve(workspaceId, sourceDocuments);
        StreamingEnforcementConfig config = configFor(posture);

        if (!config.piiEnabled() && !config.guardrailEnabled() && !config.groundingEnabled()) {
            return upstream; // passthrough
        }

        return new GuardedSseIterator(upstream, engine, auditWriter, workspaceId, config, posture,
                telemetry);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<String> extractSourceDocuments(ChatRequest request) {
        if (request == null || request.getMetadata() == null) {
            return java.util.List.of();
        }
        Object sourcesObj = request.getMetadata().get("grounding.sources");
        if (!(sourcesObj instanceof java.util.List<?> rawList) || rawList.isEmpty()) {
            return java.util.List.of();
        }
        return rawList.stream()
                .filter(e -> e instanceof String)
                .map(e -> (String) e)
                .toList();
    }

    /**
     * Delegates to the one shared resolver. Both planes resolve through
     * {@link StreamingPostureResolver}; this method only maps local properties into the shared
     * shape and back.
     */
    private StreamingEnforcementConfig configFor(
            com.dvarahq.core.enforcement.StreamingPosture posture) {
        int scanWindow = Math.max(32, Math.min(piiProperties.getStreamingScanWindowSize(),
                guardrailProperties.getStreamingScanWindowSize()));
        int overlap = Math.max(16, Math.max(piiProperties.getStreamingOverlapMargin(),
                guardrailProperties.getStreamingOverlapMargin()));
        if (overlap >= scanWindow) {
            overlap = scanWindow / 2;
        }

        return new StreamingEnforcementConfig(
                posture.piiEnabled(), posture.piiAction(),
                posture.guardrailEnabled(), posture.guardrailAction(), posture.guardrailRiskThreshold(),
                scanWindow, overlap,
                posture.groundingEnabled(), posture.groundingAction(),
                posture.customPiiPatterns(),
                piiProperties.getStreamingMaxHeldCharacters());
    }

}
