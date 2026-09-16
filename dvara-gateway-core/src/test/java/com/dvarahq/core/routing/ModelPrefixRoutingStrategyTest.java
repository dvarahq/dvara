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

class ModelPrefixRoutingStrategyTest {

    private final ModelPrefixRoutingStrategy strategy = new ModelPrefixRoutingStrategy();

    @Test
    void routesToFirstSupportingProvider() {
        LlmProvider openai = stubProvider("openai", "gpt");
        LlmProvider anthropic = stubProvider("anthropic", "claude");
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        LlmProvider result = strategy.route(request, List.of(openai, anthropic));

        assertThat(result.name()).isEqualTo("openai");
    }

    @Test
    void routesToSecondProvider_whenFirstDoesNotSupport() {
        LlmProvider openai = stubProvider("openai", "gpt");
        LlmProvider anthropic = stubProvider("anthropic", "claude");
        ChatRequest request = ChatRequest.builder().model("claude-3-opus").build();

        LlmProvider result = strategy.route(request, List.of(openai, anthropic));

        assertThat(result.name()).isEqualTo("anthropic");
    }

    @Test
    void throwsGatewayException_withEnvVarHint_whenNoProviderSupportsModel() {
        LlmProvider openai = stubProvider("openai", "gpt");
        ChatRequest request = ChatRequest.builder().model("claude-3-opus").build();

        assertThatThrownBy(() -> strategy.route(request, List.of(openai)))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("No provider configured for model: claude-3-opus")
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void throwsGatewayException_withEnvVarHint_whenProviderListEmpty() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThatThrownBy(() -> strategy.route(request, List.of()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("No provider configured for model: gpt-4o")
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void throwsGatewayException_withGenericHint_forUnknownModel() {
        ChatRequest request = ChatRequest.builder().model("unknown-model").build();

        assertThatThrownBy(() -> strategy.route(request, List.of()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("No provider configured for model: unknown-model")
                .hasMessageContaining("Check GET /v1/models");
    }

    // ---------- Reasoning-model hints ----------

    @Test
    void throwsGatewayException_withOpenAiHint_for_o1Model() {
        // Without an o1 entry in PREFIX_TO_ENV the message would fall through to the generic
        // "Check GET /v1/models" hint.
        ChatRequest request = ChatRequest.builder().model("o1-preview").build();

        assertThatThrownBy(() -> strategy.route(request, List.of()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("o1-preview")
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void throwsGatewayException_withOpenAiHint_for_o3Model() {
        ChatRequest request = ChatRequest.builder().model("o3-mini").build();

        assertThatThrownBy(() -> strategy.route(request, List.of()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("o3-mini")
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void throwsGatewayException_withOpenAiHint_for_o4Model() {
        ChatRequest request = ChatRequest.builder().model("o4-mini").build();

        assertThatThrownBy(() -> strategy.route(request, List.of()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("o4-mini")
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void throwsGatewayException_withOpenAiHint_for_chatgptModel() {
        ChatRequest request = ChatRequest.builder().model("chatgpt-4o-latest").build();

        assertThatThrownBy(() -> strategy.route(request, List.of()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("chatgpt-4o-latest")
                .hasMessageContaining("OPENAI_API_KEY");
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