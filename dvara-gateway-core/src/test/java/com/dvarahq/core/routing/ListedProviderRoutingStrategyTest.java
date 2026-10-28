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
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A model-prefix route that names providers sends its models to them, even names no provider
 * claims by prefix. A route that names none keeps the prefix match.
 */
class ListedProviderRoutingStrategyTest {

    private final LlmProvider openai = stubProvider("openai", "gpt");
    private final LlmProvider anthropic = stubProvider("anthropic", "claude");

    @Test
    void aNameNoProviderClaims_goesToTheListedProvider() {
        var strategy = ModelPrefixRoutingStrategy.forRoute(route("dmr", "openai"));

        LlmProvider result = strategy.route(request("ai/llama3.2"), List.of(anthropic, openai));

        assertThat(result.name()).isEqualTo("openai");
    }

    @Test
    void theListedProviderIsUsed_evenWhenAnotherProviderClaimsTheName() {
        var strategy = ModelPrefixRoutingStrategy.forRoute(route("gpt-via-anthropic", "anthropic"));

        assertThat(strategy.route(request("gpt-4o"), List.of(openai, anthropic)).name()).isEqualTo("anthropic");
    }

    @Test
    void providersAreTriedInTheOrderListed_skippingOnesNotRegistered() {
        var strategy = ModelPrefixRoutingStrategy.forRoute(route("r", "fireworks", "anthropic", "openai"));

        assertThat(strategy.route(request("accounts/x/models/y"), List.of(openai, anthropic)).name())
                .isEqualTo("anthropic");
    }

    @Test
    void noListedProviderRegistered_refusesNamingTheRouteAndProviders() {
        var strategy = ModelPrefixRoutingStrategy.forRoute(route("dmr", "docker"));

        assertThatThrownBy(() -> strategy.route(request("ai/llama3.2"), List.of(openai)))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("ai/llama3.2")
                .hasMessageContaining("'dmr'")
                .hasMessageContaining("[docker]");
    }

    @Test
    void aRouteNamingNoProvider_keepsThePrefixMatch() {
        assertThat(ModelPrefixRoutingStrategy.forRoute(route("plain"))).isInstanceOf(ModelPrefixRoutingStrategy.class);

        RouteConfig nullProviders = RouteConfig.builder().id("plain").strategy(RouteConfig.Strategy.MODEL_PREFIX).build();
        nullProviders.setProviders(null);
        assertThat(ModelPrefixRoutingStrategy.forRoute(nullProviders)).isInstanceOf(ModelPrefixRoutingStrategy.class);
    }

    @Test
    void blankAndRepeatedNamesAreDropped() {
        var strategy = ModelPrefixRoutingStrategy.forRoute(route("r", "openai", " ", "openai"));

        assertThat(strategy).isInstanceOf(ListedProviderRoutingStrategy.class);
        assertThat(((ListedProviderRoutingStrategy) strategy).providerNames()).containsExactly("openai");
    }

    private static RouteConfig route(String id, String... providers) {
        return RouteConfig.builder()
                .id(id)
                .strategy(RouteConfig.Strategy.MODEL_PREFIX)
                .providers(Arrays.stream(providers)
                        .map(p -> RouteConfig.ProviderWeight.builder().provider(p).weight(1).build())
                        .toList())
                .build();
    }

    private static ChatRequest request(String model) {
        return ChatRequest.builder().model(model).build();
    }

    private static LlmProvider stubProvider(String name, String prefix) {
        return new LlmProvider() {
            @Override public String name() { return name; }
            @Override public boolean supports(ChatRequest request) {
                return request.getModel() != null && request.getModel().startsWith(prefix);
            }
            @Override public ChatResponse chat(ChatRequest request) { return null; }
            @Override public Iterator<SseChunk> streamChat(ChatRequest request) { return null; }
            @Override public boolean supportsEmbedding(String model) { return false; }
            @Override public EmbeddingResponse embed(EmbeddingRequest request) { return null; }
            @Override public ProviderCapabilities capabilities() { return null; }
        };
    }
}
