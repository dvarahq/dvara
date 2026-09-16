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

import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoundRobinRoutingStrategyTest {

    @Test
    void cyclesThroughProviders() {
        RoundRobinRoutingStrategy strategy = new RoundRobinRoutingStrategy(List.of("a", "b", "c"));
        List<LlmProvider> providers = List.of(stubProvider("a"), stubProvider("b"), stubProvider("c"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThat(strategy.route(request, providers).name()).isEqualTo("a");
        assertThat(strategy.route(request, providers).name()).isEqualTo("b");
        assertThat(strategy.route(request, providers).name()).isEqualTo("c");
        assertThat(strategy.route(request, providers).name()).isEqualTo("a");
    }

    @Test
    void skipsUnavailableProvider_andRoutesToNext() {
        RoundRobinRoutingStrategy strategy = new RoundRobinRoutingStrategy(List.of("a", "b", "c"));
        List<LlmProvider> providers = List.of(stubProvider("a"), stubProvider("c"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        // First call targets "a" — available
        assertThat(strategy.route(request, providers).name()).isEqualTo("a");
        // Second call targets "b" — missing, falls through to "c"
        assertThat(strategy.route(request, providers).name()).isEqualTo("c");
    }

    @Test
    void throwsGatewayException_whenNoProviderAvailable() {
        RoundRobinRoutingStrategy strategy = new RoundRobinRoutingStrategy(List.of("a", "b"));
        List<LlmProvider> providers = List.of(stubProvider("x"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThatThrownBy(() -> strategy.route(request, providers))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("No available provider in round-robin pool");
    }

    @Test
    void constructorRejectsNullList() {
        assertThatThrownBy(() -> new RoundRobinRoutingStrategy(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsEmptyList() {
        assertThatThrownBy(() -> new RoundRobinRoutingStrategy(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static LlmProvider stubProvider(String name) {
        return new LlmProvider() {
            @Override public String name() { return name; }
            @Override public boolean supports(ChatRequest request) { return false; }
            @Override public ChatResponse chat(ChatRequest request) { return null; }
            @Override public Iterator<SseChunk> streamChat(ChatRequest request) { return null; }
            @Override public boolean supportsEmbedding(String model) { return false; }
            @Override public EmbeddingResponse embed(EmbeddingRequest request) { return null; }
            @Override public ProviderCapabilities capabilities() { return null; }
        };
    }
}