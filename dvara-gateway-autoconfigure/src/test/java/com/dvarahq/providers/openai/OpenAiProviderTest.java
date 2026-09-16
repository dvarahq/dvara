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
package com.dvarahq.providers.openai;

import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

class OpenAiProviderTest {

    private MockRestServiceServer server;
    private OpenAiProvider        provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.openai.com/v1");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new OpenAiProvider(builder.build());
    }

    // -------------------------------------------------------------------------
    // Routing / capability
    // -------------------------------------------------------------------------

    @Test
    void supports_gptPrefixModel_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_gpt4oMini_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o-mini").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_claudeModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("claude-sonnet-4-5").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    // ---------- Reasoning models (o1, o3, o4) ----------

    @Test
    void supports_o1Preview_returnsTrue() {
        // o-series reasoning models route under the default prefix matcher without a custom route.
        ChatRequest request = ChatRequest.builder().model("o1-preview").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_o1Mini_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("o1-mini").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_o3Mini_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("o3-mini").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_o4Mini_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("o4-mini").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_chatgpt4oLatest_returnsTrue() {
        // chatgpt-4o-latest is a real OpenAI alias model that does not start
        // with "gpt" — the chatgpt prefix exists specifically for it.
        ChatRequest request = ChatRequest.builder().model("chatgpt-4o-latest").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    // ---------- Collision guards ----------

    @Test
    void supports_ollamaModel_returnsFalse() {
        // The OpenAI prefix list must not swallow Ollama models: the prefixes are o1, o3 and o4,
        // not a bare "o".
        ChatRequest request = ChatRequest.builder().model("ollama/llama3.2").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void supports_mistralLargeLatest_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("mistral-large-latest").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void supportsEmbedding_textEmbeddingModel_returnsTrue() {
        assertThat(provider.supportsEmbedding("text-embedding-ada-002")).isTrue();
        assertThat(provider.supportsEmbedding("text-embedding-3-small")).isTrue();
    }

    @Test
    void supportsEmbedding_nonEmbeddingModel_returnsFalse() {
        assertThat(provider.supportsEmbedding("gpt-4o")).isFalse();
        assertThat(provider.supportsEmbedding("claude-sonnet-4-5")).isFalse();
    }

    @Test
    void supportsEmbedding_nullModel_returnsFalse() {
        assertThat(provider.supportsEmbedding(null)).isFalse();
    }

    @Test
    void name_returnsOpenai() {
        assertThat(provider.name()).isEqualTo("openai");
    }

    // -------------------------------------------------------------------------
    // chat()
    // -------------------------------------------------------------------------

    @Test
    void chat_successResponse_returnsMappedChatResponse() {
        server.expect(requestTo(containsString("/chat/completions")))
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
                .model("gpt-4o")
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
    void chat_withTools_sendsNativeToolsAndParsesToolCalls() {
        // OpenAI-compatible family: tools go out as native {type:function,...},
        // and the model's tool_calls come back on the internal message.
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.tools[0].type").value("function"))
              .andExpect(jsonPath("$.tools[0].function.name").value("get_weather"))
              .andExpect(jsonPath("$.tool_choice").value("auto"))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-t",
                        "object": "chat.completion",
                        "created": 1704067200,
                        "model": "gpt-4o",
                        "choices": [{
                          "index": 0,
                          "message": {
                            "role": "assistant",
                            "content": null,
                            "tool_calls": [{
                              "id": "call_1",
                              "type": "function",
                              "function": {"name": "get_weather", "arguments": "{\\"city\\":\\"Paris\\"}"}
                            }]
                          },
                          "finish_reason": "tool_calls"
                        }],
                        "usage": {"prompt_tokens": 5, "completion_tokens": 3, "total_tokens": 8}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("weather in Paris?")))
                .tools(List.of(ToolDefinition.builder()
                        .name("get_weather").description("Get the weather")
                        .parameters(Map.of("type", "object")).build()))
                .toolChoice("auto")
                .build());

        server.verify();
        var calls = response.getChoices().get(0).getMessage().getToolCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getId()).isEqualTo("call_1");
        assertThat(calls.get(0).getName()).isEqualTo("get_weather");
        assertThat(calls.get(0).getArguments()).contains("Paris");
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("tool_calls");
    }

    @Test
    void chat_toolResultMessage_serializesToolCallId() {
        // a tool-role result carries tool_call_id back to the provider.
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(jsonPath("$.messages[-1:].role").value("tool"))
              .andExpect(jsonPath("$.messages[-1:].tool_call_id").value("call_1"))
              .andRespond(withSuccess("""
                      {"id":"c","object":"chat.completion","created":1,"model":"gpt-4o",
                       "choices":[{"index":0,"message":{"role":"assistant","content":"It is sunny."},
                       "finish_reason":"stop"}],
                       "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.user("weather?"),
                        MultimodalMessage.toolResult("call_1", "sunny, 21C")))
                .build());

        server.verify();
    }

    @Test
    void chat_assistantMessageContentMapped() {
        server.expect(requestTo(containsString("/chat/completions")))
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
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .build());

        MultimodalMessage message = response.getChoices().get(0).getMessage();
        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getContent()).hasSize(1);

        server.verify();
    }

    @Test
    void chat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("gpt-4o")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("OpenAI API error")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    @Test
    void chat_unauthorizedError_throwsGatewayException() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("gpt-4o")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid API key")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // streamChat()
    // -------------------------------------------------------------------------

    @Test
    void streamChat_parsesOpenAiSseChunks() {
        String sseResponse = """
                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1704067200,"model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1704067200,"model":"gpt-4o","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}

                data: {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1704067200,"model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
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
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.streamChat(ChatRequest.builder()
                        .model("gpt-4o")
                        .messages(List.of(MultimodalMessage.user("Hi")))
                        .stream(true)
                        .build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // embed()
    // -------------------------------------------------------------------------

    @Test
    void embed_successResponse_returnsMappedEmbeddingResponse() {
        server.expect(requestTo(containsString("/embeddings")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                      {
                        "object": "list",
                        "model": "text-embedding-ada-002",
                        "data": [{"object": "embedding", "index": 0, "embedding": [0.1, -0.05, 0.23]}],
                        "usage": {"prompt_tokens": 5, "total_tokens": 5}
                      }
                      """, MediaType.APPLICATION_JSON));

        EmbeddingRequest request = EmbeddingRequest.builder()
                .model("text-embedding-ada-002")
                .input("DVARA governance platform")
                .build();

        EmbeddingResponse response = provider.embed(request);

        assertThat(response.getModel()).isEqualTo("text-embedding-ada-002");
        assertThat(response.getData()).hasSize(1);
        assertThat(response.getData().get(0).getIndex()).isEqualTo(0);
        assertThat(response.getData().get(0).getEmbedding()).containsExactly(0.1, -0.05, 0.23);
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(5);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(5);

        server.verify();
    }

    @Test
    void embed_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/embeddings")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.embed(EmbeddingRequest.builder()
                        .model("text-embedding-ada-002")
                        .input("hello")
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
                        "id": "chatcmpl-rf1", "object": "chat.completion", "created": 1704067200,
                        "model": "gpt-4o",
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": "{\\"key\\":\\"value\\"}"}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("Return JSON")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getId()).isEqualTo("chatcmpl-rf1");

        server.verify();
    }

    @Test
    void chat_jsonSchemaFormat_passesFullSchemaInBody() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"json_schema\"")))
              .andExpect(content().string(containsString("\"my_schema\"")))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-rf2", "object": "chat.completion", "created": 1704067200,
                        "model": "gpt-4o",
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": "{\\"name\\":\\"John\\"}"}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonSchema("my_schema",
                        Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))), true))
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getId()).isEqualTo("chatcmpl-rf2");

        server.verify();
    }

    // -------------------------------------------------------------------------
    // listModels
    // -------------------------------------------------------------------------

    @Test
    void listModels_returnsModelsFromApi() {
        server.expect(requestTo(containsString("/models")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "object": "list",
                          "data": [
                            {"id": "gpt-4o", "owned_by": "openai", "created": 1700000000},
                            {"id": "gpt-4o-mini", "owned_by": "system", "created": 1700000001}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var models = provider.listModels();
        assertThat(models).hasSize(2);
        assertThat(models.get(0).id()).isEqualTo("gpt-4o");
        assertThat(models.get(0).ownedBy()).isEqualTo("openai");
        assertThat(models.get(0).created()).isEqualTo(1700000000L);
        assertThat(models.get(1).id()).isEqualTo("gpt-4o-mini");
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

    @Test
    void chat_relaysPenaltiesAndMessageName() {
        // frequency_penalty, presence_penalty and a message's name all reach the provider.
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(jsonPath("$.frequency_penalty").value(0.5))
              .andExpect(jsonPath("$.presence_penalty").value(-0.25))
              .andExpect(jsonPath("$.messages[0].name").value("alice"))
              .andRespond(withSuccess("""
                      {"id": "c-1", "object": "chat.completion", "created": 1704067200, "model": "gpt-4o",
                       "choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}],
                       "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder().role("user")
                        .content(List.of(new ContentBlock.TextBlock("hi"))).name("alice").build()))
                .frequencyPenalty(0.5)
                .presencePenalty(-0.25)
                .build());

        server.verify();
    }
}
