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
package com.dvarahq.server.v1;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.server.v1.dto.CompletionRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The legacy completions doorway carries stop and seed, and refuses log probabilities. */
class LegacyCompletionFieldsTest {

    @Test
    void stopAndSeedTravelUpstream() {
        CompletionRequest request = CompletionRequest.builder()
                .model("gpt-4o").prompt("Hi").stop(List.of("END")).seed(7L).build();

        LegacyCompletionController.rejectUnsupported(request);
        ChatRequest internal = LegacyCompletionController.toInternal(request);

        assertThat(internal.getStop()).containsExactly("END");
        assertThat(internal.getSeed()).isEqualTo(7L);
    }

    @Test
    void logprobsAreRefusedNotDropped() {
        CompletionRequest request = CompletionRequest.builder().model("gpt-4o").prompt("Hi").logprobs(3).build();

        assertThatThrownBy(() -> LegacyCompletionController.rejectUnsupported(request))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"));
    }
}
