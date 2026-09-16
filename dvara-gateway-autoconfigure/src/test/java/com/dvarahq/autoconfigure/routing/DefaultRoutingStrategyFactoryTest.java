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
package com.dvarahq.autoconfigure.routing;

import com.dvarahq.core.routing.CanaryConfig;
import com.dvarahq.core.routing.CanaryRoutingStrategy;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoundRobinRoutingStrategy;
import com.dvarahq.core.routing.WeightedRoutingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRoutingStrategyFactoryTest {

    private final DefaultRoutingStrategyFactory factory = new DefaultRoutingStrategyFactory();

    @Test
    void modelPrefix_createsModelPrefixStrategy() {
        RouteConfig config = RouteConfig.builder()
                .strategy(RouteConfig.Strategy.MODEL_PREFIX)
                .build();

        assertThat(factory.create(config)).isInstanceOf(ModelPrefixRoutingStrategy.class);
    }

    @Test
    void roundRobin_createsRoundRobinStrategy() {
        RouteConfig config = RouteConfig.builder()
                .strategy(RouteConfig.Strategy.ROUND_ROBIN)
                .providers(List.of(
                        RouteConfig.ProviderWeight.builder().provider("openai").weight(1).build(),
                        RouteConfig.ProviderWeight.builder().provider("anthropic").weight(1).build()))
                .build();

        assertThat(factory.create(config)).isInstanceOf(RoundRobinRoutingStrategy.class);
    }

    @Test
    void weighted_createsWeightedStrategy() {
        RouteConfig config = RouteConfig.builder()
                .strategy(RouteConfig.Strategy.WEIGHTED)
                .providers(List.of(
                        RouteConfig.ProviderWeight.builder().provider("openai").weight(80).build(),
                        RouteConfig.ProviderWeight.builder().provider("anthropic").weight(20).build()))
                .build();

        assertThat(factory.create(config)).isInstanceOf(WeightedRoutingStrategy.class);
    }

    @Test
    void canary_createsCanaryStrategy() {
        RouteConfig config = RouteConfig.builder()
                .strategy(RouteConfig.Strategy.CANARY)
                .canaryConfig(CanaryConfig.builder()
                        .baselineProvider("openai")
                        .candidateProvider("anthropic")
                        .splitPct(20)
                        .build())
                .build();

        assertThat(factory.create(config)).isInstanceOf(CanaryRoutingStrategy.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = RouteConfig.Strategy.class,
            names = {"LATENCY_AWARE", "COST_AWARE", "GEO_AWARE", "INTELLIGENT"})
    void aStrategyThisBuildCannotRoute_isRefusedByName(RouteConfig.Strategy strategy) {
        // Falling back to round-robin silently would leave an operator who configured
        // latency-aware routing with round-robin and no way to learn it.
        RouteConfig config = RouteConfig.builder()
                .id("r1")
                .strategy(strategy)
                .providers(List.of(
                        RouteConfig.ProviderWeight.builder().provider("openai").weight(1).build()))
                .build();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> factory.create(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(strategy.name())
                .hasMessageContaining("model-prefix, round-robin, weighted, canary");
    }
}
