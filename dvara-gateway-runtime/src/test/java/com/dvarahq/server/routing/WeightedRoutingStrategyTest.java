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
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.WeightedRoutingStrategy;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WeightedRoutingStrategyTest {

    @Test
    void honoursWeightedSplits_70_30() {
        LlmProvider openai = provider("openai");
        LlmProvider anthropic = provider("anthropic");
        List<LlmProvider> providers = List.of(openai, anthropic);

        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(List.of(
                weight("openai", 70),
                weight("anthropic", 30)
        ));

        Map<String, Integer> counts = new HashMap<>();
        ChatRequest request = chatRequest("gpt-4o");

        int iterations = 10_000;
        for (int i = 0; i < iterations; i++) {
            LlmProvider selected = strategy.route(request, providers);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        double openaiPct = counts.getOrDefault("openai", 0) * 100.0 / iterations;
        double anthropicPct = counts.getOrDefault("anthropic", 0) * 100.0 / iterations;

        assertThat(openaiPct).isCloseTo(70.0, within(5.0));
        assertThat(anthropicPct).isCloseTo(30.0, within(5.0));
    }

    @Test
    void honoursWeightedSplits_50_50() {
        LlmProvider a = provider("a");
        LlmProvider b = provider("b");
        List<LlmProvider> providers = List.of(a, b);

        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(List.of(
                weight("a", 50),
                weight("b", 50)
        ));

        Map<String, Integer> counts = new HashMap<>();
        ChatRequest request = chatRequest("model");

        int iterations = 10_000;
        for (int i = 0; i < iterations; i++) {
            LlmProvider selected = strategy.route(request, providers);
            counts.merge(selected.name(), 1, Integer::sum);
        }

        double aPct = counts.getOrDefault("a", 0) * 100.0 / iterations;
        assertThat(aPct).isCloseTo(50.0, within(5.0));
    }

    @Test
    void singleProvider_weight100_alwaysSelected() {
        LlmProvider solo = provider("solo");
        List<LlmProvider> providers = List.of(solo);

        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(List.of(
                weight("solo", 100)
        ));

        ChatRequest request = chatRequest("model");

        for (int i = 0; i < 50; i++) {
            assertThat(strategy.route(request, providers).name()).isEqualTo("solo");
        }
    }

    @Test
    void totalWeight_isCorrect() {
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(List.of(
                weight("a", 70),
                weight("b", 30)
        ));

        assertThat(strategy.totalWeight()).isEqualTo(100);
    }

    @Test
    void weights_areStoredCorrectly() {
        List<RouteConfig.ProviderWeight> input = List.of(
                weight("a", 60),
                weight("b", 40)
        );

        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(input);

        assertThat(strategy.weights()).hasSize(2);
        assertThat(strategy.weights().get(0).getProvider()).isEqualTo("a");
        assertThat(strategy.weights().get(0).getWeight()).isEqualTo(60);
    }

    @Test
    void providerNotInAvailableList_throwsGatewayException() {
        LlmProvider other = provider("other");
        List<LlmProvider> providers = List.of(other);

        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(List.of(
                weight("missing", 100)
        ));

        ChatRequest request = chatRequest("model");

        assertThatThrownBy(() -> strategy.route(request, providers))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("NO_PROVIDER"));
    }

    @Test
    void emptyProviderList_throwsGatewayException() {
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(List.of(
                weight("openai", 100)
        ));

        ChatRequest request = chatRequest("gpt-4o");

        assertThatThrownBy(() -> strategy.route(request, List.of()))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("NO_PROVIDER"));
    }

    @Test
    void constructor_emptyWeights_throwsIllegalArgument() {
        assertThatThrownBy(() -> new WeightedRoutingStrategy(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_nullWeights_throwsIllegalArgument() {
        assertThatThrownBy(() -> new WeightedRoutingStrategy(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_zeroTotalWeight_throwsIllegalArgument() {
        assertThatThrownBy(() -> new WeightedRoutingStrategy(List.of(
                weight("a", 0),
                weight("b", 0)
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LlmProvider provider(String name) {
        LlmProvider p = mock(LlmProvider.class);
        when(p.name()).thenReturn(name);
        return p;
    }

    private static RouteConfig.ProviderWeight weight(String provider, int weight) {
        return RouteConfig.ProviderWeight.builder()
                .provider(provider)
                .weight(weight)
                .build();
    }

    private static ChatRequest chatRequest(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .build();
    }
}