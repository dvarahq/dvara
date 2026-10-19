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

import com.dvarahq.core.enforcement.StreamingEnforcementTelemetry;
import com.dvarahq.core.guardrail.GroundingConfig;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GroundingResult;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiScanResult;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.pii.PiiProperties;
import com.dvarahq.policy.guardrail.GuardrailProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A streamed reply that grounding checks is counted once, with its verdict and action, like a non-streamed one. */
class StreamingGroundingCheckedTest {

    private static final String CLAIM = "The capital of the country is a large and busy city.";

    private final List<String> checked = new ArrayList<>();
    private final StreamingEnforcementTelemetry telemetry = new StreamingEnforcementTelemetry() {
        @Override
        public void groundingChecked(String plane, String workspaceId, boolean grounded, String action) {
            checked.add(plane + "/" + workspaceId + "/" + grounded + "/" + action);
        }
    };

    private StreamingResponseEnforcerImpl enforcer(boolean grounded, GuardrailAction action) {
        PiiDetector pii = mock(PiiDetector.class);
        GuardrailDetector guardrail = mock(GuardrailDetector.class);
        when(pii.scanDocument(any(), any())).thenReturn(List.of());
        when(guardrail.scanDocument(any(), any())).thenReturn(List.of());
        when(pii.scan(any(String.class), any())).thenReturn(PiiScanResult.EMPTY);
        when(guardrail.scan(any(String.class), any())).thenReturn(GuardrailScanResult.EMPTY);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById(any())).thenReturn(Optional.empty());
        GroundingDetector detector = (req, resp, sources) -> grounded
                ? GroundingResult.GROUNDED : new GroundingResult(false, 0.1, List.of(CLAIM), 0.1);
        return new StreamingResponseEnforcerImpl(pii, guardrail, e -> { }, workspaces, new PiiProperties(),
                new GuardrailProperties(), detector, new GroundingConfig(true, action, 50, 10_000), null, null,
                telemetry);
    }

    private static ChatRequest withSources() {
        return ChatRequest.builder().model("m").metadata(Map.of("grounding.sources", List.of("a source"))).build();
    }

    private static Iterator<SseChunk> text(String... parts) {
        List<SseChunk> chunks = new ArrayList<>();
        for (String p : parts) {
            chunks.add(SseChunk.builder().id("c").model("m").delta(p).done(false).build());
        }
        chunks.add(SseChunk.builder().id("c").model("m").finishReason("stop").done(true).build());
        return chunks.iterator();
    }

    private static void drain(Iterator<SseChunk> it) {
        int safety = 0;
        while (it.hasNext() && safety++ < 1000) {
            it.next();
        }
    }

    @Test
    void aGroundedStreamIsCountedOnceAsGrounded() {
        drain(enforcer(true, GuardrailAction.LOG).wrap(text("The capital ", "of the country is a large and busy city."),
                "acme", withSources()));
        assertThat(checked).containsExactly("LLM/acme/true/LOG");
    }

    @Test
    void anUngroundedStreamIsCountedAsUngroundedUnderLog() {
        drain(enforcer(false, GuardrailAction.LOG).wrap(text(CLAIM), "acme", withSources()));
        assertThat(checked).containsExactly("LLM/acme/false/LOG");
    }

    @Test
    void anUngroundedStreamRefusedUnderBlockIsStillCounted() {
        drain(enforcer(false, GuardrailAction.BLOCK).wrap(text(CLAIM), "acme", withSources()));
        assertThat(checked).containsExactly("LLM/acme/false/BLOCK");
    }

    @Test
    void aStreamWithNoSourcesIsNotCountedAsChecked() {
        drain(enforcer(true, GuardrailAction.LOG).wrap(text(CLAIM), "acme", ChatRequest.builder().model("m").build()));
        assertThat(checked).isEmpty();
    }

    @Test
    void aBareToolCallMakesNoClaimAndIsNotCounted() {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c").model("m")
                        .toolCalls(List.of(new ToolCallDelta(0, "call_1", "lookup", "{\"id\":4471}"))).done(false).build(),
                SseChunk.builder().id("c").model("m").finishReason("tool_calls").done(true).build());
        drain(enforcer(true, GuardrailAction.LOG).wrap(chunks.iterator(), "acme", withSources()));
        assertThat(checked).isEmpty();
    }
}
