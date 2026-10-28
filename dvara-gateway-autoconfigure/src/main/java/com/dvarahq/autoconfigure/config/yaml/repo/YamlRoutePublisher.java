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
package com.dvarahq.autoconfigure.config.yaml.repo;

import com.dvarahq.core.routing.Route;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.util.ArrayList;
import java.util.List;

/**
 * Pushes the file's routes into {@link RoutingEngine}.
 *
 * <p>{@code RoutingEngine} holds compiled routes with their strategy instances; it is not a view
 * over {@code RouteRepository}. Something has to hand it the routes, and on a file-configured build
 * nothing else does. Without this, a gateway would answer "which routes exist" correctly from the
 * file and route nothing.
 *
 * <p>It runs at {@code HIGHEST_PRECEDENCE}, before the standalone banner, so the routes are live
 * before the first request the banner invites.
 *
 * <h2>Routes from the startup properties are kept</h2>
 *
 * <p>The engine starts with the routes from the {@code dvara.llm-gateway.routes} startup
 * properties. The file's routes are added after them; they do not replace them. Canary routes can
 * only be set in the startup properties, so replacing them would drop a canary without a word
 * whenever the file has a {@code routes:} block. The rules:
 * <ul>
 *   <li>Startup-property routes come first, so where both match a model, the property route wins.
 *       A file route that a property route always matches first is never used, and a WARN line
 *       names it.</li>
 *   <li>The same route id in both places is refused at startup, naming the id and both places.
 *       Two routes with one id would make "which route served this" ambiguous.</li>
 * </ul>
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class YamlRoutePublisher implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(YamlRoutePublisher.class);

    private final YamlConfigStore store;
    private final RoutingEngine routingEngine;
    private final RoutingStrategyFactory strategyFactory;

    public YamlRoutePublisher(YamlConfigStore store,
                              RoutingEngine routingEngine,
                              RoutingStrategyFactory strategyFactory) {
        this.store = store;
        this.routingEngine = routingEngine;
        this.strategyFactory = strategyFactory;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Route> routes = store.routes();
        List<RoutingEngine.ResolvedRoute> propertyRoutes = routingEngine.currentRoutes();
        if (routes.isEmpty()) {
            if (propertyRoutes.isEmpty()) {
                log.warn("gateway.yaml declares no routes. Every request will fall through to provider "
                        + "selection by model prefix; add a 'routes:' block to govern it.");
            } else {
                log.info("gateway.yaml declares no routes. Serving {} route(s) from the "
                        + "dvara.llm-gateway.routes startup properties.", propertyRoutes.size());
            }
            return;
        }

        List<String> sharedIds = routes.stream()
                .map(Route::getId)
                .filter(id -> propertyRoutes.stream().anyMatch(p -> id.equals(p.config().getId())))
                .toList();
        if (!sharedIds.isEmpty()) {
            throw new IllegalStateException("Route id(s) " + sharedIds + " are declared both in gateway.yaml "
                    + "and in the dvara.llm-gateway.routes startup properties. Each route must be declared "
                    + "in one place: rename or remove one of them.");
        }

        List<RoutingEngine.ResolvedRoute> merged = new ArrayList<>(propertyRoutes);
        for (Route route : routes) {
            propertyRoutes.stream()
                    .filter(p -> alwaysMatchesFirst(p.config().getModelPattern(), route.getModelPattern()))
                    .findFirst()
                    .ifPresent(p -> log.warn("gateway.yaml route '{}' (model '{}') will never be used: the "
                                    + "startup-property route '{}' (model '{}') matches every model it does and "
                                    + "is checked first. Remove one of them.",
                            route.getId(), route.getModelPattern(),
                            p.config().getId(), p.config().getModelPattern()));
            merged.add(resolve(route));
        }
        routingEngine.updateRoutes(merged);
        if (propertyRoutes.isEmpty()) {
            log.info("Serving {} route(s) and {} API key(s) from gateway.yaml",
                    routes.size(), store.keys().size());
        } else {
            log.info("Serving {} route(s) from the dvara.llm-gateway.routes startup properties, then {} "
                            + "route(s) and {} API key(s) from gateway.yaml",
                    propertyRoutes.size(), routes.size(), store.keys().size());
        }
    }

    /**
     * Whether a route with pattern {@code first} matches every model that {@code later} matches,
     * so a route with {@code later}, checked after it, can never be chosen. Patterns are an exact
     * model or a prefix ending in {@code *}, as {@link RoutingEngine} reads them.
     */
    static boolean alwaysMatchesFirst(String first, String later) {
        if (first == null || later == null) {
            return false;
        }
        if (first.endsWith("*")) {
            String prefix = first.substring(0, first.length() - 1);
            String laterStem = later.endsWith("*") ? later.substring(0, later.length() - 1) : later;
            return laterStem.startsWith(prefix);
        }
        return first.equals(later);
    }

    private RoutingEngine.ResolvedRoute resolve(Route route) {
        RouteConfig config = RouteConfig.builder()
                .id(route.getId())
                .modelPattern(route.getModelPattern())
                .strategy(YamlRouteRepository.toStrategy(route.getStrategy()))
                .providers(route.getProviders().stream()
                        .map(p -> RouteConfig.ProviderWeight.builder()
                                .provider(p.getProvider())
                                .weight(p.getWeight())
                                .build())
                        .toList())
                .pinnedModelVersion(route.getPinnedModelVersion())
                .costTolerancePct(route.getCostTolerancePct() != null ? route.getCostTolerancePct() : 0)
                .modelTiers(route.getModelTiers())
                .fallbacks(route.getFallbacks() == null ? List.of() : route.getFallbacks())
                .build();
        // #7 AC-CPF-03: a bad chain refuses the table, so no partial chain becomes active.
        if (config.invalidFallbacks() != null) {
            throw new IllegalStateException(config.invalidFallbacks());
        }
        if (config.invalidPin() != null) {
            throw new IllegalStateException(config.invalidPin());
        }
        return new RoutingEngine.ResolvedRoute(config, strategyFactory.create(config));
    }

}