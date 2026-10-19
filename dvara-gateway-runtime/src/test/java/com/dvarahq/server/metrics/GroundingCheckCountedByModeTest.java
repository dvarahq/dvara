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
import com.dvarahq.server.filter.GroundingDetectionFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Streamed and non-streamed grounding checks reach one counter, told apart by its mode tag. */
class GroundingCheckCountedByModeTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GatewayMetrics metrics = new GatewayMetrics(registry, (RegionContext) Optional::empty);

    private double checks(String grounded, String action, String mode) {
        Counter c = registry.find("gateway_grounding_check_total")
                .tags("grounded", grounded, "action", action, "mode", mode).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void aStreamedCheckIsCountedWithModeStreaming() {
        new MeteredStreamingEnforcementTelemetry(registry, metrics).groundingChecked("LLM", "acme", false, "BLOCK");
        assertThat(checks("false", "BLOCK", "streaming")).isEqualTo(1.0);
        assertThat(checks("false", "BLOCK", "non_streaming")).isZero();
    }

    @Test
    void aNonStreamedCheckIsCountedWithModeNonStreaming() {
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById(any())).thenReturn(Optional.empty());
        GroundingDetector detector = (req, resp, sources) -> GroundingResult.GROUNDED;
        List<AuditEvent> audited = new ArrayList<>();
        var filter = new GroundingDetectionFilter(detector, new GroundingConfig(true, GuardrailAction.LOG, 50, 10_000),
                audited::add, metrics, workspaces);
        filter.postDispatch(ChatRequest.builder().model("m").metadata(Map.of("grounding.sources", List.of("s"))).build(),
                ChatResponse.builder().build(), FilterContext.builder().workspaceId("acme").build());

        assertThat(checks("true", "LOG", "non_streaming")).isEqualTo(1.0);
        assertThat(checks("true", "LOG", "streaming")).isZero();
    }

    @Test
    void withoutGatewayMetricsAStreamedCheckIsNotReported() {
        new MeteredStreamingEnforcementTelemetry(registry).groundingChecked("LLM", "acme", true, "LOG");
        assertThat(registry.find("gateway_grounding_check_total").counter()).isNull();
    }
}
