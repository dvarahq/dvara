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
package com.dvarahq.providers.gemini;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
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

class GeminiProviderTest {

    private MockRestServiceServer server;
    private GeminiProvider        provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new GeminiProvider(builder.build());
    }

    // -------------------------------------------------------------------------
    // Routing / capability
    // -------------------------------------------------------------------------

    @Test
    void supports_geminiModel_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("gemini-2.0-flash").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_geminiPro_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("gemini-1.5-pro").messages(List.of()).build();
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
    void supportsEmbedding_alwaysReturnsFalse() {
        assertThat(provider.supportsEmbedding("gemini-2.0-flash")).isFalse();
    }

    @Test
    void name_returnsGemini() {
        assertThat(provider.name()).isEqualTo("gemini");
    }

    // -------------------------------------------------------------------------
    // chat() — happy path
    // -------------------------------------------------------------------------

    @Test
    void chat_topP_mapsToGenerationConfig_topP() {
        // Gemini nests sampling params in generationConfig and uses camelCase `topP`.
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"topP\":0.9")))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "hi", 1, 1), MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("gemini-2.0-flash")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .topP(0.9)
                .build());
        server.verify();
    }

    // A caller's stop sequences and seed reach the wire.
    @Test
    void chat_stopAndSeed_travelInGenerationConfig() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"stopSequences\":[\"END\"]")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"seed\":42")))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "hi", 1, 1), MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("gemini-2.0-flash")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stop(List.of("END"))
                .seed(42L)
                .build());
        server.verify();
    }

    @Test
    void chat_successResponse_returnsMappedChatResponse() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "Paris is the capital.", 10, 5),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = chatRequest("gemini-2.0-flash", "Capital of France?");
        ChatResponse response = provider.chat(request);

        assertThat(response.getModel()).isEqualTo("gemini-2.0-flash");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getMessage().getRole()).isEqualTo("assistant");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(5);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(15);

        server.verify();
    }

    @Test
    void chat_stopFinishReason_isMappedToStop() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "done", 5, 2),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("gemini-2.0-flash", "Hello"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");

        server.verify();
    }

    @Test
    void chat_maxTokensFinishReason_isMappedToLength() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(geminiSuccessBody("MAX_TOKENS", "truncated...", 5, 100),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("gemini-2.0-flash", "Write a novel"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("length");

        server.verify();
    }

    @Test
    void chat_usageTokensAreMappedCorrectly() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "hi", 42, 7),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("gemini-2.0-flash", "Hi"));

        assertThat(response.getUsage().getPromptTokens()).isEqualTo(42);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(7);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(49);

        server.verify();
    }

    @Test
    void chat_assistantContentIsMapped() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "Hello world!", 3, 2),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("gemini-2.0-flash", "Say hello"));

        MultimodalMessage message = response.getChoices().get(0).getMessage();
        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getContent()).hasSize(1);

        server.verify();
    }

    @Test
    void chat_systemMessageIsExtractedFromMessages() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "Sure!", 15, 4),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("gemini-2.0-flash")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("system")
                                .content(List.of(new ContentBlock.TextBlock("You are helpful.")))
                                .build(),
                        MultimodalMessage.user("Can you help?")
                ))
                .build();

        ChatResponse response = provider.chat(request);

        assertThat(response.getChoices()).hasSize(1);

        server.verify();
    }

    @Test
    void chat_safetyFinishReason_isMappedToContentFilter() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(geminiSuccessBody("SAFETY", "I cannot help with that.", 5, 8),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("gemini-2.0-flash", "Something unsafe"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("content_filter");

        server.verify();
    }

    // -------------------------------------------------------------------------
    // chat() — errors
    // -------------------------------------------------------------------------

    @Test
    void chat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(chatRequest("gemini-2.0-flash", "Hello")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Gemini API error")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    @Test
    void chat_unauthorizedError_throwsGatewayException() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> provider.chat(chatRequest("gemini-2.0-flash", "Hello")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid API key")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"))
                // the status travels on the exception, so the resilience layer need not parse the message
                .satisfies(ex -> assertThat(((GatewayException) ex).getUpstreamStatus()).isEqualTo(401));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // streamChat()
    // -------------------------------------------------------------------------

    @Test
    void streamChat_parsesGeminiSseChunks() {
        String sseResponse = """
                data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"finishReason":null}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1,"totalTokenCount":6}}

                data: {"candidates":[{"content":{"parts":[{"text":" world"}],"role":"model"},"finishReason":null}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":2,"totalTokenCount":7}}

                data: {"candidates":[{"content":{"parts":[{"text":"!"}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3,"totalTokenCount":8}}

                """;

        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:streamGenerateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        ChatRequest request = ChatRequest.builder()
                .model("gemini-2.0-flash")
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
        assertThat(chunks.get(0).getModel()).isEqualTo("gemini-2.0-flash");
        assertThat(chunks.get(0).isDone()).isFalse();
        assertThat(chunks.get(1).getDelta()).isEqualTo(" world");
        assertThat(chunks.get(1).isDone()).isFalse();
        assertThat(chunks.get(2).getDelta()).isEqualTo("!");
        assertThat(chunks.get(2).getFinishReason()).isEqualTo("stop");
        assertThat(chunks.get(2).isDone()).isTrue();

        server.verify();
    }

    /** Gemini streams a function call whole in one part and sends no id; one is minted per response. */
    @Test
    void streamChat_functionCallParts_becomeToolCallFragments() {
        String sseResponse = """
                data: {"candidates":[{"content":{"parts":[{"text":"Checking."}],"role":"model"},"finishReason":null}]}
                data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"get_weather","args":{"city":"Paris"}}},{"functionCall":{"name":"get_weather","args":{"city":"Lyon"}}}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":4,"totalTokenCount":13}}
                """;
        server.expect(requestTo(containsString(":streamGenerateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("gemini-2.0-flash").messages(List.of(MultimodalMessage.user("weather?"))).stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        while (iterator.hasNext()) chunks.add(iterator.next());

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).getDelta()).isEqualTo("Checking.");
        List<ToolCallDelta> calls = chunks.get(1).getToolCalls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).index()).isZero();
        assertThat(calls.get(1).index()).isEqualTo(1);
        assertThat(calls).extracting(ToolCallDelta::name).containsExactly("get_weather", "get_weather");
        assertThat(calls).extracting(ToolCallDelta::argumentsFragment).containsExactly("{\"city\":\"Paris\"}", "{\"city\":\"Lyon\"}");
        assertThat(calls.get(0).id()).startsWith("gemini-call-").isNotEqualTo(calls.get(1).id()).isNotEqualTo("get_weather");
        assertThat(chunks.get(1).getFinishReason()).isEqualTo("tool_calls");
        assertThat(chunks.get(1).isDone()).isTrue();
        server.verify();
    }

    /** parts[] is ordered and mixes types: text, a call, then more text — all in one event. */
    @Test
    void streamChat_textAroundAFunctionCallInOnePart_listKeepsEveryTextPart() {
        String sseResponse = """
                data: {"candidates":[{"content":{"parts":[{"text":"Checking "},{"functionCall":{"name":"get_weather","args":{"city":"Paris"}}},{"text":"now."}],"role":"model"},"finishReason":"STOP"}]}
                """;
        server.expect(requestTo(containsString(":streamGenerateContent")))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("gemini-2.0-flash").messages(List.of(MultimodalMessage.user("x"))).stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        while (iterator.hasNext()) chunks.add(iterator.next());

        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().getDelta()).as("both text parts, in order").isEqualTo("Checking now.");
        assertThat(chunks.getFirst().getToolCalls()).hasSize(1);
        assertThat(chunks.getFirst().getToolCalls().getFirst().name()).isEqualTo("get_weather");
        assertThat(chunks.getFirst().getFinishReason()).isEqualTo("tool_calls");
    }

    @Test
    void streamChat_functionCallWithAnId_keepsIt() {
        String sseResponse = """
                data: {"candidates":[{"content":{"parts":[{"functionCall":{"id":"fc_77","name":"lookup","args":{}}}],"role":"model"},"finishReason":"STOP"}]}
                """;
        server.expect(requestTo(containsString(":streamGenerateContent")))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("gemini-2.0-flash").messages(List.of(MultimodalMessage.user("x"))).stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        while (iterator.hasNext()) chunks.add(iterator.next());

        assertThat(chunks.getFirst().getToolCalls()).containsExactly(new ToolCallDelta(0, "fc_77", "lookup", "{}"));
        assertThat(chunks.getFirst().getFinishReason()).isEqualTo("tool_calls");
    }

    @Test
    void streamChat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:streamGenerateContent")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.streamChat(ChatRequest.builder()
                        .model("gemini-2.0-flash")
                        .messages(List.of(MultimodalMessage.user("Hi")))
                        .stream(true)
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Gemini streaming error")
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
        assertThat(caps.maxContextTokens()).isEqualTo(1_000_000);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ChatRequest chatRequest(String model, String userText) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user(userText)))
                .build();
    }

    // -------------------------------------------------------------------------
    // response_format
    // -------------------------------------------------------------------------

    @Test
    void chat_jsonObjectFormat_setsResponseMimeType() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"responseMimeType\":\"application/json\"")))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "valid json output", 10, 5),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("gemini-2.0-flash")
                .messages(List.of(MultimodalMessage.user("Return JSON")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getChoices()).hasSize(1);

        server.verify();
    }

    @Test
    void chat_jsonSchemaFormat_setsResponseSchema() {
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"responseMimeType\":\"application/json\"")))
              .andExpect(content().string(containsString("\"responseSchema\"")))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "structured output", 10, 5),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("gemini-2.0-flash")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonSchema("test",
                        Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"))), false))
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getChoices()).hasSize(1);

        server.verify();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String geminiSuccessBody(String finishReason, String text,
                                            int promptTokens, int candidatesTokens) {
        return """
                {
                  "candidates": [{
                    "content": {
                      "parts": [{"text": "%s"}],
                      "role": "model"
                    },
                    "finishReason": "%s"
                  }],
                  "usageMetadata": {
                    "promptTokenCount": %d,
                    "candidatesTokenCount": %d,
                    "totalTokenCount": %d
                  }
                }
                """.formatted(text, finishReason, promptTokens, candidatesTokens,
                promptTokens + candidatesTokens);
    }

    // -------------------------------------------------------------------------
    // Multimodal (vision)
    // -------------------------------------------------------------------------

    @Test
    void chat_imageBlockInUserMessage_isForwardedAsNativeGeminiInlineData() {
        // An ImageBlock reaches Gemini as an {inlineData: {mimeType, data}} part alongside the
        // {text: "..."} part, rather than being dropped from the message.
        server.expect(requestTo(containsString("/v1beta/models/gemini-2.0-flash:generateContent")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"inlineData\"")))
              .andExpect(content().string(containsString("\"mimeType\":\"image/png\"")))
              .andExpect(content().string(containsString("\"data\":\"iVBORw0KGgo=\"")))
              .andExpect(content().string(containsString("\"text\":\"Describe this image\"")))
              .andRespond(withSuccess(geminiSuccessBody("STOP", "I see a small image.", 50, 8),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("gemini-2.0-flash")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(
                                new ContentBlock.TextBlock("Describe this image"),
                                new ContentBlock.ImageBlock("image/png", "iVBORw0KGgo=")))
                        .build()))
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getChoices()).hasSize(1);

        server.verify();
    }

    // -------------------------------------------------------------------------
    // listModels
    // -------------------------------------------------------------------------

    @Test
    void listModels_returnsModelsFromApi() {
        server.expect(requestTo(containsString("/v1beta/models?key=test-key")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "models": [
                            {"name": "models/gemini-2.0-flash"},
                            {"name": "models/gemini-1.5-pro"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var models = provider.listModels();
        assertThat(models).hasSize(2);
        assertThat(models.get(0).id()).isEqualTo("gemini-2.0-flash");
        assertThat(models.get(0).ownedBy()).isEqualTo("google");
        assertThat(models.get(1).id()).isEqualTo("gemini-1.5-pro");
        server.verify();
    }

    @Test
    void listModels_throwsOnError() {
        server.expect(requestTo(containsString("/v1beta/models")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThatThrownBy(() -> provider.listModels())
                .isInstanceOf(Exception.class);
        server.verify();
    }

    @Test
    void chat_withTools_sendsFunctionDeclarationsAndParsesFunctionCall() {
        // Gemini: tools go out as functionDeclarations; a functionCall part
        // comes back as a tool call (name doubles as id) with finish_reason tool_calls.
        server.expect(requestTo(containsString(":generateContent")))
                .andExpect(jsonPath("$.tools[0].functionDeclarations[0].name").value("get_weather"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[
                            {"functionCall":{"name":"get_weather","args":{"city":"Paris"}}}]},
                          "finishReason":"STOP"}],
                         "usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":3}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("gemini-2.0-flash")
                .messages(List.of(MultimodalMessage.user("weather in Paris?")))
                .tools(List.of(ToolDefinition.builder().name("get_weather")
                        .description("Get weather").parameters(Map.of("type", "object")).build()))
                .build());

        server.verify();
        var calls = response.getChoices().get(0).getMessage().getToolCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getName()).isEqualTo("get_weather");
        assertThat(calls.get(0).getArguments()).contains("Paris");
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("tool_calls");
    }

    /** A streamed call gets a minted id, and the tool result names that id; Gemini must still get the function name. */
    @Test
    void chat_toolResultForAMintedCallId_sendsTheFunctionName() {
        server.expect(requestTo(containsString(":generateContent")))
                .andExpect(jsonPath("$.contents[1].parts[0].functionCall.name").value("get_weather"))
                .andExpect(jsonPath("$.contents[2].parts[0].functionResponse.name").value("get_weather"))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"Sunny."}]},"finishReason":"STOP"}]}
                        """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("gemini-2.0-flash")
                .messages(List.of(
                        MultimodalMessage.user("weather in Paris?"),
                        MultimodalMessage.builder().role("assistant").toolCalls(List.of(
                                com.dvarahq.core.model.ToolCall.builder().id("gemini-call-1a2b-0")
                                        .name("get_weather").arguments("{\"city\":\"Paris\"}").build())).build(),
                        MultimodalMessage.toolResult("gemini-call-1a2b-0", "sunny")))
                .build());

        server.verify();
    }

    /** Gemini sends its key in the query string, not through the header interceptor, so it records the fingerprint itself. */
    @Test
    void chat_recordsTheFingerprintOfTheKeyItCalledWith() {
        server.expect(requestTo(containsString(":generateContent")))
                .andRespond(withSuccess("""
                        {"candidates":[{"content":{"parts":[{"text":"hi"}]},"finishReason":"STOP"}]}
                        """, MediaType.APPLICATION_JSON));
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
        try {
            provider.chat(ChatRequest.builder().model("gemini-2.0-flash")
                    .messages(List.of(MultimodalMessage.user("hi"))).build());

            assertThat(com.dvarahq.providers.support.CredentialInterceptor.resolveFingerprint())
                    .isNotNull()
                    .isEqualTo(com.dvarahq.core.credential.CredentialFingerprint.of("test-key"));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    /** A stream cut before any finishReason is incomplete, not a short answer. */
    @Test
    void streamChat_aStreamCutBeforeAFinishReasonIsAnError() {
        String sseResponse = """
                data: {"candidates":[{"content":{"parts":[{"text":"Hello wor"}],"role":"model"},"finishReason":null}]}

                """;
        server.expect(requestTo(containsString(":streamGenerateContent")))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("gemini-2.0-flash").messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());

        assertThatThrownBy(() -> {
            while (iterator.hasNext()) {
                iterator.next();
            }
        }).isInstanceOf(GatewayException.class).hasMessageContaining("before a finish reason");
    }

    /** MAX_TOKENS ends the answer as surely as STOP, so it is the terminal chunk. */
    @Test
    void streamChat_aMaxTokensFinishEndsTheAnswer() {
        String sseResponse = """
                data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"finishReason":"MAX_TOKENS"}]}

                """;
        server.expect(requestTo(containsString(":streamGenerateContent")))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("gemini-2.0-flash").messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        while (iterator.hasNext()) {
            chunks.add(iterator.next());
        }

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getFinishReason()).isEqualTo("length");
        assertThat(chunks.get(0).isDone()).isTrue();
    }
}
