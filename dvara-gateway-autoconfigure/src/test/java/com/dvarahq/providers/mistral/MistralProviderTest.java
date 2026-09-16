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
package com.dvarahq.providers.mistral;

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

class MistralProviderTest {

    private MockRestServiceServer server;
    private MistralProvider        provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.mistral.ai/v1");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new MistralProvider(builder.build());
    }

    // -------------------------------------------------------------------------
    // Routing / capability
    // -------------------------------------------------------------------------

    @Test
    void supports_mistralLargeLatest_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("mistral-large-latest").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_mistralSmallLatest_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("mistral-small-latest").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_gpt4_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("gpt-4").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void supports_claudeModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("claude-sonnet-4-5").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void name_returnsMistral() {
        assertThat(provider.name()).isEqualTo("mistral");
    }

    // -------------------------------------------------------------------------
    // chat()
    // -------------------------------------------------------------------------

    // A caller's stop sequences and seed reach the wire.
    @Test
    void chat_stopAndSeed_travelAsStopAndRandomSeed() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"stop\":[\"END\"]")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"random_seed\":42")))
              .andRespond(withSuccess("""
                      {"id":"x","object":"chat.completion","model":"m",
                       "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                       "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("mistral-large-latest")
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
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-mistral-abc",
                        "object": "chat.completion",
                        "created": 1704067200,
                        "model": "mistral-large-latest",
                        "choices": [{
                          "index": 0,
                          "message": {"role": "assistant", "content": "Paris"},
                          "finish_reason": "stop"
                        }],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 3, "total_tokens": 13}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("mistral-large-latest")
                .messages(List.of(MultimodalMessage.user("Capital of France?")))
                .build();

        ChatResponse response = provider.chat(request);

        assertThat(response.getId()).isEqualTo("chatcmpl-mistral-abc");
        assertThat(response.getModel()).isEqualTo("mistral-large-latest");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(3);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(13);

        server.verify();
    }

    @Test
    void chat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("mistral-large-latest")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Mistral API error")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // streamChat()
    // -------------------------------------------------------------------------

    /** Mistral's final stream chunk carries a usage block; it rides on the terminal chunk. */
    @Test
    void streamChat_terminalChunkCarriesTheUsageMistralReports() {
        String sseResponse = """
                data: {"id":"m1","object":"chat.completion.chunk","created":1,"model":"mistral-large-latest","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":null}]}

                data: {"id":"m1","object":"chat.completion.chunk","created":1,"model":"mistral-large-latest","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}

                data: [DONE]

                """;
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(content().string(containsString("\"stream_options\":{\"include_usage\":true}")))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));
        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("mistral-large-latest").messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        while (iterator.hasNext()) chunks.add(iterator.next());
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).isDone()).isTrue();
        assertThat(chunks.get(1).getUsage()).isNotNull();
        assertThat(chunks.get(1).getUsage().getTotalTokens()).isEqualTo(9);
    }

    @Test
    void streamChat_parsesMistralSseChunks() {
        String sseResponse = """
                data: {"id":"chatcmpl-m1","object":"chat.completion.chunk","created":1704067200,"model":"mistral-large-latest","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

                data: {"id":"chatcmpl-m1","object":"chat.completion.chunk","created":1704067200,"model":"mistral-large-latest","choices":[{"index":0,"delta":{"content":" world"},"finish_reason":null}]}

                data: {"id":"chatcmpl-m1","object":"chat.completion.chunk","created":1704067200,"model":"mistral-large-latest","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        ChatRequest request = ChatRequest.builder()
                .model("mistral-large-latest")
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
        assertThat(chunks.get(0).getId()).isEqualTo("chatcmpl-m1");
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
                        .model("mistral-large-latest")
                        .messages(List.of(MultimodalMessage.user("Hi")))
                        .stream(true)
                        .build()))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // response_format
    // -------------------------------------------------------------------------

    @Test
    void chat_jsonSchemaFormat_passesResponseFormatInBody() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"json_schema\"")))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-mistral-rf1", "object": "chat.completion", "created": 1704067200,
                        "model": "mistral-large-latest",
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": "{\\"name\\":\\"test\\"}"}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("mistral-large-latest")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonSchema("my_schema",
                        Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))), true))
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getId()).isEqualTo("chatcmpl-mistral-rf1");

        server.verify();
    }

    // -------------------------------------------------------------------------
    // capabilities()
    // -------------------------------------------------------------------------

    // ---- tools are relayed, the way the OpenAI-compatible providers relay them ----

    private static final String TOOL_CALL_RESPONSE = """
            {"id":"m-1","object":"chat.completion","created":1,"model":"mistral-large-latest",
             "choices":[{"index":0,"message":{"role":"assistant","content":"",
               "tool_calls":[{"id":"call_1","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Paris\\"}"}}]},
               "finish_reason":"tool_calls"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
            """;

    private static ChatRequest withTools(Object toolChoice) {
        return ChatRequest.builder()
                .model("mistral-large-latest")
                .messages(List.of(MultimodalMessage.user("weather in Paris?")))
                .tools(List.of(com.dvarahq.core.model.ToolDefinition.builder()
                        .name("get_weather").description("Get weather")
                        .parameters(Map.of("type", "object")).build()))
                .toolChoice(toolChoice)
                .build();
    }

    /** Definitions and tool_choice go upstream; the model's tool_calls come back as internal tool calls. */
    @Test
    void chat_withTools_sendsDefinitionsAndMapsToolCallsBack() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"tools\":[{")))
              .andExpect(content().string(containsString("\"type\":\"function\"")))
              .andExpect(content().string(containsString("\"name\":\"get_weather\"")))
              .andExpect(content().string(containsString("\"description\":\"Get weather\"")))
              .andExpect(content().string(containsString("\"tool_choice\":\"auto\"")))
              .andRespond(withSuccess(TOOL_CALL_RESPONSE, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(withTools("auto"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("tool_calls");
        List<com.dvarahq.core.model.ToolCall> calls = response.getChoices().get(0).getMessage().getToolCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getId()).isEqualTo("call_1");
        assertThat(calls.get(0).getName()).isEqualTo("get_weather");
        assertThat(calls.get(0).getArguments()).isEqualTo("{\"city\":\"Paris\"}");
        server.verify();
    }

    /** OpenAI's forced choice is `required`; Mistral spells it `any`. */
    @Test
    void chat_toolChoiceRequired_isSentAsAny() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(content().string(containsString("\"tool_choice\":\"any\"")))
              .andRespond(withSuccess(TOOL_CALL_RESPONSE, MediaType.APPLICATION_JSON));
        provider.chat(withTools("required"));
        server.verify();
    }

    /** The second turn: the assistant's tool_calls and the tool's result travel with their ids. */
    @Test
    void chat_toolResultTurn_carriesToolCallsAndToolCallId() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(content().string(containsString("\"tool_calls\":[{")))
              .andExpect(content().string(containsString("\"id\":\"call_1\"")))
              .andExpect(content().string(containsString("\"role\":\"tool\"")))
              .andExpect(content().string(containsString("\"tool_call_id\":\"call_1\"")))
              .andRespond(withSuccess("""
                      {"id":"m-2","object":"chat.completion","created":1,"model":"mistral-large-latest",
                       "choices":[{"index":0,"message":{"role":"assistant","content":"Sunny, 21C"},"finish_reason":"stop"}],
                       "usage":{"prompt_tokens":20,"completion_tokens":4,"total_tokens":24}}
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("mistral-large-latest")
                .messages(List.of(
                        MultimodalMessage.user("weather in Paris?"),
                        MultimodalMessage.builder().role("assistant")
                                .content(List.of(new com.dvarahq.core.model.ContentBlock.TextBlock("")))
                                .toolCalls(List.of(com.dvarahq.core.model.ToolCall.builder()
                                        .id("call_1").name("get_weather").arguments("{\"city\":\"Paris\"}").build()))
                                .build(),
                        MultimodalMessage.toolResult("call_1", "{\"temp\":21}")))
                .tools(withTools("auto").getTools())
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getChoices().get(0).getMessage().getToolCalls()).isNullOrEmpty();
        server.verify();
    }

    @Test
    void capabilities_returnsExpectedValues() {
        ProviderCapabilities caps = provider.capabilities();

        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.supportsVision()).isFalse();
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
    void listModels_returnsModelsFromApi() {
        server.expect(requestTo(containsString("/v1/models")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "object": "list",
                          "data": [
                            {"id": "mistral-large-latest", "owned_by": "mistralai", "created": 1700000000},
                            {"id": "mistral-small-latest", "owned_by": "mistralai", "created": 1700000001}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var models = provider.listModels();
        assertThat(models).hasSize(2);
        assertThat(models.get(0).id()).isEqualTo("mistral-large-latest");
        assertThat(models.get(0).ownedBy()).isEqualTo("mistralai");
        server.verify();
    }

    /** The base URL already ends in /v1, so the model list is requested at /models relative to it, not /v1/models. */
    @Test
    void listModels_requestsTheModelListOnceUnderTheVersionedBase() {
        server.expect(requestTo("https://api.mistral.ai/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"object": "list", "data": [{"id": "mistral-large-latest", "owned_by": "mistralai", "created": 1700000000}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.listModels()).hasSize(1);
        server.verify();
    }

    @Test
    void listModels_throwsOnError() {
        server.expect(requestTo(containsString("/v1/models")))
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
                      {"id": "c-1", "object": "chat.completion", "created": 1704067200, "model": "mistral-large-latest",
                       "choices": [{"index": 0, "message": {"role": "assistant", "content": "ok"}, "finish_reason": "stop"}],
                       "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("mistral-large-latest")
                .messages(List.of(MultimodalMessage.builder().role("user")
                        .content(List.of(new ContentBlock.TextBlock("hi"))).name("alice").build()))
                .frequencyPenalty(0.5)
                .presencePenalty(-0.25)
                .build());

        server.verify();
    }

    /**
     * An image is refused, not stringified into the prompt. This provider declares no vision, so
     * an ImageBlock must never be turned into text and sent to the model; Cohere, Groq and Ollama
     * refuse an image the same way.
     *
     * <p>No request is expected, so {@code MockRestServiceServer} verifying zero expectations is part
     * of the assertion: the refusal happens before anything is sent.
     */
    @Test
    void imageInput_isRefusedBeforeAnythingIsSent() {
        ChatRequest request = ChatRequest.builder()
                .model("mistral-large-latest")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock("what is in this picture?"),
                                new ContentBlock.ImageBlock("image/png", "aGVsbG8=")))
                        .build()))
                .build();

        assertThatThrownBy(() -> provider.chat(request))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("does not support image input")
                .satisfies(e -> assertThat(((GatewayException) e).getCode())
                        .isEqualTo("UNSUPPORTED_CAPABILITY"));

        server.verify();
    }
}
