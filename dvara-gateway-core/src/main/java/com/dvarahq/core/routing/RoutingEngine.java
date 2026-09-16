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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.RoutingStrategy;

import java.util.List;

public class RoutingEngine implements RoutingStrategy {

    private final RoutingStrategy defaultStrategy;

    /**
     * The live route table, replaced whole and never edited in place.
     *
     * <p>A list emptied and refilled by {@link #updateRoutes} would have two visible states, and a
     * request arriving between them would see no routes at all and fall through to the default
     * strategy. One volatile reference to an immutable list has no intermediate state to observe.
     */
    private volatile List<ResolvedRoute> routes;

    public RoutingEngine(RoutingStrategy defaultStrategy, List<ResolvedRoute> routes) {
        this.defaultStrategy = defaultStrategy;
        this.routes = List.copyOf(routes);
    }

    @Override
    public LlmProvider route(ChatRequest request, List<LlmProvider> providers) {
        return route(request, providers, RequestContext.builder().build());
    }

    @Override
    public LlmProvider route(ChatRequest request, List<LlmProvider> providers, RequestContext ctx) {
        String model = request.getModel();
        if (model == null) {
            return defaultStrategy.route(request, providers, ctx);
        }

        for (ResolvedRoute route : routes) {
            if (matchesPattern(model, route.config().getModelPattern())) {
                ctx.setMatchedRoute(route.config());
                ChatRequest effective = applyPinning(request, route.config());
                return route.strategy().route(effective, providers, ctx);
            }
        }

        ctx.setMatchedRoute(null);
        return defaultStrategy.route(request, providers, ctx);
    }

    public void updateRoutes(List<ResolvedRoute> newRoutes) {
        this.routes = List.copyOf(newRoutes);
    }

    public List<ResolvedRoute> currentRoutes() {
        return routes;
    }

    private boolean matchesPattern(String model, String pattern) {
        if (pattern == null) return false;
        if (pattern.endsWith("*")) {
            return model.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return model.equals(pattern);
    }

    private ChatRequest applyPinning(ChatRequest request, RouteConfig config) {
        if (config.getPinnedModelVersion() != null) {
            // toBuilder(), never a hand-rolled copy, so every field of the request survives.
            return request.toBuilder().model(config.getPinnedModelVersion()).build();
        }
        return request;
    }

    public record ResolvedRoute(RouteConfig config, RoutingStrategy strategy) {}
}