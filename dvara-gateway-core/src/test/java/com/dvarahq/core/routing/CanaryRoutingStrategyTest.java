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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanaryRoutingStrategyTest {

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

    @Test
    void splitDistribution_routesBetweenBaselineAndCandidate() {
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(50)
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("openai"), stubProvider("anthropic"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        AtomicInteger baselineCount = new AtomicInteger();
        AtomicInteger candidateCount = new AtomicInteger();

        for (int i = 0; i < 1000; i++) {
            LlmProvider selected = strategy.route(request, providers);
            if ("openai".equals(selected.name())) {
                baselineCount.incrementAndGet();
            } else {
                candidateCount.incrementAndGet();
            }
        }

        assertThat(baselineCount.get()).isBetween(350, 650);
        assertThat(candidateCount.get()).isBetween(350, 650);
    }

    @Test
    void splitPct0_allTrafficToBaseline() {
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(0)
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("openai"), stubProvider("anthropic"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        for (int i = 0; i < 100; i++) {
            assertThat(strategy.route(request, providers).name()).isEqualTo("openai");
        }
    }

    @Test
    void splitPct100_allTrafficToCandidate() {
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(100)
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("openai"), stubProvider("anthropic"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        for (int i = 0; i < 100; i++) {
            assertThat(strategy.route(request, providers).name()).isEqualTo("anthropic");
        }
    }

    @Test
    void workspaceScoping_matchingWorkspace_usesSplit() {
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(100)
                .workspaceScope("workspace-a")
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("openai"), stubProvider("anthropic"));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of("workspace_id", "workspace-a"))
                .build();

        assertThat(strategy.route(request, providers).name()).isEqualTo("anthropic");
    }

    @Test
    void workspaceScoping_nonMatchingWorkspace_routesToBaseline() {
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(100)
                .workspaceScope("workspace-a")
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("openai"), stubProvider("anthropic"));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of("workspace_id", "workspace-b"))
                .build();

        assertThat(strategy.route(request, providers).name()).isEqualTo("openai");
    }

    @Test
    void workspaceScoping_noWorkspace_routesToBaseline() {
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(100)
                .workspaceScope("workspace-a")
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("openai"), stubProvider("anthropic"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThat(strategy.route(request, providers).name()).isEqualTo("openai");
    }

    @Test
    void providerFallback_baselineNotFound_usesCandidate() {
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(0)
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("anthropic"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThat(strategy.route(request, providers).name()).isEqualTo("anthropic");
    }

    @Test
    void noProvider_throwsException() {
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(50)
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("gemini"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThatThrownBy(() -> strategy.route(request, providers))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("No provider found for canary routing");
    }

    // ---------------------------------------------- a split that is not a percentage

    @Test
    void aSplitAboveOneHundredSendsNoTrafficToTheCandidate() {
        // The roll is 0..99, so `roll < 500` is always true and every request would reach the
        // unproven provider — the opposite of what a canary is for. Zero rather than a clamp to 100:
        // a value that cannot be honoured must not be guessed at in the direction of more exposure.
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(500)
                .testName("fat-fingered")
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("openai"), stubProvider("anthropic"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        for (int i = 0; i < 200; i++) {
            assertThat(strategy.route(request, providers).name())
                    .as("every request stays on the baseline")
                    .isEqualTo("openai");
        }
    }

    @Test
    void aNegativeSplitAlsoSendsNoTrafficToTheCandidate() {
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(-10)
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("openai"), stubProvider("anthropic"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        for (int i = 0; i < 200; i++) {
            assertThat(strategy.route(request, providers).name()).isEqualTo("openai");
        }
    }

    @Test
    void oneHundredIsStillHonoured() {
        // The boundary is a legitimate setting — a completed canary cut fully over — so the guard
        // must not take it for a mistake.
        CanaryConfig config = CanaryConfig.builder()
                .baselineProvider("openai")
                .candidateProvider("anthropic")
                .splitPct(100)
                .build();

        CanaryRoutingStrategy strategy = new CanaryRoutingStrategy(config);
        List<LlmProvider> providers = List.of(stubProvider("openai"), stubProvider("anthropic"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        for (int i = 0; i < 200; i++) {
            assertThat(strategy.route(request, providers).name()).isEqualTo("anthropic");
        }
    }
}
