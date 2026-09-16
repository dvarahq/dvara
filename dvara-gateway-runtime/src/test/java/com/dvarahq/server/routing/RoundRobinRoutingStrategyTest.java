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
package com.dvarahq.server.routing;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.routing.RoundRobinRoutingStrategy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoundRobinRoutingStrategyTest {

    @Test
    void distributesEvenly_acrossProviders() {
        LlmProvider openai = provider("openai");
        LlmProvider anthropic = provider("anthropic");
        List<LlmProvider> providers = List.of(openai, anthropic);

        RoundRobinRoutingStrategy strategy =
                new RoundRobinRoutingStrategy(List.of("openai", "anthropic"));

        Map<String, Integer> counts = new HashMap<>();
        ChatRequest request = chatRequest("gpt-4o");

        for (int i = 0; i < 100; i++) {
            LlmProvider selected = strategy.route(request, providers);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        assertThat(counts.get("openai")).isEqualTo(50);
        assertThat(counts.get("anthropic")).isEqualTo(50);
    }

    @Test
    void distributesEvenly_threeProviders() {
        LlmProvider a = provider("a");
        LlmProvider b = provider("b");
        LlmProvider c = provider("c");
        List<LlmProvider> providers = List.of(a, b, c);

        RoundRobinRoutingStrategy strategy =
                new RoundRobinRoutingStrategy(List.of("a", "b", "c"));

        Map<String, Integer> counts = new HashMap<>();
        ChatRequest request = chatRequest("model");

        for (int i = 0; i < 99; i++) {
            LlmProvider selected = strategy.route(request, providers);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        assertThat(counts.get("a")).isEqualTo(33);
        assertThat(counts.get("b")).isEqualTo(33);
        assertThat(counts.get("c")).isEqualTo(33);
    }

    @Test
    void roundRobin_cyclesInOrder() {
        LlmProvider openai = provider("openai");
        LlmProvider anthropic = provider("anthropic");
        List<LlmProvider> providers = List.of(openai, anthropic);

        RoundRobinRoutingStrategy strategy =
                new RoundRobinRoutingStrategy(List.of("openai", "anthropic"));

        ChatRequest request = chatRequest("gpt-4o");

        assertThat(strategy.route(request, providers).name()).isEqualTo("openai");
        assertThat(strategy.route(request, providers).name()).isEqualTo("anthropic");
        assertThat(strategy.route(request, providers).name()).isEqualTo("openai");
        assertThat(strategy.route(request, providers).name()).isEqualTo("anthropic");
    }

    @Test
    void skipsUnavailableProvider_andAdvancesToNext() {
        LlmProvider anthropic = provider("anthropic");
        // openai is in the round-robin pool but not in the available providers list
        List<LlmProvider> providers = List.of(anthropic);

        RoundRobinRoutingStrategy strategy =
                new RoundRobinRoutingStrategy(List.of("openai", "anthropic"));

        ChatRequest request = chatRequest("model");

        // Both slots should resolve to anthropic since openai isn't available
        assertThat(strategy.route(request, providers).name()).isEqualTo("anthropic");
        assertThat(strategy.route(request, providers).name()).isEqualTo("anthropic");
    }

    @Test
    void allProvidersUnavailable_throwsGatewayException() {
        RoundRobinRoutingStrategy strategy =
                new RoundRobinRoutingStrategy(List.of("openai", "anthropic"));

        ChatRequest request = chatRequest("gpt-4o");

        assertThatThrownBy(() -> strategy.route(request, List.of()))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("NO_PROVIDER"));
    }

    @Test
    void constructor_emptyList_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RoundRobinRoutingStrategy(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nullList_throwsIllegalArgument() {
        assertThatThrownBy(() -> new RoundRobinRoutingStrategy(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LlmProvider provider(String name) {
        LlmProvider p = mock(LlmProvider.class);
        when(p.name()).thenReturn(name);
        return p;
    }

    private static ChatRequest chatRequest(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .build();
    }
}