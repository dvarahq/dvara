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

/**
 * Serves a model-prefix route that names its providers. Picks the first listed provider that is
 * registered, in the order listed, whatever model names that provider claims. This is how a route
 * reaches models no provider claims by prefix, such as {@code ai/*} on an OpenAI-compatible server.
 *
 * <p>A model-prefix route that names no provider keeps {@link ModelPrefixRoutingStrategy}.
 */
public class ListedProviderRoutingStrategy implements RoutingStrategy {

    private final String routeId;
    private final List<String> providerNames;

    public ListedProviderRoutingStrategy(String routeId, List<String> providerNames) {
        if (providerNames == null || providerNames.isEmpty()) {
            throw new IllegalArgumentException("A listed-provider route needs at least one provider");
        }
        this.routeId = routeId;
        this.providerNames = List.copyOf(providerNames);
    }

    @Override
    public LlmProvider route(ChatRequest request, List<LlmProvider> providers) {
        for (String name : providerNames) {
            for (LlmProvider p : providers) {
                if (p.name().equals(name)) {
                    return p;
                }
            }
        }
        throw new GatewayException("NO_PROVIDER",
                "No provider configured for model: " + request.getModel() + ". Route '" + routeId
                        + "' sends it to " + providerNames + ", and none of them is registered.");
    }

    public List<String> providerNames() {
        return providerNames;
    }
}
