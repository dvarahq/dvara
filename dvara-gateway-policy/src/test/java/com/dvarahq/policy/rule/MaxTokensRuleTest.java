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
package com.dvarahq.policy.rule;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.policy.PolicyContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MaxTokensRuleTest {

    private final PolicyContext ctx = PolicyContext.empty();

    @Test
    void matches_exceedsLimit_returnsTrue() {
        var rule = new MaxTokensRule(4096);
        var request = ChatRequest.builder().model("gpt-4o").maxTokens(8000).build();
        assertThat(rule.matches(ctx, request)).isTrue();
    }

    @Test
    void doesNotMatch_withinLimit_returnsFalse() {
        var rule = new MaxTokensRule(4096);
        var request = ChatRequest.builder().model("gpt-4o").maxTokens(2048).build();
        assertThat(rule.matches(ctx, request)).isFalse();
    }

    @Test
    void doesNotMatch_exactlyAtLimit_returnsFalse() {
        var rule = new MaxTokensRule(4096);
        var request = ChatRequest.builder().model("gpt-4o").maxTokens(4096).build();
        assertThat(rule.matches(ctx, request)).isFalse();
    }

    @Test
    void nullMaxTokens_returnsFalse() {
        var rule = new MaxTokensRule(4096);
        var request = ChatRequest.builder().model("gpt-4o").maxTokens(null).build();
        assertThat(rule.matches(ctx, request)).isFalse();
    }
}