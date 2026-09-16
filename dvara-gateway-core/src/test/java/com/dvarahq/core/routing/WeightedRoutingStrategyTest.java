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
package com.dvarahq.core.routing;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WeightedRoutingStrategyTest {

    @Test
    void distributesTrafficByWeight() {
        List<RouteConfig.ProviderWeight> weights = List.of(
                RouteConfig.ProviderWeight.builder().provider("a").weight(80).build(),
                RouteConfig.ProviderWeight.builder().provider("b").weight(20).build()
        );
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(weights);
        List<LlmProvider> providers = List.of(stubProvider("a"), stubProvider("b"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();

        for (int i = 0; i < 10_000; i++) {
            LlmProvider selected = strategy.route(request, providers);
            if ("a".equals(selected.name())) aCount.incrementAndGet();
            else bCount.incrementAndGet();
        }

        // 80/20 split — a should get roughly 8000
        assertThat(aCount.get()).isBetween(7000, 9000);
        assertThat(bCount.get()).isBetween(1000, 3000);
    }

    @Test
    void singleProvider_weight100_alwaysRouted() {
        List<RouteConfig.ProviderWeight> weights = List.of(
                RouteConfig.ProviderWeight.builder().provider("a").weight(100).build()
        );
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(weights);
        List<LlmProvider> providers = List.of(stubProvider("a"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        for (int i = 0; i < 100; i++) {
            assertThat(strategy.route(request, providers).name()).isEqualTo("a");
        }
    }

    @Test
    void totalWeight_sumsCorrectly() {
        List<RouteConfig.ProviderWeight> weights = List.of(
                RouteConfig.ProviderWeight.builder().provider("a").weight(30).build(),
                RouteConfig.ProviderWeight.builder().provider("b").weight(70).build()
        );
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(weights);

        assertThat(strategy.totalWeight()).isEqualTo(100);
    }

    @Test
    void weightsAccessor_returnsImmutableCopy() {
        List<RouteConfig.ProviderWeight> weights = List.of(
                RouteConfig.ProviderWeight.builder().provider("a").weight(50).build()
        );
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(weights);

        assertThat(strategy.weights()).hasSize(1);
        assertThatThrownBy(() -> strategy.weights().add(
                RouteConfig.ProviderWeight.builder().provider("b").weight(50).build()
        )).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void throwsGatewayException_whenSelectedProviderNotInList() {
        List<RouteConfig.ProviderWeight> weights = List.of(
                RouteConfig.ProviderWeight.builder().provider("a").weight(100).build()
        );
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(weights);
        List<LlmProvider> providers = List.of(stubProvider("x"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThatThrownBy(() -> strategy.route(request, providers))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("No available provider in weighted pool");
    }

    /**
     * A configured-but-unregistered member does not take its share of the traffic down with it.
     *
     * <p>The roll runs over the registered members' weights only, so it can never land in a band
     * nobody serves. Two hundred iterations is enough that a strategy rolling over the full total
     * could not pass by luck.
     */
    @Test
    void anUnregisteredMemberDoesNotFailItsShareOfTheTraffic() {
        List<RouteConfig.ProviderWeight> weights = List.of(
                RouteConfig.ProviderWeight.builder().provider("a").weight(50).build(),
                RouteConfig.ProviderWeight.builder().provider("b").weight(50).build()
        );
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(weights);
        List<LlmProvider> registered = List.of(stubProvider("a"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        for (int i = 0; i < 200; i++) {
            assertThat(strategy.route(request, registered).name()).isEqualTo("a");
        }
    }

    /** Weights still divide the traffic among the members that ARE registered. */
    @Test
    void weightsStillApplyAcrossTheRegisteredMembers() {
        List<RouteConfig.ProviderWeight> weights = List.of(
                RouteConfig.ProviderWeight.builder().provider("a").weight(90).build(),
                RouteConfig.ProviderWeight.builder().provider("b").weight(10).build(),
                RouteConfig.ProviderWeight.builder().provider("gone").weight(100).build()
        );
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(weights);
        List<LlmProvider> registered = List.of(stubProvider("a"), stubProvider("b"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        int aCount = 0;
        for (int i = 0; i < 2000; i++) {
            if (strategy.route(request, registered).name().equals("a")) {
                aCount++;
            }
        }

        // 90:10 over the two registered members, not 90:10:100 over all three.
        assertThat(aCount).isBetween(1650, 1950);
    }

    /** Every member registered but all of them at zero weight: there is no share to give. */
    @Test
    void zeroWeightAcrossTheRegisteredMembersIsNoProvider() {
        List<RouteConfig.ProviderWeight> weights = List.of(
                RouteConfig.ProviderWeight.builder().provider("a").weight(0).build(),
                RouteConfig.ProviderWeight.builder().provider("b").weight(100).build()
        );
        WeightedRoutingStrategy strategy = new WeightedRoutingStrategy(weights);
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThatThrownBy(() -> strategy.route(request, List.of(stubProvider("a"))))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("No available provider in weighted pool");
    }

    @Test
    void constructorRejectsNullList() {
        assertThatThrownBy(() -> new WeightedRoutingStrategy(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsEmptyList() {
        assertThatThrownBy(() -> new WeightedRoutingStrategy(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsZeroTotalWeight() {
        List<RouteConfig.ProviderWeight> weights = List.of(
                RouteConfig.ProviderWeight.builder().provider("a").weight(0).build()
        );

        assertThatThrownBy(() -> new WeightedRoutingStrategy(weights))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Total weight must be positive");
    }

    private static LlmProvider stubProvider(String name) {
        return new LlmProvider() {
            @Override public String name() { return name; }
            @Override public boolean supports(ChatRequest request) { return false; }
            @Override public ChatResponse chat(ChatRequest request) { return null; }
            @Override public Iterator<SseChunk> streamChat(ChatRequest request) { return null; }
            @Override public boolean supportsEmbedding(String model) { return false; }
            @Override public EmbeddingResponse embed(EmbeddingRequest request) { return null; }
            @Override public ProviderCapabilities capabilities() { return null; }
        };
    }
}