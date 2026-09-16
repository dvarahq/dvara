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
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.providers.mock.MockMatcherTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class MeteredMockMatcherTelemetryTest {

    private SimpleMeterRegistry registry;
    private AuditWriter auditWriter;
    private MeteredMockMatcherTelemetry telemetry;

    private static GatewayProperties propsWithSampleRate(double rate) {
        GatewayProperties props = new GatewayProperties();
        props.getProviders().getMock().setAuditSampleRate(rate);
        return props;
    }

    private static ChatRequest req(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .build();
    }

    private static ChatRequest reqWithWorkspace(String model, String workspaceId) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .metadata(Map.of("workspace_id", workspaceId))
                .build();
    }

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        auditWriter = mock(AuditWriter.class);
        telemetry = new MeteredMockMatcherTelemetry(registry, auditWriter, propsWithSampleRate(1.0));
    }

    // ---------------------------------------------------------------------
    // matcherFired — Prometheus counter + audit event
    // ---------------------------------------------------------------------

    @Test
    void matcherFired_incrementsPrometheusCounter_withScenarioAndSourceLabels() {
        telemetry.matcherFired("billing", MockMatcherTelemetry.Source.YAML, req("mock/billing"));

        Counter counter = registry.find("gateway_mock_matcher_fires_total")
                .tag("scenario", "billing")
                .tag("source", "yaml")
                .counter();

        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void matcherFired_multipleCalls_accumulateOnSameCounter() {
        telemetry.matcherFired("hit", MockMatcherTelemetry.Source.FILE, req("mock/hit"));
        telemetry.matcherFired("hit", MockMatcherTelemetry.Source.FILE, req("mock/hit"));
        telemetry.matcherFired("hit", MockMatcherTelemetry.Source.FILE, req("mock/hit"));

        double count = registry.find("gateway_mock_matcher_fires_total")
                .tag("scenario", "hit")
                .tag("source", "file")
                .counter()
                .count();

        assertThat(count).isEqualTo(3.0);
    }

    @Test
    void matcherFired_differentScenarios_useSeparateCounters() {
        telemetry.matcherFired("billing", MockMatcherTelemetry.Source.YAML, req("mock/billing"));
        telemetry.matcherFired("weather", MockMatcherTelemetry.Source.YAML, req("mock/weather"));

        double billingCount = registry.find("gateway_mock_matcher_fires_total")
                .tag("scenario", "billing").counter().count();
        double weatherCount = registry.find("gateway_mock_matcher_fires_total")
                .tag("scenario", "weather").counter().count();

        assertThat(billingCount).isEqualTo(1.0);
        assertThat(weatherCount).isEqualTo(1.0);
    }

    @Test
    void matcherFired_differentSources_useSeparateCounters() {
        telemetry.matcherFired("same-name", MockMatcherTelemetry.Source.FILE, req("mock/same"));
        telemetry.matcherFired("same-name", MockMatcherTelemetry.Source.YAML, req("mock/same"));

        double fileCount = registry.find("gateway_mock_matcher_fires_total")
                .tag("scenario", "same-name").tag("source", "file").counter().count();
        double yamlCount = registry.find("gateway_mock_matcher_fires_total")
                .tag("scenario", "same-name").tag("source", "yaml").counter().count();

        assertThat(fileCount).isEqualTo(1.0);
        assertThat(yamlCount).isEqualTo(1.0);
    }

    @Test
    void matcherFired_emitsAuditEvent_withScenarioAndSource() {
        telemetry.matcherFired("billing", MockMatcherTelemetry.Source.YAML, req("mock/billing"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter).write(captor.capture());
        AuditEvent event = captor.getValue();

        assertThat(event.eventType()).isEqualTo("MOCK_MATCHER_FIRED");
        assertThat(event.payload()).containsEntry("scenario", "billing");
        assertThat(event.payload()).containsEntry("source", "yaml");
    }

    @Test
    void matcherFired_nullScenarioName_usesUnknownLabel() {
        telemetry.matcherFired(null, MockMatcherTelemetry.Source.FILE, req("mock/any"));

        Counter counter = registry.find("gateway_mock_matcher_fires_total")
                .tag("scenario", "unknown")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void matcherFired_nullSource_usesUnknownLabel() {
        telemetry.matcherFired("billing", null, req("mock/billing"));

        Counter counter = registry.find("gateway_mock_matcher_fires_total")
                .tag("scenario", "billing")
                .tag("source", "unknown")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------------
    // scenariosReloaded — Prometheus counter + audit event
    // ---------------------------------------------------------------------

    @Test
    void scenariosReloaded_incrementsUnlabelledCounter() {
        telemetry.scenariosReloaded(3);
        telemetry.scenariosReloaded(5);

        Counter counter = registry.find("gateway_mock_scenarios_reloaded_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);
    }

    @Test
    void scenariosReloaded_emitsAuditEvent_withScenarioCount() {
        telemetry.scenariosReloaded(7);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter).write(captor.capture());
        AuditEvent event = captor.getValue();

        assertThat(event.eventType()).isEqualTo("MOCK_SCENARIO_RELOADED");
        assertThat(event.payload()).containsEntry("scenario_count", 7);
    }

    @Test
    void scenariosReloaded_zeroScenarios_stillEmits() {
        telemetry.scenariosReloaded(0);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter).write(captor.capture());
        assertThat(captor.getValue().payload()).containsEntry("scenario_count", 0);
    }

    // ---------------------------------------------------------------------
    // Isolation — no cross-talk between methods
    // ---------------------------------------------------------------------

    @Test
    void matcherFired_doesNotTouchReloadCounter() {
        telemetry.matcherFired("x", MockMatcherTelemetry.Source.YAML, req("mock/x"));

        Counter reloadCounter = registry.find("gateway_mock_scenarios_reloaded_total").counter();
        assertThat(reloadCounter).isNull(); // never registered
    }

    @Test
    void scenariosReloaded_doesNotTouchFireCounters() {
        telemetry.scenariosReloaded(1);

        assertThat(registry.find("gateway_mock_matcher_fires_total").counter()).isNull();
    }

    @Test
    void noCalls_noCountersRegistered_noAuditEvents() {
        // Brand-new telemetry with zero activity — nothing registered, nothing audited.
        assertThat(registry.find("gateway_mock_matcher_fires_total").counter()).isNull();
        assertThat(registry.find("gateway_mock_scenarios_reloaded_total").counter()).isNull();
        verifyNoInteractions(auditWriter);
    }

    // ---------------------------------------------------------------------
    // Workspace extraction from request.metadata
    // ---------------------------------------------------------------------

    @Test
    void matcherFired_extractsWorkspaceIdFromRequestMetadata() {
        telemetry.matcherFired("billing", MockMatcherTelemetry.Source.YAML,
                reqWithWorkspace("mock/billing", "workspace-acme"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter).write(captor.capture());
        assertThat(captor.getValue().workspaceId()).isEqualTo("workspace-acme");
    }

    @Test
    void matcherFired_nullRequest_leavesWorkspaceIdNull() {
        telemetry.matcherFired("x", MockMatcherTelemetry.Source.YAML, null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter).write(captor.capture());
        assertThat(captor.getValue().workspaceId()).isNull();
    }

    @Test
    void matcherFired_requestWithoutMetadata_leavesWorkspaceIdNull() {
        telemetry.matcherFired("x", MockMatcherTelemetry.Source.YAML, req("mock/no-metadata"));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditWriter).write(captor.capture());
        assertThat(captor.getValue().workspaceId()).isNull();
    }

    // ---------------------------------------------------------------------
    // Audit sampling — counter always fires, audit event is sampled
    // ---------------------------------------------------------------------

    @Test
    void sampleRateZero_counterStillIncrements_butAuditEventSkipped() {
        var zeroSampleTelemetry = new MeteredMockMatcherTelemetry(
                registry, auditWriter, propsWithSampleRate(0.0));

        for (int i = 0; i < 100; i++) {
            zeroSampleTelemetry.matcherFired("hit", MockMatcherTelemetry.Source.YAML, req("mock/hit"));
        }

        // Counter accumulates every call even at sample rate 0
        double count = registry.find("gateway_mock_matcher_fires_total")
                .tag("scenario", "hit").counter().count();
        assertThat(count).isEqualTo(100.0);

        // But zero audit events are written
        verify(auditWriter, never()).write(any());
    }

    @Test
    void sampleRateOne_alwaysEmitsAudit() {
        // Default setUp uses 1.0 — verify 100 fires produce 100 audit writes.
        for (int i = 0; i < 100; i++) {
            telemetry.matcherFired("hit", MockMatcherTelemetry.Source.YAML, req("mock/hit"));
        }
        verify(auditWriter, times(100)).write(any());
    }

    @Test
    void sampleRateOutOfRange_isClamped() {
        // -1.0 clamps to 0.0 (no audit)
        var negSample = new MeteredMockMatcherTelemetry(
                new SimpleMeterRegistry(), mock(AuditWriter.class), propsWithSampleRate(-1.0));
        negSample.matcherFired("x", MockMatcherTelemetry.Source.YAML, req("mock/x"));
        // No exception: the clamp shows up as no audit writes.

        // 2.0 clamps to 1.0 (always audit)
        AuditWriter capturingWriter = mock(AuditWriter.class);
        var highSample = new MeteredMockMatcherTelemetry(
                new SimpleMeterRegistry(), capturingWriter, propsWithSampleRate(2.0));
        highSample.matcherFired("x", MockMatcherTelemetry.Source.YAML, req("mock/x"));
        verify(capturingWriter, times(1)).write(any());
    }

    // ---------------------------------------------------------------------
    // matcherFellThrough — Prometheus counter (no audit event)
    // ---------------------------------------------------------------------

    @Test
    void matcherFellThrough_incrementsFallthroughCounter() {
        telemetry.matcherFellThrough(req("mock/x"));

        Counter counter = registry.find("gateway_mock_matcher_fallthrough_total").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void matcherFellThrough_multipleCalls_accumulate() {
        telemetry.matcherFellThrough(req("mock/a"));
        telemetry.matcherFellThrough(req("mock/b"));
        telemetry.matcherFellThrough(req("mock/c"));

        assertThat(registry.find("gateway_mock_matcher_fallthrough_total").counter().count())
                .isEqualTo(3.0);
    }

    @Test
    void matcherFellThrough_doesNotEmitAuditEvent() {
        // A per-request fallthrough audit would dominate the audit volume.
        // Only the counter increments — no AuditWriter call.
        telemetry.matcherFellThrough(req("mock/x"));
        telemetry.matcherFellThrough(req("mock/y"));

        verifyNoInteractions(auditWriter);
    }

    @Test
    void matcherFellThrough_doesNotTouchFireCounters() {
        telemetry.matcherFellThrough(req("mock/x"));

        assertThat(registry.find("gateway_mock_matcher_fires_total").counter()).isNull();
        assertThat(registry.find("gateway_mock_scenarios_reloaded_total").counter()).isNull();
    }

    @Test
    void sampleRateHalf_approximatelyHalfOfFiresAudited() {
        AuditWriter capturingWriter = mock(AuditWriter.class);
        var halfSampleTelemetry = new MeteredMockMatcherTelemetry(
                new SimpleMeterRegistry(), capturingWriter, propsWithSampleRate(0.5));

        int fires = 2000; // large sample to keep the probability bounds tight
        for (int i = 0; i < fires; i++) {
            halfSampleTelemetry.matcherFired("hit", MockMatcherTelemetry.Source.YAML, req("mock/hit"));
        }

        // With 2000 fires at p=0.5, roughly 1000 writes are expected. The band is wide
        // (750 to 1250) so the random draw cannot make the test flaky.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(capturingWriter, org.mockito.Mockito.atLeast(750)).write(captor.capture());
        assertThat(captor.getAllValues().size()).isBetween(750, 1250);
    }
}