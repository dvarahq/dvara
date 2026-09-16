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

import com.dvarahq.core.provider.RoutingStrategy;
import com.dvarahq.core.routing.CanaryRoutingStrategy;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import com.dvarahq.core.routing.RoundRobinRoutingStrategy;
import com.dvarahq.core.routing.WeightedRoutingStrategy;

import java.util.List;
import java.util.stream.Collectors;

public class DefaultRoutingStrategyFactory implements RoutingStrategyFactory {

    @Override
    public RoutingStrategy create(RouteConfig config) {
        return switch (config.getStrategy()) {
            case ROUND_ROBIN -> {
                List<String> names = config.getProviders().stream()
                        .map(RouteConfig.ProviderWeight::getProvider)
                        .collect(Collectors.toList());
                yield new RoundRobinRoutingStrategy(names);
            }
            case WEIGHTED -> new WeightedRoutingStrategy(config.getProviders());
            // These four need a latency tracker, a cost model, a residency policy or a complexity
            // classifier that this build does not carry. Refusing names the strategies that work,
            // rather than silently routing some other way.
            case LATENCY_AWARE, COST_AWARE, GEO_AWARE, INTELLIGENT -> throw new IllegalStateException(
                    "route '" + config.getId() + "': strategy " + config.getStrategy()
                            + " is not part of this build. Supported: model-prefix, round-robin, weighted, canary.");
            case MODEL_PREFIX -> new ModelPrefixRoutingStrategy();
            case CANARY -> new CanaryRoutingStrategy(config.getCanaryConfig());
        };
    }
}