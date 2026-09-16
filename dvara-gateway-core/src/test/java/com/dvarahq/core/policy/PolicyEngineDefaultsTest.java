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
package com.dvarahq.core.policy;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an engine gets for free, and what it must not get for free.
 *
 * <p>These defaults are reachable only by an engine that does not override them — which neither shipped
 * engine is, since {@code DefaultPolicyEngine} overrides and {@code ShadowAwarePolicyEngine} delegates
 * to it. So the only way to pin them is to declare the minimum engine here and ask it directly. Without
 * that, a behaviour change to a default is a change nothing can see.
 */
class PolicyEngineDefaultsTest {

    /** The least an engine can implement: one method, everything else inherited. */
    private static final class MinimalEngine implements PolicyEngine {
        int evaluateCalls = 0;

        @Override
        public PolicyDecision evaluate(PolicyContext context, ChatRequest request) {
            evaluateCalls++;
            return PolicyDecision.deny("the ACTIVE policy set denied this", "active-policy", "rule-1");
        }
    }

    private static ChatRequest request() {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("hello")))
                .build();
    }

    @Test
    void evaluateDslRefusesRatherThanAnsweringAboutTheActivePolicies() {
        // A default that delegated to evaluate() would discard the candidate DSL and report on
        // whatever is currently ACTIVE. A dry-run surface would then label that answer as what the
        // candidate would do: a draft that denies coming back ALLOWED, or a draft that allows coming
        // back denied by a policy nobody asked about.
        MinimalEngine engine = new MinimalEngine();

        assertThatThrownBy(() -> engine.evaluateDsl("rules: []", PolicyContext.empty(), request()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("cannot evaluate a candidate DSL")
                .satisfies(e -> assertThat(((GatewayException) e).getCode())
                        .isEqualTo("UNSUPPORTED_CAPABILITY"));

        assertThat(engine.evaluateCalls)
                .as("it must not fall through to the active policy set")
                .isZero();
    }

    @Test
    void validateDslIsDeliberatelyANoOpAndSaysSo() {
        // Unlike evaluateDsl, this one has nothing to get wrong: an engine holding no DSL to compile
        // validates nothing, and answering "valid" is not a claim about a candidate's behaviour.
        MinimalEngine engine = new MinimalEngine();

        engine.validateDsl("anything at all");   // does not throw
        assertThat(engine.evaluateCalls).isZero();
    }

    @Test
    void aStatelessEngineReportsNoCompilationFailuresAndNothingToReload() {
        MinimalEngine engine = new MinimalEngine();

        assertThat(engine.policyCompilationFailureCount())
                .as("zero means 'nothing is switched off', which is true for an engine with no index")
                .isZero();
        engine.reload();   // does not throw
    }

    @Test
    void evaluateIsTheOneMethodAnEngineMustSupply() {
        MinimalEngine engine = new MinimalEngine();

        PolicyDecision decision = engine.evaluate(PolicyContext.empty(), request());

        assertThat(decision.allowed()).isFalse();
        assertThat(engine.evaluateCalls).isEqualTo(1);
    }
}
