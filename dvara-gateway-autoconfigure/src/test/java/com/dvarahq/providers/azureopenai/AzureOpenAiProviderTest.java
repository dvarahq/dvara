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
package com.dvarahq.providers.azureopenai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AzureOpenAiProviderTest {

    private MockRestServiceServer server;
    private AzureOpenAiProvider   provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://my-resource.openai.azure.com/openai");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new AzureOpenAiProvider(builder.build());
    }

    // -------------------------------------------------------------------------
    // Routing / capability
    // -------------------------------------------------------------------------

    @Test
    void supports_azurePrefixModel_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("azure/gpt-4o").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_azureGpt4oMini_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("azure/gpt-4o-mini").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_plainGptModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void supports_claudeModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("claude-sonnet-4-5").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void name_returnsAzureOpenai() {
        assertThat(provider.name()).isEqualTo("azure-openai");
    }

    // -------------------------------------------------------------------------
    // chat()
    // -------------------------------------------------------------------------

    @Test
    void chat_topP_mapsToOpenAiCompatibleBody_top_p() {
        // The OpenAI-compatible family (via AbstractOpenAiCompatibleProvider) sends top_p.
        server.expect(requestTo(containsString("/deployments/gpt-4o/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"top_p\":0.9")))
              .andRespond(withSuccess("""
                      {"id":"x","object":"chat.completion","model":"gpt-4o",
                       "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                       "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("azure/gpt-4o")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .topP(0.9)
                .build());
    }

    // A caller's stop sequences and seed must reach the upstream body.
    @Test
    void chat_stopAndSeed_travelInTheOpenAiShape() {
        server.expect(requestTo(containsString("/deployments/gpt-4o/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"stop\":[\"END\"]")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"seed\":42")))
              .andRespond(withSuccess("""
                      {"id":"x","object":"chat.completion","model":"m",
                       "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                       "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("azure/gpt-4o")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stop(List.of("END"))
                .seed(42L)
                .build());
        server.verify();
    }

    @Test
    void chat_successResponse_returnsMappedChatResponse() {
        server.expect(requestTo(containsString("/deployments/gpt-4o/chat/completions")))
              .andExpect(requestTo(containsString("api-version=2024-10-21")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-abc",
                        "object": "chat.completion",
                        "created": 1704067200,
                        "model": "gpt-4o",
                        "choices": [{
                          "index": 0,
                          "message": {"role": "assistant", "content": "Paris"},
                          "finish_reason": "stop"
                        }],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("azure/gpt-4o")
                .messages(List.of(MultimodalMessage.user("Capital of France?")))
                .build();

        ChatResponse response = provider.chat(request);

        assertThat(response.getId()).isEqualTo("chatcmpl-abc");
        assertThat(response.getModel()).isEqualTo("gpt-4o");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(3);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(13);

        server.verify();
    }

    @Test
    void chat_assistantMessageContentMapped() {
        server.expect(requestTo(containsString("/deployments/gpt-4o/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-xyz",
                        "object": "chat.completion",
                        "created": 1704067200,
                        "model": "gpt-4o",
                        "choices": [{
                          "index": 0,
                          "message": {"role": "assistant", "content": "Hello there!"},
                          "finish_reason": "stop"
                        }],
                        "usage": {"prompt_tokens": 5, "completion_tokens": 4, "total_tokens": 9}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("azure/gpt-4o")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .build());

        MultimodalMessage message = response.getChoices().get(0).getMessage();
        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getContent()).hasSize(1);

        server.verify();
    }

    @Test
    void chat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/deployments/gpt-4o/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("azure/gpt-4o")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Azure OpenAI API error")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // streamChat()
    // -------------------------------------------------------------------------

    @Test
    void streamChat_parsesAzureOpenAiSseChunks() {
        String sseResponse = """
                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1704067200,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1704067200,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1704067200,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        server.expect(requestTo(containsString("/deployments/gpt-4o/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        ChatRequest request = ChatRequest.builder()
                .model("azure/gpt-4o")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stream(true)
                .build();

        Iterator<SseChunk> iterator = provider.streamChat(request);
        List<SseChunk> chunks = new ArrayList<>();
        while (iterator.hasNext()) {
            chunks.add(iterator.next());
        }

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getDelta()).isEqualTo("Hello");
        assertThat(chunks.get(0).getId()).isEqualTo("chatcmpl-1");
        assertThat(chunks.get(0).isDone()).isFalse();
        assertThat(chunks.get(1).getDelta()).isEqualTo(" world");
        assertThat(chunks.get(2).getFinishReason()).isEqualTo("stop");
        assertThat(chunks.get(2).isDone()).isTrue();

        server.verify();
    }

    @Test
    void streamChat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/deployments/gpt-4o/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.streamChat(ChatRequest.builder()
                        .model("azure/gpt-4o")
                        .messages(List.of(MultimodalMessage.user("Hi")))
                        .stream(true)
                        .build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // capabilities()
    // -------------------------------------------------------------------------

    @Test
    void capabilities_returnsExpectedValues() {
        ProviderCapabilities caps = provider.capabilities();

        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.supportsVision()).isTrue();
        assertThat(caps.supportsToolCalls()).isTrue();
        assertThat(caps.supportsStreamingToolCalls()).isTrue();
        assertThat(caps.supportsStructuredOutputs()).isTrue();
        assertThat(caps.supportsJsonMode()).isTrue();
        assertThat(caps.maxContextTokens()).isEqualTo(128_000);
    }

    // -------------------------------------------------------------------------
    // listModels
    // -------------------------------------------------------------------------

    @Test
    void listModels_returnsModelsWithAzurePrefix() {
        server.expect(requestTo(containsString("/models?api-version=")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "object": "list",
                          "data": [
                            {"id": "gpt-4o", "owned_by": "azure", "created": 1700000000}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var models = provider.listModels();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).id()).isEqualTo("azure/gpt-4o");
        assertThat(models.get(0).ownedBy()).isEqualTo("azure");
        server.verify();
    }

    @Test
    void listModels_throwsOnError() {
        server.expect(requestTo(containsString("/models")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.listModels())
                .isInstanceOf(Exception.class);
        server.verify();
    }
}