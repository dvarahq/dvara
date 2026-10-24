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
package com.dvarahq.providers.cohere;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CohereProviderTest {

    private MockRestServiceServer server;
    private CohereProvider        provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.cohere.com/v2");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new CohereProvider(builder.build());
    }

    // -------------------------------------------------------------------------
    // Routing / capability
    // -------------------------------------------------------------------------

    @Test
    void supports_commandRPlus_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("command-r-plus").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_commandR_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("command-r").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_commandLight_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("command-light").messages(List.of()).build();
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
    void name_returnsCohere() {
        assertThat(provider.name()).isEqualTo("cohere");
    }

    // -------------------------------------------------------------------------
    // chat()
    // -------------------------------------------------------------------------

    @Test
    void chat_topP_mapsToCohereNucleusParam_p() {
        // Cohere v2 chat uses `p` (not `top_p`) for nucleus sampling.
        server.expect(requestTo(containsString("/chat")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"p\":0.9")))
              .andRespond(withSuccess("""
                      {"id":"x","message":{"role":"assistant","content":[{"type":"text","text":"hi"}]},
                       "finish_reason":"COMPLETE",
                       "usage":{"billed_units":{"input_tokens":1,"output_tokens":1},"tokens":{"input_tokens":1,"output_tokens":1}}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("command-r-plus")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .topP(0.9)
                .build());
    }

    // A caller's stop sequences and seed reach the wire.
    @Test
    void chat_stopAndSeed_travelAsStopSequencesAndSeed() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"stop_sequences\":[\"END\"]")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"seed\":42")))
              .andRespond(withSuccess("""
                      {"id":"x","message":{"role":"assistant","content":[{"type":"text","text":"hi"}]},
                       "finish_reason":"COMPLETE",
                       "usage":{"billed_units":{"input_tokens":1,"output_tokens":1},"tokens":{"input_tokens":1,"output_tokens":1}}}
                      """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("command-r-plus")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stop(List.of("END"))
                .seed(42L)
                .build());
        server.verify();
    }

    @Test
    void chat_successResponse_returnsMappedChatResponse() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"model\":\"command-r-plus\"")))
              .andRespond(withSuccess("""
                      {
                        "id": "abc123",
                        "message": {
                          "role": "assistant",
                          "content": [{"type": "text", "text": "Hello!"}]
                        },
                        "finish_reason": "COMPLETE",
                        "usage": {
                          "billed_units": {"input_tokens": 10, "output_tokens": 5},
                          "tokens": {"input_tokens": 10, "output_tokens": 5}
                        }
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("command-r-plus")
                .messages(List.of(MultimodalMessage.user("Hello")))
                .build();

        ChatResponse response = provider.chat(request);

        assertThat(response.getId()).isEqualTo("abc123");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(5);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(15);

        server.verify();
    }

    @Test
    void chat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("command-r-plus")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Cohere API error")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    @Test
    void chat_maxTokensFinishReason_mapsToLength() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                      {
                        "id": "abc456",
                        "message": {
                          "role": "assistant",
                          "content": [{"type": "text", "text": "Truncated response"}]
                        },
                        "finish_reason": "MAX_TOKENS",
                        "usage": {
                          "tokens": {"input_tokens": 10, "output_tokens": 100}
                        }
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("command-r")
                .messages(List.of(MultimodalMessage.user("Tell me a long story")))
                .maxTokens(100)
                .build());

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("length");

        server.verify();
    }

    // -------------------------------------------------------------------------
    // response_format rejection
    // -------------------------------------------------------------------------

    @Test
    void chat_jsonObjectFormat_throwsUnsupported() {
        ChatRequest request = ChatRequest.builder()
                .model("command-r-plus")
                .messages(List.of(MultimodalMessage.user("Return JSON")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        assertThatThrownBy(() -> provider.chat(request))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode())
                        .isEqualTo("UNSUPPORTED_RESPONSE_FORMAT"));
    }

    @Test
    void chat_jsonSchemaFormat_throwsUnsupported() {
        ChatRequest request = ChatRequest.builder()
                .model("command-r-plus")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonSchema("test",
                        Map.of("type", "object"), true))
                .build();

        assertThatThrownBy(() -> provider.chat(request))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode())
                        .isEqualTo("UNSUPPORTED_RESPONSE_FORMAT"));
    }

    // -------------------------------------------------------------------------
    // streamChat()
    // -------------------------------------------------------------------------

    @Test
    void streamChat_parsesCohereSseChunks() {
        String sseResponse = """
                data: {"type":"content-delta","delta":{"message":{"content":{"text":"Hello"}}}}

                data: {"type":"content-delta","delta":{"message":{"content":{"text":" world"}}}}

                data: {"type":"message-end","delta":{"finish_reason":"COMPLETE","usage":{"billed_units":{"input_tokens":10,"output_tokens":5}}}}

                """;

        server.expect(requestTo(containsString("/chat")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"stream\":true")))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        ChatRequest request = ChatRequest.builder()
                .model("command-r-plus")
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
        assertThat(chunks.get(0).isDone()).isFalse();
        assertThat(chunks.get(1).getDelta()).isEqualTo(" world");
        assertThat(chunks.get(1).isDone()).isFalse();
        assertThat(chunks.get(2).getFinishReason()).isEqualTo("stop");
        assertThat(chunks.get(2).isDone()).isTrue();

        server.verify();
    }

    @Test
    void streamChat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.streamChat(ChatRequest.builder()
                        .model("command-r-plus")
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
        // Images and tools both flow end to end now, streamed tool calls included; the dispatcher's
        // capability filter reads these.
        assertThat(caps.supportsVision()).isTrue();
        assertThat(caps.supportsToolCalls()).isTrue();
        assertThat(caps.supportsStreamingToolCalls()).isTrue();
        assertThat(caps.supportsStructuredOutputs()).isFalse();
        assertThat(caps.supportsJsonMode()).isFalse();
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
                          "models": [
                            {"name": "command-r-plus"},
                            {"name": "command-r"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var models = provider.listModels();
        assertThat(models).hasSize(2);
        assertThat(models.get(0).id()).isEqualTo("command-r-plus");
        assertThat(models.get(0).ownedBy()).isEqualTo("cohere");
        server.verify();
    }

    /** Chat is v2 and the model list is v1, so the request swaps the version rather than appending /v1 to the /v2 base. */
    @Test
    void listModels_requestsTheV1ModelListBesideTheV2Base() {
        server.expect(requestTo("https://api.cohere.com/v1/models"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"models": [{"name": "command-r"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.listModels()).hasSize(1);
        server.verify();
    }

    /** A base URL with a path in front of the version keeps that path. */
    @Test
    void listModels_behindAProxyPath_keepsThePathAndSwapsOnlyTheVersion() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://proxy.example.com/cohere/v2");
        MockRestServiceServer proxied = MockRestServiceServer.bindTo(builder).build();
        proxied.expect(requestTo("https://proxy.example.com/cohere/v1/models"))
                .andRespond(withSuccess("""
                        {"models": [{"name": "command-r"}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(new CohereProvider(builder.build()).listModels()).hasSize(1);
        proxied.verify();
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

    // -------------------------------------------------------------------------
    // Unsupported content-block rejection
    // -------------------------------------------------------------------------

    private static final String OK = """
            {"id":"c-1","finish_reason":"COMPLETE","message":{"role":"assistant","content":[{"type":"text","text":"A cat."}]},
             "usage":{"billed_units":{"input_tokens":5,"output_tokens":2},"tokens":{"input_tokens":900,"output_tokens":3}}}
            """;

    /** Text and images go as Cohere's content parts: image_url with url (base64 as a data: URL) and detail. */
    @Test
    void chat_imageBlocks_goAsImageUrlParts() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(jsonPath("$.model").value("command-a-vision-07-2025"))
              .andExpect(jsonPath("$.messages[0].content[0].type").value("text"))
              .andExpect(jsonPath("$.messages[0].content[0].text").value("Describe this image."))
              .andExpect(jsonPath("$.messages[0].content[1].type").value("image_url"))
              .andExpect(jsonPath("$.messages[0].content[1].image_url.url").value("data:image/png;base64,BASE64DATA"))
              .andExpect(jsonPath("$.messages[0].content[1].image_url.detail").doesNotExist())
              .andExpect(jsonPath("$.messages[0].content[2].image_url.url").value("https://example.com/cat.webp"))
              .andExpect(jsonPath("$.messages[0].content[2].image_url.detail").value("high"))
              .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("command-a-vision-07-2025")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(
                                new com.dvarahq.core.model.ContentBlock.TextBlock("Describe this image."),
                                new com.dvarahq.core.model.ContentBlock.ImageBlock("image/png", "BASE64DATA"),
                                new com.dvarahq.core.model.ContentBlock.ImageBlock(
                                        com.dvarahq.core.model.ContentBlock.ImageBlock.URL_MEDIA_TYPE,
                                        "https://example.com/cat.webp", "high")))
                        .build()))
                .build());

        assertThat(response.getChoices().get(0).getMessage().getContent().get(0).textContent()).isEqualTo("A cat.");
        server.verify();
    }

    /** Text alone stays a plain string, as before. */
    @Test
    void chat_textOnly_staysAString() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(jsonPath("$.messages[0].content").value("Hello"))
              .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        provider.chat(ChatRequest.builder().model("command-a-03-2025")
                .messages(List.of(MultimodalMessage.user("Hello"))).build());
        server.verify();
    }

    private static final String TOOL_CALL_RESPONSE = """
            {"id":"c-2","finish_reason":"TOOL_CALL",
             "message":{"role":"assistant","tool_plan":"I will look up the weather.",
               "tool_calls":[{"id":"get_weather_rpg0z5h8yyz2","type":"function",
                 "function":{"name":"get_weather","arguments":"{\\"location\\":\\"Paris\\"}"}}]},
             "usage":{"billed_units":{"input_tokens":126,"output_tokens":84},"tokens":{"input_tokens":1589,"output_tokens":135}}}
            """;

    private static ChatRequest withTools(Object toolChoice) {
        return ChatRequest.builder()
                .model("command-a-03-2025")
                .messages(List.of(MultimodalMessage.user("weather in Paris?")))
                .tools(List.of(
                        com.dvarahq.core.model.ToolDefinition.builder()
                                .name("get_weather").description("Get weather")
                                .parameters(Map.of("type", "object",
                                        "properties", Map.of("location", Map.of("type", "string")))).build(),
                        com.dvarahq.core.model.ToolDefinition.builder()
                                .name("get_time").description("Get time").build()))
                .toolChoice(toolChoice)
                .build();
    }

    /** Definitions go in Cohere's v2 shape; tool_calls come back as tool calls; TOOL_CALL is tool_calls. */
    @Test
    void chat_withTools_sendsDefinitionsAndMapsToolCallsBack() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(jsonPath("$.tools[0].type").value("function"))
              .andExpect(jsonPath("$.tools[0].function.name").value("get_weather"))
              .andExpect(jsonPath("$.tools[0].function.description").value("Get weather"))
              .andExpect(jsonPath("$.tools[0].function.parameters.properties.location.type").value("string"))
              // Cohere requires parameters; a tool defined without them gets an empty object schema.
              .andExpect(jsonPath("$.tools[1].function.parameters.type").value("object"))
              .andExpect(jsonPath("$.tool_choice").doesNotExist())
              .andRespond(withSuccess(TOOL_CALL_RESPONSE, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(withTools("auto"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("tool_calls");
        List<com.dvarahq.core.model.ToolCall> calls = response.getChoices().get(0).getMessage().getToolCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getId()).isEqualTo("get_weather_rpg0z5h8yyz2");
        assertThat(calls.get(0).getName()).isEqualTo("get_weather");
        assertThat(calls.get(0).getArguments()).isEqualTo("{\"location\":\"Paris\"}");
        // The plan is the model's reasoning before a call, not answer text.
        assertThat(response.getChoices().get(0).getMessage().getContent().get(0).textContent()).isEmpty();
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(1589);
        server.verify();
    }

    /** OpenAI's required and none are Cohere's REQUIRED and NONE. */
    @Test
    void chat_toolChoiceRequiredAndNone_areCoheresWords() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(jsonPath("$.tool_choice").value("REQUIRED"))
              .andRespond(withSuccess(TOOL_CALL_RESPONSE, MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("/chat")))
              .andExpect(jsonPath("$.tool_choice").value("NONE"))
              .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));
        provider.chat(withTools("required"));
        provider.chat(withTools("none"));
        server.verify();
    }

    /** Cohere cannot name one tool; a choice naming one offers that tool alone, as REQUIRED. */
    @Test
    void chat_toolChoiceNamingAFunction_offersThatToolAloneAsRequired() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(jsonPath("$.tools.length()").value(1))
              .andExpect(jsonPath("$.tools[0].function.name").value("get_time"))
              .andExpect(jsonPath("$.tool_choice").value("REQUIRED"))
              .andRespond(withSuccess(TOOL_CALL_RESPONSE, MediaType.APPLICATION_JSON));
        provider.chat(withTools(Map.of("type", "function", "function", Map.of("name", "get_time"))));
        server.verify();
    }

    /** A choice naming a tool the request does not define is refused before anything is sent. */
    @Test
    void chat_toolChoiceNamingAnUnknownFunction_isRefused() {
        assertThatThrownBy(() -> provider.chat(
                withTools(Map.of("type", "function", "function", Map.of("name", "get_stock")))))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("get_stock")
                .extracting("code").isEqualTo("INVALID_REQUEST");
        server.verify();
    }

    /** The second turn: the assistant's tool_calls (no empty content) and the tool result with its id. */
    @Test
    void chat_toolResultTurn_carriesToolCallsAndToolCallId() {
        server.expect(requestTo(containsString("/chat")))
              .andExpect(jsonPath("$.messages[1].role").value("assistant"))
              .andExpect(jsonPath("$.messages[1].content").doesNotExist())
              .andExpect(jsonPath("$.messages[1].tool_calls[0].id").value("get_weather_rpg0z5h8yyz2"))
              .andExpect(jsonPath("$.messages[1].tool_calls[0].type").value("function"))
              .andExpect(jsonPath("$.messages[1].tool_calls[0].function.name").value("get_weather"))
              .andExpect(jsonPath("$.messages[1].tool_calls[0].function.arguments").value("{\"location\":\"Paris\"}"))
              .andExpect(jsonPath("$.messages[2].role").value("tool"))
              .andExpect(jsonPath("$.messages[2].tool_call_id").value("get_weather_rpg0z5h8yyz2"))
              .andExpect(jsonPath("$.messages[2].content").value("{\"temp\":21}"))
              .andRespond(withSuccess(OK, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("command-a-03-2025")
                .messages(List.of(
                        MultimodalMessage.user("weather in Paris?"),
                        MultimodalMessage.builder().role("assistant")
                                .content(List.of(new com.dvarahq.core.model.ContentBlock.TextBlock("")))
                                .toolCalls(List.of(com.dvarahq.core.model.ToolCall.builder()
                                        .id("get_weather_rpg0z5h8yyz2").name("get_weather")
                                        .arguments("{\"location\":\"Paris\"}").build()))
                                .build(),
                        MultimodalMessage.toolResult("get_weather_rpg0z5h8yyz2", "{\"temp\":21}")))
                .tools(withTools("auto").getTools())
                .build());
        assertThat(response.getChoices().get(0).getMessage().getToolCalls()).isNullOrEmpty();
        server.verify();
    }

    /**
     * Cohere's message-start carries {@code "content": []}, a list, where content-delta carries an object. The list
     * failed the first event, so every stream ended with "Error reading Cohere stream" before any text.
     */
    @Test
    void streamChat_messageStartWithAContentList_isReadAndTheTextFollows() {
        String sse = """
                data: {"id":"s1","type":"message-start","delta":{"message":{"role":"assistant","content":[],"tool_plan":"","tool_calls":[],"citations":[]}}}

                data: {"type":"content-start","index":0,"delta":{"message":{"content":{"type":"text","text":""}}}}

                data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":"Hello"}}}}

                data: {"type":"content-delta","index":0,"delta":{"message":{"content":{"text":" world"}}}}

                data: {"type":"content-end","index":0}

                data: {"type":"message-end","delta":{"finish_reason":"COMPLETE","usage":{"tokens":{"input_tokens":5,"output_tokens":2}}}}

                """;
        server.expect(requestTo(containsString("/chat")))
              .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

        Iterator<SseChunk> it = provider.streamChat(ChatRequest.builder().model("command-a-03-2025")
                .messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        while (it.hasNext()) chunks.add(it.next());

        assertThat(chunks.stream().map(SseChunk::getDelta).filter(java.util.Objects::nonNull)
                .reduce("", String::concat)).isEqualTo("Hello world");
        assertThat(chunks.get(chunks.size() - 1).getFinishReason()).isEqualTo("stop");
        server.verify();
    }

    /**
     * A streamed tool call, as Cohere's stream reference shows it: tool-plan-delta (skipped), then
     * tool-call-start with the id and name, tool-call-delta fragments of the arguments, tool-call-end,
     * and message-end with TOOL_CALL and the usage.
     */
    @Test
    void streamChat_toolCallEvents_becomeToolCallDeltas() {
        String sse = """
                data: {"id":"s1","type":"message-start","delta":{"message":{"role":"assistant","content":[],"tool_plan":"","tool_calls":[],"citations":[]}}}

                data: {"delta":{"message":{"tool_plan":"I"}},"type":"tool-plan-delta"}

                data: {"delta":{"message":{"tool_calls":{"function":{"arguments":"","name":"get_weather"},"id":"get_weather_j3f0adww9pmr","type":"function"}}},"index":0,"type":"tool-call-start"}

                data: {"delta":{"message":{"tool_calls":{"function":{"arguments":"{\\""}}}},"index":0,"type":"tool-call-delta"}

                data: {"delta":{"message":{"tool_calls":{"function":{"arguments":"location\\":\\"Paris\\"}"}}}},"index":0,"type":"tool-call-delta"}

                data: {"index":0,"type":"tool-call-end"}

                data: {"delta":{"message":{"tool_calls":[{"function":{"arguments":"{}","name":"get_time"},"id":"get_time_1","type":"function"}]}},"index":1,"type":"tool-call-start"}

                data: {"index":1,"type":"tool-call-end"}

                data: {"type":"message-end","delta":{"finish_reason":"TOOL_CALL","usage":{"billed_units":{"input_tokens":126,"output_tokens":84},"tokens":{"input_tokens":1589,"output_tokens":135}}}}

                """;
        server.expect(requestTo(containsString("/chat")))
              .andExpect(jsonPath("$.stream").value(true))
              .andExpect(jsonPath("$.tools[0].function.name").value("get_weather"))
              .andRespond(withSuccess(sse, MediaType.TEXT_EVENT_STREAM));

        Iterator<SseChunk> it = provider.streamChat(withTools("auto").toBuilder().stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        while (it.hasNext()) chunks.add(it.next());

        List<com.dvarahq.core.model.ToolCallDelta> deltas = chunks.stream()
                .filter(c -> c.getToolCalls() != null).flatMap(c -> c.getToolCalls().stream()).toList();
        List<com.dvarahq.core.model.ToolCallDelta> first = deltas.stream().filter(d -> d.index() == 0).toList();
        assertThat(first.get(0).id()).isEqualTo("get_weather_j3f0adww9pmr");
        assertThat(first.get(0).name()).isEqualTo("get_weather");
        assertThat(first.stream().map(com.dvarahq.core.model.ToolCallDelta::argumentsFragment)
                .filter(java.util.Objects::nonNull).reduce("", String::concat))
                .isEqualTo("{\"location\":\"Paris\"}");
        com.dvarahq.core.model.ToolCallDelta second = deltas.stream().filter(d -> d.index() == 1).findFirst().orElseThrow();
        assertThat(second.id()).isEqualTo("get_time_1");
        assertThat(second.argumentsFragment()).isEqualTo("{}");
        // No text came out of the plan.
        assertThat(chunks.stream().map(SseChunk::getDelta).filter(java.util.Objects::nonNull)).isEmpty();
        SseChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.isDone()).isTrue();
        assertThat(last.getFinishReason()).isEqualTo("tool_calls");
        assertThat(last.getUsage().getPromptTokens()).isEqualTo(1589);
        assertThat(last.getUsage().getCompletionTokens()).isEqualTo(135);
        server.verify();
    }

    /** A model with no vision refuses the image with a 400; Cohere's message reaches the caller. */
    @Test
    void chat_imageOnATextModel_surfacesCoheresRefusal() {
        server.expect(requestTo(containsString("/chat")))
              .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                      .withBadRequest().contentType(MediaType.APPLICATION_JSON).body("""
                      {"id":"e1","message":"image content is not supported for this model"}
                      """));
        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                .model("command-r-plus")
                .messages(List.of(MultimodalMessage.builder().role("user").content(List.of(
                        new com.dvarahq.core.model.ContentBlock.TextBlock("Describe this image."),
                        new com.dvarahq.core.model.ContentBlock.ImageBlock("image/png", "BASE64DATA"))).build()))
                .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Cohere API error 400")
                .hasMessageContaining("image content is not supported for this model");
    }

    /** A stream cut before message-end is incomplete, not a short answer. */
    @Test
    void streamChat_aStreamCutBeforeMessageEndIsAnError() {
        String sseResponse = """
                data: {"type":"content-delta","delta":{"message":{"content":{"text":"Hello wor"}}}}

                """;
        server.expect(requestTo(containsString("/chat")))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("command-r-plus").messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());

        assertThatThrownBy(() -> {
            while (iterator.hasNext()) {
                iterator.next();
            }
        }).isInstanceOf(GatewayException.class).hasMessageContaining("before a finish reason");
    }
}
