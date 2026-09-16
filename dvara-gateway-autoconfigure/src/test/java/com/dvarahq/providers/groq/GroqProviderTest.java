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
package com.dvarahq.providers.groq;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GroqProviderTest {

    private MockRestServiceServer server;
    private GroqProvider          provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.groq.com/openai/v1");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new GroqProvider(builder.build());
    }

    // -------------------------------------------------------------------------
    // Routing / capability
    // -------------------------------------------------------------------------

    @Test
    void supports_groqPrefixModel_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("groq/llama-3.3-70b").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_groqMixtral_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("groq/mixtral-8x7b-32768").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_gptModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void supports_claudeModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("claude-sonnet-4-5").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void name_returnsGroq() {
        assertThat(provider.name()).isEqualTo("groq");
    }

    // -------------------------------------------------------------------------
    // chat()
    // -------------------------------------------------------------------------

    // A caller's stop sequences and seed reach the wire.
    @Test
    void chat_stopAndSeed_travelInTheOpenAiShape() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"stop\":[\"END\"]")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"seed\":42")))
              .andRespond(withSuccess("""
                      {"id":"x","object":"chat.completion","model":"m",
                       "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                       "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("groq/llama-3.3-70b")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stop(List.of("END"))
                .seed(42L)
                .build());
        server.verify();
    }

    @Test
    void chat_successResponse_returnsMappedChatResponse() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"llama-3.3-70b\"")))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-groq-abc",
                        "object": "chat.completion",
                        "created": 1704067200,
                        "model": "llama-3.3-70b",
                        "choices": [{
                          "index": 0,
                          "message": {"role": "assistant", "content": "Paris"},
                          "finish_reason": "stop"
                        }],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("groq/llama-3.3-70b")
                .messages(List.of(MultimodalMessage.user("Capital of France?")))
                .build();

        ChatResponse response = provider.chat(request);

        assertThat(response.getId()).isEqualTo("chatcmpl-groq-abc");
        assertThat(response.getModel()).isEqualTo("llama-3.3-70b");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(3);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(13);

        server.verify();
    }

    @Test
    void chat_stripsGroqPrefixFromModel() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"mixtral-8x7b-32768\"")))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-groq-xyz",
                        "object": "chat.completion",
                        "created": 1704067200,
                        "model": "mixtral-8x7b-32768",
                        "choices": [{
                          "index": 0,
                          "message": {"role": "assistant", "content": "Hello"},
                          "finish_reason": "stop"
                        }],
                        "usage": {"prompt_tokens": 5, "completion_tokens": 1, "total_tokens": 6}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("groq/mixtral-8x7b-32768")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .build());

        assertThat(response.getId()).isEqualTo("chatcmpl-groq-xyz");

        server.verify();
    }

    @Test
    void chat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("groq/llama-3.3-70b")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Groq API error")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // streamChat()
    // -------------------------------------------------------------------------

    /** Groq reports streamed usage under x_groq on the final chunk; it rides on the terminal chunk. */
    @Test
    void streamChat_terminalChunkCarriesTheUsageGroqReports() {
        String sseResponse = """
                data: {"id":"g1","object":"chat.completion.chunk","created":1,"model":"llama-3.3-70b","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

                data: {"id":"g1","object":"chat.completion.chunk","created":1,"model":"llama-3.3-70b","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"x_groq":{"id":"req","usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}}

                data: [DONE]

                """;
        server.expect(requestTo(containsString("/chat/completions")))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));
        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("groq/llama-3.3-70b").messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        while (iterator.hasNext()) chunks.add(iterator.next());
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).isDone()).isTrue();
        assertThat(chunks.get(1).getFinishReason()).isEqualTo("stop");
        assertThat(chunks.get(1).getUsage()).isNotNull();
        assertThat(chunks.get(1).getUsage().getTotalTokens()).isEqualTo(9);
    }

    @Test
    void streamChat_parsesGroqSseChunks() {
        String sseResponse = """
                data: {"id":"chatcmpl-groq-1","object":"chat.completion.chunk","created":1704067200,"model":"llama-3.3-70b","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

                data: {"id":"chatcmpl-groq-1","object":"chat.completion.chunk","created":1704067200,"model":"llama-3.3-70b","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}

                data: {"id":"chatcmpl-groq-1","object":"chat.completion.chunk","created":1704067200,"model":"llama-3.3-70b","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        ChatRequest request = ChatRequest.builder()
                .model("groq/llama-3.3-70b")
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
        assertThat(chunks.get(0).getId()).isEqualTo("chatcmpl-groq-1");
        assertThat(chunks.get(0).isDone()).isFalse();
        assertThat(chunks.get(1).getDelta()).isEqualTo(" world");
        assertThat(chunks.get(2).getFinishReason()).isEqualTo("stop");
        assertThat(chunks.get(2).isDone()).isTrue();

        server.verify();
    }

    @Test
    void streamChat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.streamChat(ChatRequest.builder()
                        .model("groq/llama-3.3-70b")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .stream(true)
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Groq streaming error");

        server.verify();
    }

    // -------------------------------------------------------------------------
    // capabilities()
    // -------------------------------------------------------------------------

    @Test
    void capabilities_returnsExpectedValues() {
        ProviderCapabilities caps = provider.capabilities();

        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.supportsVision()).isFalse();
        // Groq's API supports tool calls on every hosted model, but this provider does not
        // serialize tool definitions, so the flag says what actually flows end to end. The
        // dispatcher's capability filter relies on it to keep a request carrying tools off this
        // provider.
        assertThat(caps.supportsToolCalls()).isFalse();
        assertThat(caps.supportsStreamingToolCalls()).isFalse();
        assertThat(caps.supportsStructuredOutputs()).isFalse();
        assertThat(caps.supportsJsonMode()).isTrue();
        assertThat(caps.maxContextTokens()).isEqualTo(131_072);
    }

    // -------------------------------------------------------------------------
    // response_format
    // -------------------------------------------------------------------------

    @Test
    void chat_jsonObjectFormat_passesResponseFormatInBody() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"response_format\"")))
              .andExpect(content().string(containsString("\"json_object\"")))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-groq-rf1", "object": "chat.completion", "created": 1704067200,
                        "model": "llama-3.3-70b",
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": "{\\"key\\":\\"value\\"}"}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("groq/llama-3.3-70b")
                .messages(List.of(MultimodalMessage.user("Return JSON")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getId()).isEqualTo("chatcmpl-groq-rf1");

        server.verify();
    }

    @Test
    void chat_jsonSchemaFormat_throwsUnsupportedResponseFormat() {
        ChatRequest request = ChatRequest.builder()
                .model("groq/llama-3.3-70b")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonSchema("my_schema",
                        Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))), true))
                .build();

        assertThatThrownBy(() -> provider.chat(request))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("does not support json_schema")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("UNSUPPORTED_RESPONSE_FORMAT"));
    }

    // -------------------------------------------------------------------------
    // listModels
    // -------------------------------------------------------------------------

    @Test
    void listModels_returnsModelsWithGroqPrefix() {
        server.expect(requestTo(containsString("/openai/v1/models")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "object": "list",
                          "data": [
                            {"id": "llama-3.3-70b-versatile", "owned_by": "groq", "created": 1700000000}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var models = provider.listModels();
        assertThat(models).hasSize(1);
        assertThat(models.get(0).id()).isEqualTo("groq/llama-3.3-70b-versatile");
        assertThat(models.get(0).ownedBy()).isEqualTo("groq");
        server.verify();
    }

    /** The base URL already ends in /openai/v1, so the model list is requested at /models relative to it, not /openai/v1/models. */
    @Test
    void listModels_requestsTheModelListOnceUnderTheVersionedBase() {
        server.expect(requestTo("https://api.groq.com/openai/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"object": "list", "data": [{"id": "llama-3.3-70b-versatile", "owned_by": "groq", "created": 1700000000}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.listModels()).hasSize(1);
        server.verify();
    }

    @Test
    void listModels_throwsOnError() {
        server.expect(requestTo(containsString("/openai/v1/models")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.listModels())
                .isInstanceOf(Exception.class);
        server.verify();
    }

    // -------------------------------------------------------------------------
    // Unsupported content-block rejection
    // -------------------------------------------------------------------------

    @Test
    void chat_rejectsImageBlockWithUnsupportedCapability() {
        ChatRequest request = ChatRequest.builder()
                .model("groq/llama-3.3-70b-versatile")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(
                                new com.dvarahq.core.model.ContentBlock.TextBlock("Describe this image."),
                                new com.dvarahq.core.model.ContentBlock.ImageBlock("image/png", "BASE64DATA")))
                        .build()))
                .build();

        assertThatThrownBy(() -> provider.chat(request))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("vision")
                .extracting("code").isEqualTo("UNSUPPORTED_CAPABILITY");
    }

    @Test
    void chat_relaysPenaltiesAndMessageName() {
        // frequency_penalty, presence_penalty and a message's name all reach the provider.
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.frequency_penalty").value(0.5))
              .andExpect(jsonPath("$.presence_penalty").value(-0.25))
              .andExpect(jsonPath("$.messages[0].name").value("alice"))
              .andRespond(withSuccess("""
                      {"id": "c-1", "object": "chat.completion", "created": 1704067200, "model": "groq/llama-3.3-70b",
                       "choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}],
                       "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("groq/llama-3.3-70b")
                .messages(List.of(MultimodalMessage.builder().role("user")
                        .content(List.of(new ContentBlock.TextBlock("hi"))).name("alice").build()))
                .frequencyPenalty(0.5)
                .presencePenalty(-0.25)
                .build());

        server.verify();
    }
}
