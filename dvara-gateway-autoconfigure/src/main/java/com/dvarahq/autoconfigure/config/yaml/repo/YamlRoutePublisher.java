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
        if (!routes.isEmpty()) {
            routingEngine.updateRoutes(routes.stream().map(this::resolve).toList());
            log.info("Serving {} route(s) and {} API key(s) from gateway.yaml",
                    routes.size(), store.keys().size());
        } else {
            log.warn("gateway.yaml declares no routes. Every request will fall through to provider "
                    + "selection by model prefix; add a 'routes:' block to govern it.");
        }
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
                .build();
        return new RoutingEngine.ResolvedRoute(config, strategyFactory.create(config));
    }

}