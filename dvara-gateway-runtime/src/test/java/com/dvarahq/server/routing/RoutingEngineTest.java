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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoundRobinRoutingStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingEngineTest {

    @Test
    void fallsBackToDefaultStrategy_whenNoRouteMatches() {
        LlmProvider openai = provider("openai");
        when(openai.supports(any())).thenReturn(true);

        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of()
        );

        ChatRequest request = chatRequest("gpt-4o");
        LlmProvider selected = engine.route(request, List.of(openai));

        assertThat(selected.name()).isEqualTo("openai");
    }

    @Test
    void matchesExactModelPattern() {
        LlmProvider openai = provider("openai");
        LlmProvider anthropic = provider("anthropic");

        RouteConfig config = RouteConfig.builder()
                .id("exact-match")
                .modelPattern("gpt-4o")
                .strategy(RouteConfig.Strategy.ROUND_ROBIN)
                .providers(List.of(
                        RouteConfig.ProviderWeight.builder().provider("openai").weight(1).build(),
                        RouteConfig.ProviderWeight.builder().provider("anthropic").weight(1).build()
                ))
                .build();

        RoundRobinRoutingStrategy rrStrategy =
                new RoundRobinRoutingStrategy(List.of("openai", "anthropic"));

        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of(new RoutingEngine.ResolvedRoute(config, rrStrategy))
        );

        ChatRequest request = chatRequest("gpt-4o");
        List<LlmProvider> providers = List.of(openai, anthropic);

        // First call should use round-robin
        LlmProvider first = engine.route(request, providers);
        LlmProvider second = engine.route(request, providers);

        // Round-robin should alternate between the two
        assertThat(List.of(first.name(), second.name()))
                .containsExactly("openai", "anthropic");
    }

    @Test
    void matchesGlobPattern_withWildcard() {
        LlmProvider openai = provider("openai");
        LlmProvider anthropic = provider("anthropic");

        RouteConfig config = RouteConfig.builder()
                .id("glob-match")
                .modelPattern("gpt*")
                .strategy(RouteConfig.Strategy.ROUND_ROBIN)
                .providers(List.of(
                        RouteConfig.ProviderWeight.builder().provider("openai").weight(1).build()
                ))
                .build();

        RoundRobinRoutingStrategy rrStrategy =
                new RoundRobinRoutingStrategy(List.of("openai"));

        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of(new RoutingEngine.ResolvedRoute(config, rrStrategy))
        );

        ChatRequest request = chatRequest("gpt-4o-mini");

        LlmProvider selected = engine.route(request, List.of(openai, anthropic));
        assertThat(selected.name()).isEqualTo("openai");
    }

    @Test
    void nonMatchingPattern_fallsToDefault() {
        LlmProvider anthropic = provider("anthropic");
        when(anthropic.supports(any())).thenReturn(true);

        RouteConfig config = RouteConfig.builder()
                .id("gpt-route")
                .modelPattern("gpt*")
                .strategy(RouteConfig.Strategy.ROUND_ROBIN)
                .providers(List.of(
                        RouteConfig.ProviderWeight.builder().provider("openai").weight(1).build()
                ))
                .build();

        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of(new RoutingEngine.ResolvedRoute(config,
                        new RoundRobinRoutingStrategy(List.of("openai"))))
        );

        // claude model should NOT match gpt* pattern, falls through to default
        ChatRequest request = chatRequest("claude-3-sonnet");
        LlmProvider selected = engine.route(request, List.of(anthropic));
        assertThat(selected.name()).isEqualTo("anthropic");
    }

    @Test
    void modelVersionPinning_overridesModelInRequest() {
        LlmProvider openai = provider("openai");
        when(openai.supports(any())).thenReturn(true);

        RouteConfig config = RouteConfig.builder()
                .id("pin-version")
                .modelPattern("gpt-4o")
                .strategy(RouteConfig.Strategy.MODEL_PREFIX)
                .providers(List.of())
                .pinnedModelVersion("gpt-4o-2024-08-06")
                .build();

        // Use a custom strategy that captures the request model
        final String[] capturedModel = {null};
        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of(new RoutingEngine.ResolvedRoute(config, (req, provs) -> {
                    capturedModel[0] = req.getModel();
                    return openai;
                }))
        );

        ChatRequest request = chatRequest("gpt-4o");
        engine.route(request, List.of(openai));

        assertThat(capturedModel[0]).isEqualTo("gpt-4o-2024-08-06");
    }

    @Test
    void modelVersionPinning_notSet_preservesOriginalModel() {
        LlmProvider openai = provider("openai");
        when(openai.supports(any())).thenReturn(true);

        RouteConfig config = RouteConfig.builder()
                .id("no-pin")
                .modelPattern("gpt-4o")
                .strategy(RouteConfig.Strategy.MODEL_PREFIX)
                .providers(List.of())
                .build();

        final String[] capturedModel = {null};
        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of(new RoutingEngine.ResolvedRoute(config, (req, provs) -> {
                    capturedModel[0] = req.getModel();
                    return openai;
                }))
        );

        ChatRequest request = chatRequest("gpt-4o");
        engine.route(request, List.of(openai));

        assertThat(capturedModel[0]).isEqualTo("gpt-4o");
    }

    @Test
    void hotReload_updateRoutes_appliesNewConfig() {
        LlmProvider openai = provider("openai");
        LlmProvider anthropic = provider("anthropic");
        when(openai.supports(any())).thenReturn(true);
        when(anthropic.supports(any())).thenReturn(true);

        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of()
        );

        ChatRequest request = chatRequest("gpt-4o");
        List<LlmProvider> providers = List.of(openai, anthropic);

        // Initially no routes — falls through to default (first match)
        LlmProvider initial = engine.route(request, providers);
        assertThat(initial.name()).isEqualTo("openai");

        // Hot-reload: add a route that always picks anthropic
        RouteConfig newConfig = RouteConfig.builder()
                .id("new-route")
                .modelPattern("gpt*")
                .strategy(RouteConfig.Strategy.ROUND_ROBIN)
                .providers(List.of(
                        RouteConfig.ProviderWeight.builder().provider("anthropic").weight(1).build()
                ))
                .build();

        engine.updateRoutes(List.of(new RoutingEngine.ResolvedRoute(
                newConfig, new RoundRobinRoutingStrategy(List.of("anthropic")))));

        // After update, should route to anthropic
        LlmProvider afterUpdate = engine.route(request, providers);
        assertThat(afterUpdate.name()).isEqualTo("anthropic");
    }

    @Test
    void currentRoutes_returnsSnapshot() {
        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of()
        );

        assertThat(engine.currentRoutes()).isEmpty();

        RouteConfig config = RouteConfig.builder()
                .id("r1")
                .modelPattern("gpt*")
                .strategy(RouteConfig.Strategy.ROUND_ROBIN)
                .providers(List.of())
                .build();

        engine.updateRoutes(List.of(new RoutingEngine.ResolvedRoute(
                config, new RoundRobinRoutingStrategy(List.of("openai")))));

        assertThat(engine.currentRoutes()).hasSize(1);
        assertThat(engine.currentRoutes().get(0).config().getId()).isEqualTo("r1");
    }

    @Test
    void nullModel_fallsToDefault() {
        LlmProvider openai = provider("openai");
        when(openai.supports(any())).thenReturn(true);

        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of()
        );

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(MultimodalMessage.user("hi")))
                .build();

        LlmProvider selected = engine.route(request, List.of(openai));
        assertThat(selected.name()).isEqualTo("openai");
    }

    @Test
    void firstMatchingRoute_wins() {
        LlmProvider openai = provider("openai");
        LlmProvider anthropic = provider("anthropic");

        RouteConfig route1 = RouteConfig.builder()
                .id("route1")
                .modelPattern("gpt*")
                .strategy(RouteConfig.Strategy.ROUND_ROBIN)
                .providers(List.of())
                .build();
        RouteConfig route2 = RouteConfig.builder()
                .id("route2")
                .modelPattern("gpt*")
                .strategy(RouteConfig.Strategy.ROUND_ROBIN)
                .providers(List.of())
                .build();

        RoutingEngine engine = new RoutingEngine(
                new ModelPrefixRoutingStrategy(),
                List.of(
                        new RoutingEngine.ResolvedRoute(route1, (req, provs) -> openai),
                        new RoutingEngine.ResolvedRoute(route2, (req, provs) -> anthropic)
                )
        );

        ChatRequest request = chatRequest("gpt-4o");
        LlmProvider selected = engine.route(request, List.of(openai, anthropic));
        assertThat(selected.name()).isEqualTo("openai");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static LlmProvider provider(String name) {
        LlmProvider p = mock(LlmProvider.class);
        when(p.name()).thenReturn(name);
        return p;
    }

    private static ChatRequest chatRequest(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .build();
    }
}