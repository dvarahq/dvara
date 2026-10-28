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

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.autoconfigure.RoutingAutoConfiguration;
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.dvarahq.autoconfigure.routing.DefaultRoutingStrategyFactory;
import com.dvarahq.core.routing.CanaryRoutingStrategy;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RoutingEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Routes from {@code gateway.yaml} are added to the routes from the startup properties; they do
 * not replace them. A canary route can only come from the startup properties, so replacing them
 * would drop it without a word on every setup whose file has a {@code routes:} block.
 */
class YamlRoutePublisherTest {

    private final DefaultRoutingStrategyFactory factory = new DefaultRoutingStrategyFactory();

    @Test
    void aCanaryRouteFromTheStartupPropertiesIsKept_besideTheFileRoutes() {
        RoutingEngine engine = engineWith(canaryRoute("gpt4o-canary", "gpt-4o"));
        YamlConfigStore store = storeWith(fileRoute("everything-else", "gpt*"));

        new YamlRoutePublisher(store, engine, factory).run(null);

        assertThat(engine.currentRoutes()).extracting(r -> r.config().getId())
                .containsExactly("gpt4o-canary", "everything-else");
        assertThat(engine.currentRoutes().get(0).strategy())
                .as("the canary keeps its split")
                .isInstanceOf(CanaryRoutingStrategy.class);
    }

    @Test
    void theSameRouteIdInBothPlacesRefusesTheStart_namingIt() {
        RoutingEngine engine = engineWith(canaryRoute("gpt", "gpt-4o"));
        YamlConfigStore store = storeWith(fileRoute("gpt", "gpt*"));

        assertThatThrownBy(() -> new YamlRoutePublisher(store, engine, factory).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[gpt]")
                .hasMessageContaining("gateway.yaml")
                .hasMessageContaining("dvara.llm-gateway.routes");
    }

    @Test
    void withNoPropertyRoutes_theFileRoutesAreServedAsBefore() {
        RoutingEngine engine = engineWith();
        YamlConfigStore store = storeWith(fileRoute("a", "gpt*"), fileRoute("b", "claude*"));

        new YamlRoutePublisher(store, engine, factory).run(null);

        assertThat(engine.currentRoutes()).extracting(r -> r.config().getId()).containsExactly("a", "b");
    }

    @Test
    void withNoFileRoutes_thePropertyRoutesAreServedAsBefore() {
        RoutingEngine engine = engineWith(canaryRoute("gpt4o-canary", "gpt-4o"));

        new YamlRoutePublisher(storeWith(), engine, factory).run(null);

        assertThat(engine.currentRoutes()).extracting(r -> r.config().getId()).containsExactly("gpt4o-canary");
    }

    @Test
    void aFileRouteAPropertyRouteAlwaysMatchesFirst_isRecognised() {
        assertThat(YamlRoutePublisher.alwaysMatchesFirst("gpt*", "gpt-4o")).isTrue();
        assertThat(YamlRoutePublisher.alwaysMatchesFirst("gpt*", "gpt-4*")).isTrue();
        assertThat(YamlRoutePublisher.alwaysMatchesFirst("gpt-4o", "gpt-4o")).isTrue();
        assertThat(YamlRoutePublisher.alwaysMatchesFirst("gpt-4o", "gpt*")).isFalse();
        assertThat(YamlRoutePublisher.alwaysMatchesFirst("gpt-4*", "gpt*")).isFalse();
        assertThat(YamlRoutePublisher.alwaysMatchesFirst("claude*", "gpt-4o")).isFalse();
    }

    private RoutingEngine engineWith(GatewayProperties.RouteDefinition... defs) {
        return new RoutingEngine(new ModelPrefixRoutingStrategy(),
                RoutingAutoConfiguration.buildRoutes(List.of(defs), factory));
    }

    private static GatewayProperties.RouteDefinition canaryRoute(String id, String model) {
        GatewayProperties.WeightedProvider baseline = new GatewayProperties.WeightedProvider();
        baseline.setProvider("openai");
        baseline.setWeight(1);
        GatewayProperties.WeightedProvider candidate = new GatewayProperties.WeightedProvider();
        candidate.setProvider("azure-openai");
        candidate.setWeight(1);

        GatewayProperties.CanaryConfigDefinition canary = new GatewayProperties.CanaryConfigDefinition();
        canary.setBaselineProvider("openai");
        canary.setCandidateProvider("azure-openai");
        canary.setSplitPct(10);

        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId(id);
        def.setModelPattern(model);
        def.setStrategy("canary");
        def.setProviders(List.of(baseline, candidate));
        def.setCanaryConfig(canary);
        return def;
    }

    private static GatewayYamlConfig.RouteEntry fileRoute(String id, String model) {
        GatewayYamlConfig.RouteEntry route = new GatewayYamlConfig.RouteEntry();
        route.setId(id);
        route.setModel(model);
        route.setProvider("openai");
        return route;
    }

    private static YamlConfigStore storeWith(GatewayYamlConfig.RouteEntry... routes) {
        GatewayYamlConfig config = new GatewayYamlConfig();
        config.setRoutes(List.of(routes));
        return new YamlConfigStore(config);
    }
}
