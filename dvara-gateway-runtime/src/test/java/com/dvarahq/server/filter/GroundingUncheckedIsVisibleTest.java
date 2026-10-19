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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.guardrail.GroundingConfig;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GroundingResult;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.metrics.MeteredStreamingEnforcementTelemetry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Grounding judges a response only against the source documents its request sends. When grounding is on and a request
 * sends none, the response cannot be checked; that must be visible, not read as "grounding is on, so this was checked".
 */
class GroundingUncheckedIsVisibleTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GatewayMetrics metrics = new GatewayMetrics(registry, (RegionContext) Optional::empty);
    private final List<AuditEvent> audited = new ArrayList<>();
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final GroundingDetector detector = mock(GroundingDetector.class);
    private final ChatResponse response = ChatResponse.builder().id("r1").build();
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureWarnings() {
        when(workspaces.findById(any())).thenReturn(Optional.empty());
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(GatewayMetrics.class)).addAppender(logs);
    }

    @AfterEach
    void release() {
        ((Logger) LoggerFactory.getLogger(GatewayMetrics.class)).detachAppender(logs);
    }

    private GroundingDetectionFilter filter(boolean enabled, int maxSourceLength) {
        return new GroundingDetectionFilter(detector, new GroundingConfig(enabled, GuardrailAction.LOG, 50, maxSourceLength),
                audited::add, metrics, workspaces);
    }

    private static ChatRequest request(Map<String, Object> metadata) {
        return ChatRequest.builder().model("m").metadata(metadata).build();
    }

    private static FilterContext ctx(String workspace) {
        return FilterContext.builder().workspaceId(workspace).build();
    }

    private double unchecked(String workspace, String reason, String mode) {
        Counter c = registry.find("gateway_grounding_unchecked_total")
                .tags("workspace", workspace, "reason", reason, "mode", mode).counter();
        return c == null ? 0 : c.count();
    }

    private long warnings() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .filter(e -> e.getFormattedMessage().contains("was not checked")).count();
    }

    @Test
    void aRequestWithNoSourcesIsCountedAndSaysUnchecked() {
        ChatResponse out = filter(true, 10_000).postDispatch(request(null), response, ctx("acme"));

        assertThat(out.getGatewayHeaders()).containsEntry(GroundingDetectionFilter.GROUNDING_HEADER, "unchecked");
        assertThat(unchecked("acme", "no_sources", "non_streaming")).isEqualTo(1.0);
        verify(detector, never()).check(any(), any(), any());
        assertThat(out.getId()).isEqualTo("r1");
    }

    @Test
    void anEmptyOrNonTextSourceListIsNoSources() {
        var f = filter(true, 10_000);
        f.postDispatch(request(Map.of("grounding.sources", List.of())), response, ctx("acme"));
        f.postDispatch(request(Map.of("grounding.sources", List.of(42))), response, ctx("acme"));
        f.postDispatch(request(Map.of("other", "x")), response, ctx("acme"));

        assertThat(unchecked("acme", "no_sources", "non_streaming")).isEqualTo(3.0);
    }

    @Test
    void sourcesAllDroppedByTheLimitsAreSaidAsSuch() {
        ChatResponse out = filter(true, 5).postDispatch(
                request(Map.of("grounding.sources", List.of("a source longer than five"))), response, ctx("acme"));

        assertThat(out.getGatewayHeaders()).containsEntry(GroundingDetectionFilter.GROUNDING_HEADER, "unchecked");
        assertThat(unchecked("acme", "sources_over_limits", "non_streaming")).isEqualTo(1.0);
        assertThat(unchecked("acme", "no_sources", "non_streaming")).isZero();
    }

    @Test
    void aCheckedResponseSaysItsVerdictAndIsNotCountedUnchecked() {
        GroundingResult grounded = mock(GroundingResult.class);
        when(grounded.grounded()).thenReturn(true);
        when(detector.check(any(), any(), any())).thenReturn(grounded);
        ChatResponse withHeader = response.toBuilder().gatewayHeaders(Map.of("X-Gateway-Other", "kept")).build();

        ChatResponse out = filter(true, 10_000).postDispatch(
                request(Map.of("grounding.sources", List.of("the source"))), withHeader, ctx("acme"));

        assertThat(out.getGatewayHeaders())
                .containsEntry(GroundingDetectionFilter.GROUNDING_HEADER, "grounded")
                .containsEntry("X-Gateway-Other", "kept");
        assertThat(registry.find("gateway_grounding_unchecked_total").counter()).isNull();
    }

    @Test
    void anUngroundedResponseUnderLogSaysUngrounded() {
        GroundingResult ungrounded = mock(GroundingResult.class);
        when(ungrounded.grounded()).thenReturn(false);
        when(ungrounded.ungroundedClaims()).thenReturn(List.of("a claim"));
        when(detector.check(any(), any(), any())).thenReturn(ungrounded);

        ChatResponse out = filter(true, 10_000).postDispatch(
                request(Map.of("grounding.sources", List.of("the source"))), response, ctx("acme"));

        assertThat(out.getGatewayHeaders()).containsEntry(GroundingDetectionFilter.GROUNDING_HEADER, "ungrounded");
    }

    @Test
    void groundingOffSaysAndCountsNothing() {
        ChatResponse out = filter(false, 10_000).postDispatch(request(null), response, ctx("acme"));

        assertThat(out).isSameAs(response);
        assertThat(registry.find("gateway_grounding_unchecked_total").counter()).isNull();
        assertThat(warnings()).isZero();
    }

    @Test
    void theWarningIsWrittenOncePerWorkspace() {
        var f = filter(true, 10_000);
        f.postDispatch(request(null), response, ctx("acme"));
        f.postDispatch(request(null), response, ctx("acme"));
        assertThat(warnings()).isEqualTo(1);

        f.postDispatch(request(null), response, ctx("globex"));
        assertThat(warnings()).isEqualTo(2);
        assertThat(unchecked("acme", "no_sources", "non_streaming")).isEqualTo(2.0);
    }

    @Test
    void aStreamReportedUncheckedSharesTheCounterAndTheOneWarning() {
        filter(true, 10_000).postDispatch(request(null), response, ctx("acme"));
        new MeteredStreamingEnforcementTelemetry(registry, metrics).groundingUnchecked("LLM", "acme", "no_sources");

        assertThat(unchecked("acme", "no_sources", "streaming")).isEqualTo(1.0);
        assertThat(warnings()).isEqualTo(1);
    }
}
