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
import com.dvarahq.core.routing.RouteRepository;
import com.dvarahq.core.routing.RouteVersion;

import java.util.List;
import java.util.Optional;

/**
 * Routes, served from {@code gateway.yaml}.
 *
 * <p>Version history is refused rather than faked as an empty list: a file has no history, and
 * answering "no versions" to a rollback surface is a different and more dangerous claim than
 * answering "not here".
 */
public class YamlRouteRepository implements RouteRepository {

    private final YamlConfigStore store;

    public YamlRouteRepository(YamlConfigStore store) {
        this.store = store;
    }

    /**
     * The names {@code gateway.yaml} accepts for a routing strategy.
     *
     * <p>Must stay in step with {@code GatewayYamlLoader.isValidStrategy}, which runs first and
     * fails the boot on anything it does not recognise; a strategy understood here but rejected
     * there cannot be configured at all.
     */
    public static RouteConfig.Strategy toStrategy(String strategy) {
        return switch (strategy == null ? "" : strategy.toLowerCase()) {
            case "round-robin" -> RouteConfig.Strategy.ROUND_ROBIN;
            case "weighted" -> RouteConfig.Strategy.WEIGHTED;
            case "canary" -> RouteConfig.Strategy.CANARY;
            default -> RouteConfig.Strategy.MODEL_PREFIX;
        };
    }

    @Override
    public Optional<Route> findById(String id) {
        return Optional.ofNullable(store.routesById().get(id));
    }

    @Override
    public List<Route> findAll() {
        return store.routes();
    }

    @Override
    public Route save(Route route) {
        throw YamlReadOnly.on("RouteRepository.save");
    }

    @Override
    public boolean deleteById(String id) {
        throw YamlReadOnly.on("RouteRepository.deleteById");
    }

    @Override
    public List<RouteVersion> getVersionHistory(String routeId) {
        throw YamlReadOnly.on("RouteRepository.getVersionHistory");
    }

    @Override
    public Optional<RouteVersion> getVersion(String routeId, int version) {
        throw YamlReadOnly.on("RouteRepository.getVersion");
    }
}