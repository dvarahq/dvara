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
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.pii.PiiProperties;
import com.dvarahq.policy.guardrail.GuardrailProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A stream that grounding is on for but cannot check is reported, like a non-streamed response. */
class StreamingGroundingUncheckedTest {

    private final List<String> reported = new ArrayList<>();
    private final StreamingEnforcementTelemetry telemetry = new StreamingEnforcementTelemetry() {
        @Override
        public void groundingUnchecked(String plane, String workspaceId, String reason) {
            reported.add(plane + "/" + workspaceId + "/" + reason);
        }
    };

    private StreamingResponseEnforcerImpl enforcer(boolean groundingEnabled, int maxSourceLength) {
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById(any())).thenReturn(Optional.empty());
        GroundingDetector detector = (req, resp, sources) -> GroundingResult.GROUNDED;
        return new StreamingResponseEnforcerImpl(mock(PiiDetector.class), mock(GuardrailDetector.class), e -> { },
                workspaces, new PiiProperties(), new GuardrailProperties(), detector,
                new GroundingConfig(groundingEnabled, GuardrailAction.LOG, 50, maxSourceLength), null, null, telemetry);
    }

    private static Iterator<SseChunk> upstream() {
        return Collections.emptyIterator();
    }

    private static ChatRequest request(Map<String, Object> metadata) {
        return ChatRequest.builder().model("m").metadata(metadata).build();
    }

    @Test
    void aStreamWithNoSourcesIsReported() {
        enforcer(true, 10_000).wrap(upstream(), "acme", request(null));
        assertThat(reported).containsExactly("LLM/acme/no_sources");
    }

    @Test
    void aStreamWhoseSourcesAreAllOverTheLimitIsReportedAsSuch() {
        enforcer(true, 5).wrap(upstream(), "acme", request(Map.of("grounding.sources", List.of("far too long a source"))));
        assertThat(reported).containsExactly("LLM/acme/sources_over_limits");
    }

    @Test
    void aStreamWithSourcesIsNotReported() {
        enforcer(true, 10_000).wrap(upstream(), "acme", request(Map.of("grounding.sources", List.of("the source"))));
        assertThat(reported).isEmpty();
    }

    @Test
    void groundingOffReportsNothing() {
        enforcer(false, 10_000).wrap(upstream(), "acme", request(null));
        assertThat(reported).isEmpty();
    }
}
