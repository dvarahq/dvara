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
package com.dvarahq.server.metrics;

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.providers.mock.MockMatcherTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Metered + audited implementation of {@link MockMatcherTelemetry}.
 *
 * <p>Wires the Mock provider's matcher lifecycle into Micrometer counters
 * and the audit writer:
 *
 * <ul>
 *   <li>{@code gateway_mock_matcher_fires_total{scenario, source}} —
 *       incremented every time a matcher's predicate returns true.
 *       <b>Always</b> emitted, regardless of the audit sample rate.</li>
 *   <li>{@code gateway_mock_scenarios_reloaded_total} — incremented on every
 *       successful reload of the scenarios directory.</li>
 *   <li>{@code MOCK_MATCHER_FIRED} audit event — emitted per matcher fire,
 *       payload: scenario + source, envelope workspace pulled from
 *       {@code request.metadata["workspace_id"]} when present. Subject to
 *       the {@code dvara.llm-gateway.providers.mock.audit-sample-rate} property for
 *       high-throughput load tests; default 1.0 (always emit).</li>
 *   <li>{@code MOCK_SCENARIO_RELOADED} audit event — emitted on every
 *       successful reload, payload: scenario_count.</li>
 * </ul>
 *
 * <p>Activated only when {@code dvara.llm-gateway.providers.mock.enabled=true} — for
 * a standard production deployment with Mock off, this bean never registers
 * and {@code MockProvider} falls back to the {@link MockMatcherTelemetry#NOOP}
 * default.
 */
@Component
@ConditionalOnBean(AuditWriter.class)
@ConditionalOnProperty(name = "dvara.llm-gateway.providers.mock.enabled", havingValue = "true")
public class MeteredMockMatcherTelemetry implements MockMatcherTelemetry {

    private final MeterRegistry meterRegistry;
    private final AuditWriter auditWriter;
    private final double auditSampleRate;

    public MeteredMockMatcherTelemetry(MeterRegistry meterRegistry,
                                       AuditWriter auditWriter,
                                       GatewayProperties properties) {
        this.meterRegistry = meterRegistry;
        this.auditWriter = auditWriter;
        double configured = properties.getProviders().getMock().getAuditSampleRate();
        // Clamp to [0.0, 1.0] to keep the probability check well-defined.
        this.auditSampleRate = Math.min(Math.max(configured, 0.0), 1.0);
    }

    @Override
    public void matcherFired(String scenarioName, Source source, ChatRequest request) {
        // Counter always fires — operators rely on the cumulative rate being
        // accurate even when audit events are sampled.
        Counter.builder("gateway_mock_matcher_fires_total")
                .description("Number of times a Mock provider matcher fired, labelled by scenario name and source")
                .tag("scenario", scenarioName != null ? scenarioName : "unknown")
                .tag("source", source != null ? source.name().toLowerCase() : "unknown")
                .register(meterRegistry)
                .increment();

        if (!shouldEmitAudit()) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("scenario", scenarioName);
        payload.put("source", source != null ? source.name().toLowerCase() : null);
        auditWriter.write(new AuditEvent(Ids.newId(), Instant.now(),
                extractWorkspaceId(request), "MOCK_MATCHER_FIRED", payload));
    }

    @Override
    public void matcherFellThrough(ChatRequest request) {
        // Counter always fires so the matcher hit rate can be computed
        // accurately from rate(fires) / (rate(fires) + rate(fallthrough)).
        Counter.builder("gateway_mock_matcher_fallthrough_total")
                .description("Number of mock requests where no matcher fired and the default response was used")
                .register(meterRegistry)
                .increment();

        // No audit event for fallthroughs — the absence of MOCK_MATCHER_FIRED
        // is itself the audit signal, and emitting a per-request event for
        // every default-response call would dominate the audit volume.
    }

    @Override
    public void scenariosReloaded(int scenarioCount) {
        Counter.builder("gateway_mock_scenarios_reloaded_total")
                .description("Number of times the Mock scenarios directory was successfully reloaded")
                .register(meterRegistry)
                .increment();

        Map<String, Object> payload = new HashMap<>();
        payload.put("scenario_count", scenarioCount);
        auditWriter.write(new AuditEvent(Ids.newId(), Instant.now(),
                null, "MOCK_SCENARIO_RELOADED", payload));
    }

    /**
     * Extracts the workspace ID from the incoming chat request's metadata, if
     * one was attached upstream by the authentication layer. Returns
     * {@code null} when the request carries no workspace context — the audit
     * event envelope will then show a null workspace, which is the same
     * behavior as other non-workspace-scoped system events.
     */
    private static String extractWorkspaceId(ChatRequest request) {
        if (request == null || request.getMetadata() == null) {
            return null;
        }
        Object workspaceId = request.getMetadata().get("workspace_id");
        return workspaceId != null ? Objects.toString(workspaceId) : null;
    }

    /**
     * Returns {@code true} when the audit event should be emitted for this
     * call. Uses {@link ThreadLocalRandom} so concurrent fires don't contend
     * on a single shared Random instance. Fast path at 1.0 and 0.0 avoids
     * the random draw entirely.
     */
    private boolean shouldEmitAudit() {
        if (auditSampleRate >= 1.0) return true;
        if (auditSampleRate <= 0.0) return false;
        return ThreadLocalRandom.current().nextDouble() < auditSampleRate;
    }
}