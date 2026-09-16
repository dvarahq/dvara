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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelDenylistRuleTest {

    private final PolicyContext ctx = PolicyContext.empty();

    @Test
    void matches_modelInDenylist_returnsTrue() {
        var rule = new ModelDenylistRule(Set.of("gpt-3.5-turbo"));
        var request = ChatRequest.builder().model("gpt-3.5-turbo").build();
        assertThat(rule.matches(ctx, request)).isTrue();
    }

    @Test
    void doesNotMatch_modelNotInDenylist_returnsFalse() {
        var rule = new ModelDenylistRule(Set.of("gpt-3.5-turbo"));
        var request = ChatRequest.builder().model("gpt-4o").build();
        assertThat(rule.matches(ctx, request)).isFalse();
    }

    @Test
    void nullModel_returnsFalse() {
        var rule = new ModelDenylistRule(Set.of("gpt-3.5-turbo"));
        var request = ChatRequest.builder().model(null).build();
        assertThat(rule.matches(ctx, request)).isFalse();
    }
}