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

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.guardrail.GroundingConfig;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GroundingResult;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GroundingDetectionFilterSafetyTest {

    private static final String CLAIM = "Alice Smith (alice@example.com) was approved.";

    private final List<AuditEvent> audited = new ArrayList<>();
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final GatewayMetrics metrics = new GatewayMetrics(new SimpleMeterRegistry(), (RegionContext) Optional::empty);
    private final ChatRequest request = ChatRequest.builder().model("m")
            .metadata(Map.of("grounding.sources", List.of("a source document"))).build();
    private final FilterContext ctx = FilterContext.builder().workspaceId("acme").build();

    private void workspaceMetadata(Map<String, Object> metadata) {
        Workspace ws = mock(Workspace.class);
        when(ws.getMetadata()).thenReturn(metadata);
        when(workspaces.findById("acme")).thenReturn(Optional.of(ws));
    }

    private GroundingDetector detector(boolean grounded) {
        GroundingResult result = mock(GroundingResult.class);
        when(result.grounded()).thenReturn(grounded);
        when(result.ungroundedClaims()).thenReturn(grounded ? List.of() : List.of(CLAIM));
        GroundingDetector detector = mock(GroundingDetector.class);
        when(detector.check(any(), any(), any())).thenReturn(result);
        return detector;
    }

    // A mistyped metadata value must not throw: this filter runs after the provider call has already been made.
    @Test
    void aMistypedMetadataValueFallsBackInsteadOfFailingTheCall() {
        workspaceMetadata(Map.of("grounding.enabled", true, "grounding.action", "blok", "grounding.max-sources", "fifty"));
        var filter = new GroundingDetectionFilter(detector(true), new GroundingConfig(false, GuardrailAction.LOG, 50, 10000),
                audited::add, metrics, workspaces);

        ChatResponse response = ChatResponse.builder().build();
        assertThat(filter.postDispatch(request, response, ctx)).isSameAs(response);
    }

    // The claims are the model's text before PII redaction, and the audit chain cannot be edited.
    @Test
    void theAuditRecordCarriesClaimHashesNotClaimText() {
        workspaceMetadata(Map.of("grounding.enabled", true));
        var filter = new GroundingDetectionFilter(detector(false), new GroundingConfig(true, GuardrailAction.LOG, 50, 10000),
                audited::add, metrics, workspaces);

        filter.postDispatch(request, ChatResponse.builder().build(), ctx);

        assertThat(audited).singleElement().satisfies(e -> {
            assertThat(e.payload()).doesNotContainKey("ungrounded_claims").containsKey("ungrounded_claim_hashes");
            assertThat(e.payload().toString()).doesNotContain("alice@example.com").doesNotContain("Alice Smith");
        });
    }

    // Refused before the provider is called, not after the call has cost something.
    @Test
    void noDetectorRefusesBeforeDispatch() {
        workspaceMetadata(Map.of("grounding.enabled", true));
        var filter = new GroundingDetectionFilter(null, new GroundingConfig(true, GuardrailAction.LOG, 50, 10000),
                audited::add, metrics, workspaces);

        assertThatThrownBy(() -> filter.preDispatch(request, ctx))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getCode())
                .isEqualTo("GROUNDING_UNAVAILABLE");
    }

    @Test
    void noDetectorWithGroundingOffPassesBeforeDispatch() {
        workspaceMetadata(Map.of("grounding.enabled", false));
        var filter = new GroundingDetectionFilter(null, new GroundingConfig(false, GuardrailAction.LOG, 50, 10000),
                audited::add, metrics, workspaces);

        assertThat(filter.preDispatch(request, ctx)).isSameAs(request);
    }
}
