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
package com.dvarahq.providers.mock;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MatcherEvaluatorTest {

    private ChatRequest req(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .temperature(0.7)
                .stream(false)
                .build();
    }

    // --- compilation ---

    @Test
    void blankSource_rejectedAtCompile() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MatcherEvaluator(""))
                .withMessageContaining("must not be blank");
    }

    @Test
    void nullSource_rejectedAtCompile() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MatcherEvaluator(null))
                .withMessageContaining("must not be blank");
    }

    @Test
    void invalidGroovySyntax_rejectedAtCompile() {
        // Unbalanced brace — Groovy parser should reject
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new MatcherEvaluator("request.model == { unclosed"))
                .withMessageContaining("failed to compile");
    }

    // --- evaluation ---

    @Test
    void trueLiteral_matches() {
        MatcherEvaluator predicate = new MatcherEvaluator("true");
        assertThat(predicate.matches(req("mock/any"))).isTrue();
    }

    @Test
    void falseLiteral_doesNotMatch() {
        MatcherEvaluator predicate = new MatcherEvaluator("false");
        assertThat(predicate.matches(req("mock/any"))).isFalse();
    }

    @Test
    void modelEquality_matchesWhenRequestMatches() {
        MatcherEvaluator predicate = new MatcherEvaluator("request.model == 'mock/fast'");
        assertThat(predicate.matches(req("mock/fast"))).isTrue();
        assertThat(predicate.matches(req("mock/slow"))).isFalse();
    }

    @Test
    void temperatureThreshold_matchesWhenAboveLimit() {
        MatcherEvaluator predicate = new MatcherEvaluator("request.temperature != null && request.temperature > 0.5");
        assertThat(predicate.matches(req("mock/x"))).isTrue();
    }

    @Test
    void modelStartsWithCheck_worksForPrefixMatching() {
        MatcherEvaluator predicate = new MatcherEvaluator("request.model.startsWith('mock/')");
        assertThat(predicate.matches(req("mock/anything"))).isTrue();
    }

    // --- return-value handling ---

    @Test
    void nullReturnValue_treatedAsNonMatch() {
        MatcherEvaluator predicate = new MatcherEvaluator("null");
        assertThat(predicate.matches(req("mock/any"))).isFalse();
    }

    @Test
    void nonBooleanReturnValue_treatedAsNonMatch() {
        // Groovy script returns a string, not a boolean — must not be coerced to "truthy"
        MatcherEvaluator predicate = new MatcherEvaluator("'yes'");
        assertThat(predicate.matches(req("mock/any"))).isFalse();
    }

    @Test
    void intReturnValue_treatedAsNonMatch() {
        // Similarly, a non-zero int should not be coerced to true — strict Boolean.TRUE equality
        MatcherEvaluator predicate = new MatcherEvaluator("42");
        assertThat(predicate.matches(req("mock/any"))).isFalse();
    }

    // --- exception propagation ---

    @Test
    void runtimeException_wrappedAsProviderError() {
        MatcherEvaluator predicate = new MatcherEvaluator("throw new RuntimeException('boom')");
        assertThatThrownBy(() -> predicate.matches(req("mock/any")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Mock matcher predicate evaluation failed")
                .hasMessageContaining("boom");
    }

    @Test
    void threadSafety_compiledOnceEvaluatedManyTimes() {
        // Same compiled predicate can be evaluated repeatedly with different requests.
        // Tests that we don't leak binding state between calls.
        MatcherEvaluator predicate = new MatcherEvaluator("request.model == 'mock/target'");
        for (int i = 0; i < 100; i++) {
            assertThat(predicate.matches(req("mock/other"))).isFalse();
            assertThat(predicate.matches(req("mock/target"))).isTrue();
        }
    }
}