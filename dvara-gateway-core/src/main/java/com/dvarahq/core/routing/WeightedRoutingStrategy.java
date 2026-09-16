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
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.RoutingStrategy;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public class WeightedRoutingStrategy implements RoutingStrategy {

    private final List<RouteConfig.ProviderWeight> weights;
    private final int totalWeight;

    public WeightedRoutingStrategy(List<RouteConfig.ProviderWeight> weights) {
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("Weighted routing requires at least one provider weight");
        }
        this.weights = List.copyOf(weights);
        this.totalWeight = weights.stream().mapToInt(RouteConfig.ProviderWeight::getWeight).sum();
        if (this.totalWeight <= 0) {
            throw new IllegalArgumentException("Total weight must be positive");
        }
    }

    /**
     * Picks a provider in proportion to the weights, <b>over the members that are registered</b>.
     *
     * <p>The roll is against the weight of the registered members only, so a member that is
     * configured but not registered (an API key set in one environment and not another) does not
     * take its share of the traffic down with it. Round-robin and canary do the same in their own
     * terms. Weights describe how to divide traffic, not who is allowed to be missing.
     *
     * <p>{@code NO_PROVIDER} is thrown when <em>no</em> member is registered, or when the ones that
     * are all carry zero weight.
     */
    @Override
    public LlmProvider route(ChatRequest request, List<LlmProvider> providers) {
        List<RouteConfig.ProviderWeight> available = weights.stream()
                .filter(pw -> findProvider(pw.getProvider(), providers) != null)
                .toList();
        int availableWeight = available.stream().mapToInt(RouteConfig.ProviderWeight::getWeight).sum();

        if (availableWeight > 0) {
            int random = ThreadLocalRandom.current().nextInt(availableWeight);
            int cumulative = 0;
            for (RouteConfig.ProviderWeight pw : available) {
                cumulative += pw.getWeight();
                if (random < cumulative) {
                    return findProvider(pw.getProvider(), providers);
                }
            }
        }

        throw new GatewayException("NO_PROVIDER",
                "No available provider in weighted pool for model: " + request.getModel());
    }

    public int totalWeight() {
        return totalWeight;
    }

    public List<RouteConfig.ProviderWeight> weights() {
        return weights;
    }

    private LlmProvider findProvider(String name, List<LlmProvider> providers) {
        for (LlmProvider p : providers) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }
}