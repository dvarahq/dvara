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
package com.dvarahq.autoconfigure;

import com.dvarahq.autoconfigure.routing.DefaultRoutingStrategyFactory;
import com.dvarahq.core.provider.RoutingStrategy;
import com.dvarahq.core.routing.CanaryConfig;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import com.dvarahq.core.routing.ShadowConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.routing.RequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@AutoConfiguration
@EnableConfigurationProperties(GatewayProperties.class)
public class RoutingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RoutingAutoConfiguration.class);

    /**
     * The default engine, unless the application registers its own.
     *
     * <p>Conditioned on {@code RoutingEngine}, what this produces, rather than on
     * {@code RoutingStrategy}. The engine is itself a {@code RoutingStrategy}, and a condition on
     * the strategy would back off for an application's custom strategy and leave nothing to
     * provide the engine that the YAML publisher and other consumers require. A
     * {@code RoutingStrategy} bean that is not an engine becomes this engine's default strategy,
     * the one a request takes when no route matches.
     *
     * <p>That strategy is resolved at first use rather than here: asking the context for a strategy
     * while this bean is being created is a circular reference, because the engine is one. By the
     * first route the engine exists and is filtered out.
     *
     * <p>{@code @Primary}, because with an application strategy present there are two candidates
     * for the unqualified {@code RoutingStrategy} the provider dispatcher injects. The engine is
     * what the dispatcher must get; the application's strategy reaches requests through it, so an
     * application strategy must not itself be primary.
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(RoutingEngine.class)
    public RoutingEngine routingEngine(GatewayProperties props, RoutingStrategyFactory strategyFactory,
                                       ObjectProvider<RoutingStrategy> strategies) {
        List<RoutingEngine.ResolvedRoute> routes = buildRoutes(props.getRoutes(), strategyFactory);
        return new RoutingEngine(new ApplicationDefaultStrategy(strategies), routes);
    }

    /**
     * The application's own {@code RoutingStrategy} bean if it has one, else model-prefix; see above.
     * With several, the first in {@code @Order} wins — the contract is one application strategy.
     */
    static final class ApplicationDefaultStrategy implements RoutingStrategy {
        private final ObjectProvider<RoutingStrategy> strategies;
        private volatile RoutingStrategy resolved;

        ApplicationDefaultStrategy(ObjectProvider<RoutingStrategy> strategies) {
            this.strategies = strategies;
        }

        private RoutingStrategy resolved() {
            RoutingStrategy r = resolved;
            if (r == null) {
                r = strategies.orderedStream()
                        .filter(s -> !(s instanceof RoutingEngine))
                        .findFirst()
                        .orElseGet(ModelPrefixRoutingStrategy::new);
                resolved = r;
            }
            return r;
        }

        @Override
        public LlmProvider route(ChatRequest request, List<LlmProvider> providers) {
            return resolved().route(request, providers);
        }

        @Override
        public LlmProvider route(ChatRequest request, List<LlmProvider> providers, RequestContext ctx) {
            return resolved().route(request, providers, ctx);
        }
    }

    public static List<RoutingEngine.ResolvedRoute> buildRoutes(List<GatewayProperties.RouteDefinition> defs,
                                                                 RoutingStrategyFactory strategyFactory) {
        List<RoutingEngine.ResolvedRoute> routes = new ArrayList<>();
        for (GatewayProperties.RouteDefinition def : defs) {
            RouteConfig config = toRouteConfig(def);
            RoutingStrategy strategy = strategyFactory.create(config);
            routes.add(new RoutingEngine.ResolvedRoute(config, strategy));
        }
        return routes;
    }

    /** @deprecated Use {@link RoutingStrategyFactory} instead. Kept for backward compatibility. */
    @Deprecated
    public static List<RoutingEngine.ResolvedRoute> buildRoutes(List<GatewayProperties.RouteDefinition> defs) {
        return buildRoutes(defs, new DefaultRoutingStrategyFactory());
    }

    private static RouteConfig toRouteConfig(GatewayProperties.RouteDefinition def) {
        List<RouteConfig.ProviderWeight> weights = def.getProviders().stream()
                .map(wp -> RouteConfig.ProviderWeight.builder()
                        .provider(wp.getProvider())
                        .weight(wp.getWeight())
                        .region(wp.getRegion())
                        .build())
                .collect(Collectors.toList());

        RouteConfig.Strategy strategy = switch (def.getStrategy()) {
            case "round-robin" -> RouteConfig.Strategy.ROUND_ROBIN;
            case "weighted" -> RouteConfig.Strategy.WEIGHTED;
            case "canary" -> RouteConfig.Strategy.CANARY;
            case "latency-aware", "cost-aware", "geo-aware", "intelligent" -> throw new IllegalStateException(
                    "route '" + def.getId() + "': strategy " + def.getStrategy()
                            + " is not part of this build. Supported: model-prefix, round-robin, weighted, canary.");
            default -> RouteConfig.Strategy.MODEL_PREFIX;
        };

        // A canary sends part of a route's live traffic to an unproven provider, so the split is stated at
        // startup. INFO, not WARN: a configured canary is the normal state of that route, not a problem, and a
        // warning on every boot trains operators to ignore the level.
        if (def.getCanaryConfig() != null) {
            log.info("Canary split on route '{}': {}% to candidate '{}' (baseline '{}', workspace scope '{}').",
                    def.getId(), def.getCanaryConfig().getSplitPct(),
                    def.getCanaryConfig().getCandidateProvider(),
                    def.getCanaryConfig().getBaselineProvider(),
                    def.getCanaryConfig().getWorkspaceScope());
        }
        if (def.getShadowConfig() != null) {
            // This build does not dispatch shadow traffic, so a shadow block would be parsed,
            // reported as active and never dispatched. Refused, so the operator learns it here.
            throw new IllegalStateException("route '" + def.getId() + "': shadow-config is set, but this "
                    + "build does not dispatch shadow traffic. Remove the block.");
        }

        return RouteConfig.builder()
                .id(def.getId())
                .modelPattern(def.getModelPattern())
                .strategy(strategy)
                .providers(weights)
                .pinnedModelVersion(def.getPinnedModelVersion())
                .costTolerancePct(def.getCostTolerancePct())
                .latencySlaMs(def.getLatencySlaMs())
                .modelTiers(def.getModelTiers())
                .canaryConfig(toCanaryConfig(def.getCanaryConfig()))
                .shadowConfig(toShadowConfig(def.getShadowConfig()))
                .build();
    }

    /**
     * Binds the YAML {@code canary-config} block onto the core
     * {@link CanaryConfig} type.
     */
    private static CanaryConfig toCanaryConfig(GatewayProperties.CanaryConfigDefinition def) {
        if (def == null) return null;
        // A split is a percentage, and the strategy compares it against a roll of 0..99 — so 500
        // sends EVERY request to the candidate, and a negative sends none while the canary reports
        // itself configured. Refused here, at the boundary, where the operator who typed it is
        // watching; the strategy is separately defensive about a value that reached it some other way.
        if (def.getSplitPct() < 0 || def.getSplitPct() > 100) {
            throw new IllegalStateException("canary-config split-pct must be between 0 and 100, was "
                    + def.getSplitPct() + ". A canary bounds how much traffic reaches an unproven "
                    + "provider; a value outside that range sends all of it or none.");
        }
        return CanaryConfig.builder()
                .baselineProvider(def.getBaselineProvider())
                .candidateProvider(def.getCandidateProvider())
                .splitPct(def.getSplitPct())
                .workspaceScope(def.getWorkspaceScope())
                .testName(def.getTestName())
                .build();
    }

    /**
     * Binds the YAML {@code shadow-config} block onto the core {@link ShadowConfig} type.
     */
    private static ShadowConfig toShadowConfig(GatewayProperties.ShadowConfigDefinition def) {
        if (def == null) return null;
        return ShadowConfig.builder()
                .shadowProvider(def.getShadowProvider())
                .samplePct(def.getSamplePct())
                .workspaceScope(def.getWorkspaceScope())
                .testName(def.getTestName())
                .build();
    }

    /** @deprecated Use {@link RoutingStrategyFactory#create(RouteConfig)} instead. */
    @Deprecated
    public static RoutingStrategy createResolvedStrategy(RouteConfig config) {
        return new DefaultRoutingStrategyFactory().create(config);
    }
}