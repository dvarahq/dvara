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
package com.dvarahq.server.routing;

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.autoconfigure.RoutingAutoConfiguration;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoundRobinRoutingStrategy;
import com.dvarahq.core.routing.WeightedRoutingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingAutoConfigurationTest {

    @Test
    void buildRoutes_roundRobin() {
        GatewayProperties.WeightedProvider wp1 = new GatewayProperties.WeightedProvider();
        wp1.setProvider("openai");
        wp1.setWeight(1);

        GatewayProperties.WeightedProvider wp2 = new GatewayProperties.WeightedProvider();
        wp2.setProvider("anthropic");
        wp2.setWeight(1);

        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId("rr-route");
        def.setModelPattern("gpt*");
        def.setStrategy("round-robin");
        def.setProviders(List.of(wp1, wp2));

        List<RoutingEngine.ResolvedRoute> routes =
                RoutingAutoConfiguration.buildRoutes(List.of(def));

        assertThat(routes).hasSize(1);
        RoutingEngine.ResolvedRoute route = routes.get(0);
        assertThat(route.config().getId()).isEqualTo("rr-route");
        assertThat(route.config().getModelPattern()).isEqualTo("gpt*");
        assertThat(route.config().getStrategy()).isEqualTo(RouteConfig.Strategy.ROUND_ROBIN);
        assertThat(route.strategy()).isInstanceOf(RoundRobinRoutingStrategy.class);
    }

    @Test
    void buildRoutes_weighted() {
        GatewayProperties.WeightedProvider wp1 = new GatewayProperties.WeightedProvider();
        wp1.setProvider("openai");
        wp1.setWeight(70);

        GatewayProperties.WeightedProvider wp2 = new GatewayProperties.WeightedProvider();
        wp2.setProvider("anthropic");
        wp2.setWeight(30);

        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId("weighted-route");
        def.setModelPattern("claude*");
        def.setStrategy("weighted");
        def.setProviders(List.of(wp1, wp2));

        List<RoutingEngine.ResolvedRoute> routes =
                RoutingAutoConfiguration.buildRoutes(List.of(def));

        assertThat(routes).hasSize(1);
        RoutingEngine.ResolvedRoute route = routes.get(0);
        assertThat(route.config().getStrategy()).isEqualTo(RouteConfig.Strategy.WEIGHTED);
        assertThat(route.strategy()).isInstanceOf(WeightedRoutingStrategy.class);
        assertThat(route.config().getProviders()).hasSize(2);
        assertThat(route.config().getProviders().get(0).getWeight()).isEqualTo(70);
    }

    @Test
    void buildRoutes_modelPrefix_default() {
        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId("pin-route");
        def.setModelPattern("gpt-4o");
        def.setPinnedModelVersion("gpt-4o-2024-08-06");
        // strategy defaults to "model-prefix"

        List<RoutingEngine.ResolvedRoute> routes =
                RoutingAutoConfiguration.buildRoutes(List.of(def));

        assertThat(routes).hasSize(1);
        assertThat(routes.get(0).config().getStrategy()).isEqualTo(RouteConfig.Strategy.MODEL_PREFIX);
        assertThat(routes.get(0).config().getPinnedModelVersion()).isEqualTo("gpt-4o-2024-08-06");
    }

    @Test
    void buildRoutes_emptyList_returnsEmpty() {
        List<RoutingEngine.ResolvedRoute> routes =
                RoutingAutoConfiguration.buildRoutes(List.of());

        assertThat(routes).isEmpty();
    }

    @Test
    void buildRoutes_canary_splitOutsideAPercentage_refusesTheBoot() {
        // The strategy compares the split against a roll of 0..99, so a split of 500 would send every
        // request to the candidate. It is refused at startup, where the operator can see it.
        GatewayProperties.WeightedProvider wp = new GatewayProperties.WeightedProvider();
        wp.setProvider("openai");
        wp.setWeight(1);

        GatewayProperties.CanaryConfigDefinition canary = new GatewayProperties.CanaryConfigDefinition();
        canary.setBaselineProvider("openai");
        canary.setCandidateProvider("bedrock");
        canary.setSplitPct(500);

        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId("canary-route");
        def.setModelPattern("gpt*");
        def.setStrategy("canary");
        def.setProviders(List.of(wp));
        def.setCanaryConfig(canary);

        assertThatThrownBy(() -> RoutingAutoConfiguration.buildRoutes(List.of(def)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between 0 and 100")
                .hasMessageContaining("500");
    }

    @Test
    void buildRoutes_canary_splitOfOneHundred_isAccepted() {
        // The boundary is a legitimate setting: a canary that has completed and cut fully over.
        GatewayProperties.WeightedProvider wp = new GatewayProperties.WeightedProvider();
        wp.setProvider("openai");
        wp.setWeight(1);

        GatewayProperties.CanaryConfigDefinition canary = new GatewayProperties.CanaryConfigDefinition();
        canary.setBaselineProvider("openai");
        canary.setCandidateProvider("bedrock");
        canary.setSplitPct(100);

        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId("canary-route");
        def.setModelPattern("gpt*");
        def.setStrategy("canary");
        def.setProviders(List.of(wp));
        def.setCanaryConfig(canary);

        assertThat(RoutingAutoConfiguration.buildRoutes(List.of(def))).hasSize(1);
    }

    /** A configured canary is stated at startup at INFO, not as a warning repeated on every boot. */
    @Test
    void buildRoutes_canary_statesTheSplitAtInfo() {
        GatewayProperties.WeightedProvider wp = new GatewayProperties.WeightedProvider();
        wp.setProvider("openai");
        wp.setWeight(1);
        GatewayProperties.CanaryConfigDefinition canary = new GatewayProperties.CanaryConfigDefinition();
        canary.setBaselineProvider("openai");
        canary.setCandidateProvider("bedrock");
        canary.setSplitPct(20);
        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId("canary-route");
        def.setModelPattern("gpt*");
        def.setStrategy("canary");
        def.setProviders(List.of(wp));
        def.setCanaryConfig(canary);

        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RoutingAutoConfiguration.class);
        var previousLevel = logger.getLevel();
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        logger.addAppender(appender);
        try {
            RoutingAutoConfiguration.buildRoutes(List.of(def));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
        }

        var canaryLines = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("canary-route")).toList();
        assertThat(canaryLines).hasSize(1);
        assertThat(canaryLines.get(0).getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
        assertThat(canaryLines.get(0).getFormattedMessage()).contains("20% to candidate 'bedrock'");
    }

    @Test
    void buildRoutes_canary_withCanaryConfig_populatesRouteConfig() {
        GatewayProperties.WeightedProvider wpOpenai = new GatewayProperties.WeightedProvider();
        wpOpenai.setProvider("openai");
        wpOpenai.setWeight(1);
        GatewayProperties.WeightedProvider wpBedrock = new GatewayProperties.WeightedProvider();
        wpBedrock.setProvider("bedrock");
        wpBedrock.setWeight(1);

        GatewayProperties.CanaryConfigDefinition canary = new GatewayProperties.CanaryConfigDefinition();
        canary.setBaselineProvider("openai");
        canary.setCandidateProvider("bedrock");
        canary.setSplitPct(20);
        canary.setWorkspaceScope("acme-corp");
        canary.setTestName("bedrock-eval");

        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId("canary-route");
        def.setModelPattern("gpt*");
        def.setStrategy("canary");
        def.setProviders(List.of(wpOpenai, wpBedrock));
        def.setCanaryConfig(canary);

        List<RoutingEngine.ResolvedRoute> routes =
                RoutingAutoConfiguration.buildRoutes(List.of(def));

        assertThat(routes).hasSize(1);
        RouteConfig cfg = routes.get(0).config();
        assertThat(cfg.getStrategy()).isEqualTo(RouteConfig.Strategy.CANARY);
        assertThat(cfg.getCanaryConfig()).isNotNull();
        assertThat(cfg.getCanaryConfig().getBaselineProvider()).isEqualTo("openai");
        assertThat(cfg.getCanaryConfig().getCandidateProvider()).isEqualTo("bedrock");
        assertThat(cfg.getCanaryConfig().getSplitPct()).isEqualTo(20);
        assertThat(cfg.getCanaryConfig().getWorkspaceScope()).isEqualTo("acme-corp");
        assertThat(cfg.getCanaryConfig().getTestName()).isEqualTo("bedrock-eval");
    }

    @Test
    void buildRoutes_withShadowConfig_isRefused() {
        GatewayProperties.WeightedProvider wp = new GatewayProperties.WeightedProvider();
        wp.setProvider("openai");
        wp.setWeight(1);

        GatewayProperties.ShadowConfigDefinition shadow = new GatewayProperties.ShadowConfigDefinition();
        shadow.setShadowProvider("bedrock");
        shadow.setSamplePct(10);
        shadow.setWorkspaceScope("acme-corp");
        shadow.setTestName("shadow-eval");

        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId("shadow-route");
        def.setModelPattern("gpt*");
        def.setStrategy("model-prefix");
        def.setProviders(List.of(wp));
        def.setShadowConfig(shadow);

        // The shadow dispatcher does not send shadow traffic, so a shadow block would be reported as
        // active and never dispatched. Startup is refused instead, naming the route.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RoutingAutoConfiguration.buildRoutes(List.of(def)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shadow-route")
                .hasMessageContaining("does not dispatch shadow traffic");
    }

    // Binds kebab-case canary-config / shadow-config properties through Spring Boot's Binder, because a
    // hand-built RouteDefinition cannot show that relaxed binding populates those fields.
    @Test
    void yamlBinding_canaryAndShadowBlocks_populateRouteDefinition() {
        java.util.Map<String, Object> props = new java.util.HashMap<>();
        props.put("gateway.routes[0].id", "canary-route");
        props.put("gateway.routes[0].model-pattern", "gpt*");
        props.put("gateway.routes[0].strategy", "canary");
        props.put("gateway.routes[0].providers[0].provider", "openai");
        props.put("gateway.routes[0].providers[0].weight", "1");
        props.put("gateway.routes[0].providers[1].provider", "bedrock");
        props.put("gateway.routes[0].providers[1].weight", "1");
        props.put("gateway.routes[0].canary-config.baseline-provider", "openai");
        props.put("gateway.routes[0].canary-config.candidate-provider", "bedrock");
        props.put("gateway.routes[0].canary-config.split-pct", "20");
        props.put("gateway.routes[0].canary-config.workspace-scope", "acme-corp");
        props.put("gateway.routes[0].canary-config.test-name", "bedrock-eval");
        props.put("gateway.routes[0].shadow-config.shadow-provider", "anthropic");
        props.put("gateway.routes[0].shadow-config.sample-pct", "10");
        props.put("gateway.routes[0].shadow-config.test-name", "shadow-eval");

        org.springframework.boot.context.properties.source.MapConfigurationPropertySource source =
                new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(props);
        GatewayProperties bound = new org.springframework.boot.context.properties.bind.Binder(source)
                .bind("gateway", GatewayProperties.class)
                .get();

        assertThat(bound.getRoutes()).hasSize(1);
        GatewayProperties.RouteDefinition def = bound.getRoutes().get(0);

        assertThat(def.getCanaryConfig()).isNotNull();
        assertThat(def.getCanaryConfig().getBaselineProvider()).isEqualTo("openai");
        assertThat(def.getCanaryConfig().getCandidateProvider()).isEqualTo("bedrock");
        assertThat(def.getCanaryConfig().getSplitPct()).isEqualTo(20);
        assertThat(def.getCanaryConfig().getWorkspaceScope()).isEqualTo("acme-corp");
        assertThat(def.getCanaryConfig().getTestName()).isEqualTo("bedrock-eval");

        assertThat(def.getShadowConfig()).isNotNull();
        assertThat(def.getShadowConfig().getShadowProvider()).isEqualTo("anthropic");
        assertThat(def.getShadowConfig().getSamplePct()).isEqualTo(10);
        assertThat(def.getShadowConfig().getTestName()).isEqualTo("shadow-eval");

        // The shadow block binds and is then refused, because the shadow dispatcher does not send
        // shadow traffic.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RoutingAutoConfiguration.buildRoutes(List.of(def)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not dispatch shadow traffic");

        // Without it, the bound canary block threads through buildRoutes to a real RouteConfig.
        def.setShadowConfig(null);
        List<RoutingEngine.ResolvedRoute> routes =
                RoutingAutoConfiguration.buildRoutes(List.of(def));
        RouteConfig cfg = routes.get(0).config();
        assertThat(cfg.getCanaryConfig()).isNotNull();
        assertThat(cfg.getCanaryConfig().getSplitPct()).isEqualTo(20);
        assertThat(cfg.getShadowConfig()).isNull();
    }

    @Test
    void buildRoutes_noCanaryOrShadow_leavesBothNull() {
        GatewayProperties.WeightedProvider wp = new GatewayProperties.WeightedProvider();
        wp.setProvider("openai");
        wp.setWeight(1);

        GatewayProperties.RouteDefinition def = new GatewayProperties.RouteDefinition();
        def.setId("plain-route");
        def.setModelPattern("gpt*");
        def.setStrategy("model-prefix");
        def.setProviders(List.of(wp));

        List<RoutingEngine.ResolvedRoute> routes =
                RoutingAutoConfiguration.buildRoutes(List.of(def));

        assertThat(routes).hasSize(1);
        RouteConfig cfg = routes.get(0).config();
        assertThat(cfg.getCanaryConfig()).isNull();
        assertThat(cfg.getShadowConfig()).isNull();
    }

    @Test
    void buildRoutes_multipleRoutes() {
        GatewayProperties.WeightedProvider wp = new GatewayProperties.WeightedProvider();
        wp.setProvider("openai");
        wp.setWeight(1);

        GatewayProperties.RouteDefinition def1 = new GatewayProperties.RouteDefinition();
        def1.setId("route-1");
        def1.setModelPattern("gpt*");
        def1.setStrategy("round-robin");
        def1.setProviders(List.of(wp));

        GatewayProperties.RouteDefinition def2 = new GatewayProperties.RouteDefinition();
        def2.setId("route-2");
        def2.setModelPattern("claude*");
        def2.setStrategy("weighted");
        def2.setProviders(List.of(wp));

        List<RoutingEngine.ResolvedRoute> routes =
                RoutingAutoConfiguration.buildRoutes(List.of(def1, def2));

        assertThat(routes).hasSize(2);
        assertThat(routes.get(0).config().getId()).isEqualTo("route-1");
        assertThat(routes.get(1).config().getId()).isEqualTo("route-2");
    }
}