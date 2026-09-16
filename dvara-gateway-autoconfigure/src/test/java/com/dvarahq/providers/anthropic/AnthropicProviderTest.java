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
package com.dvarahq.providers.anthropic;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

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

class AnthropicProviderTest {

    private MockRestServiceServer server;
    private AnthropicProvider     provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.anthropic.com");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new AnthropicProvider(builder.build());
    }

    // -------------------------------------------------------------------------
    // Routing / capability
    // -------------------------------------------------------------------------

    @Test
    void supports_claudePrefixModel_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("claude-sonnet-4-5").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_gptModel_returnsFalse() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").messages(List.of()).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void supportsEmbedding_alwaysReturnsFalse() {
        assertThat(provider.supportsEmbedding("any-model")).isFalse();
    }

    @Test
    void name_returnsAnthropic() {
        assertThat(provider.name()).isEqualTo("anthropic");
    }

    // -------------------------------------------------------------------------
    // chat() — happy path
    // -------------------------------------------------------------------------

    @Test
    void chat_stop_travelsAsStopSequences() {
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"stop_sequences\":[\"END\"]")))
              .andRespond(withSuccess(anthropicSuccessBody("msg-1", "end_turn", "hi", 1, 1), MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stop(List.of("END"))
                .build());
        server.verify();
    }

    @Test
    void chat_seed_isRefusedNotDropped() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("claude-sonnet-4-5")
                        .messages(List.of(MultimodalMessage.user("Hi")))
                        .seed(42L)
                        .build()))
                .isInstanceOf(com.dvarahq.core.exception.GatewayException.class)
                .hasMessageContaining("seed is not supported by Anthropic")
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                        ((com.dvarahq.core.exception.GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"));
    }

    @Test
    void chat_successResponse_returnsMappedChatResponse() {
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(anthropicSuccessBody("msg-123", "end_turn", "Paris", 10, 3), MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(MultimodalMessage.user("Capital of France?")))
                .build();

        ChatResponse response = provider.chat(request);

        assertThat(response.getId()).isEqualTo("msg-123");
        assertThat(response.getModel()).isEqualTo("claude-sonnet-4-5");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getMessage().getRole()).isEqualTo("assistant");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(3);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(13);

        server.verify();
    }

    @Test
    void chat_endTurnStopReason_isMappedToStop() {
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(anthropicSuccessBody("msg-1", "end_turn", "ok", 5, 2), MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("claude-sonnet-4-5", "Hello"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");

        server.verify();
    }

    @Test
    void chat_maxTokensStopReason_isPassedThrough() {
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(anthropicSuccessBody("msg-2", "max_tokens", "truncated...", 5, 100), MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("claude-sonnet-4-5", "Write a novel"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("max_tokens");

        server.verify();
    }

    @Test
    void chat_usageTokensAreMappedCorrectly() {
        // input_tokens maps to promptTokens and output_tokens to completionTokens.
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(anthropicSuccessBody("msg-3", "end_turn", "hi", 42, 7), MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("claude-sonnet-4-5", "Hi"));

        assertThat(response.getUsage().getPromptTokens()).isEqualTo(42);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(7);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(49);

        server.verify();
    }

    @Test
    void chat_systemMessageIsExtractedFromMessages() {
        // System messages are sent in the separate "system" field, not in the "messages" array.
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(anthropicSuccessBody("msg-4", "end_turn", "Sure!", 15, 4), MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("system")
                                .content(List.of(new com.dvarahq.core.model.ContentBlock.TextBlock("You are helpful.")))
                                .build(),
                        MultimodalMessage.user("Can you help?")
                ))
                .build();

        ChatResponse response = provider.chat(request);

        assertThat(response.getId()).isEqualTo("msg-4");

        server.verify();
    }

    // -------------------------------------------------------------------------
    // chat() — errors
    // -------------------------------------------------------------------------

    @Test
    void chat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(chatRequest("claude-sonnet-4-5", "Hello")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Anthropic API error")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    @Test
    void chat_unauthorizedError_throwsGatewayException() {
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> provider.chat(chatRequest("claude-sonnet-4-5", "Hello")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid API key")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
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

    private static String anthropicSuccessBody(String id, String stopReason,
                                               String text, int inputTokens, int outputTokens) {
        return """
                {
                  "id": "%s",
                  "type": "message",
                  "role": "assistant",
                  "content": [{"type": "text", "text": "%s"}],
                  "model": "claude-sonnet-4-5",
                  "stop_reason": "%s",
                  "usage": {"input_tokens": %d, "output_tokens": %d}
                }
                """.formatted(id, text, stopReason, inputTokens, outputTokens);
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
        assertThat(caps.maxContextTokens()).isEqualTo(200_000);
    }

    // -------------------------------------------------------------------------
    // response_format
    // -------------------------------------------------------------------------

    @Test
    void chat_jsonObjectFormat_appendsSystemPrompt() {
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("Respond with valid JSON")))
              .andRespond(withSuccess(anthropicSuccessBody("msg-jo", "end_turn", "valid json output", 10, 5),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(MultimodalMessage.user("Return JSON")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getId()).isEqualTo("msg-jo");

        server.verify();
    }

    @Test
    void chat_jsonSchemaFormat_rewritesToToolUse() {
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("structured_output")))
              .andExpect(content().string(containsString("tools")))
              .andRespond(withSuccess("""
                      {
                        "id": "msg-js", "type": "message", "role": "assistant",
                        "content": [{"type": "tool_use", "id": "tu_1", "name": "structured_output", "input": {"name": "John"}}],
                        "model": "claude-sonnet-4-5", "stop_reason": "tool_use",
                        "usage": {"input_tokens": 10, "output_tokens": 5}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonSchema("test", Map.of("type", "object"), false))
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        String text = response.getChoices().get(0).getMessage().getContent().get(0).toString();
        assertThat(text).contains("name");
        assertThat(text).contains("John");

        server.verify();
    }

    @Test
    void chat_jsonSchemaStrict_setsDowngradeHeader() {
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                      {
                        "id": "msg-strict", "type": "message", "role": "assistant",
                        "content": [{"type": "tool_use", "id": "tu_2", "name": "structured_output", "input": {"key": "val"}}],
                        "model": "claude-sonnet-4-5", "stop_reason": "tool_use",
                        "usage": {"input_tokens": 10, "output_tokens": 5}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonSchema("test", Map.of("type", "object"), true))
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getGatewayHeaders()).containsEntry("X-Gateway-Strict-Downgraded", "true");

        server.verify();
    }

    // -------------------------------------------------------------------------
    // Multimodal (vision)
    // -------------------------------------------------------------------------

    @Test
    void chat_imageBlockInUserMessage_isForwardedAsNativeAnthropicImage() {
        // An ImageBlock reaches the upstream API in the native
        // {type: image, source: {type: base64, media_type, data}} envelope, alongside the
        // text block in a typed-blocks content array.
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"type\":\"image\"")))
              .andExpect(content().string(containsString("\"media_type\":\"image/png\"")))
              .andExpect(content().string(containsString("\"data\":\"iVBORw0KGgo=\"")))
              .andExpect(content().string(containsString("\"type\":\"text\"")))
              .andExpect(content().string(containsString("\"text\":\"Describe this image\"")))
              .andRespond(withSuccess(anthropicSuccessBody("msg-img", "end_turn", "I see a small image.", 50, 8),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(
                                new ContentBlock.TextBlock("Describe this image"),
                                new ContentBlock.ImageBlock("image/png", "iVBORw0KGgo=")))
                        .build()))
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getId()).isEqualTo("msg-img");

        server.verify();
    }

    @Test
    void chat_textOnlyMessage_keepsStringContentForm() {
        // A text-only message uses the string form of the content field, not the typed-blocks form.
        server.expect(requestTo(containsString("/v1/messages")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"content\":\"Hello\"")))
              .andRespond(withSuccess(anthropicSuccessBody("msg-txt", "end_turn", "Hi", 5, 2),
                      MediaType.APPLICATION_JSON));

        provider.chat(chatRequest("claude-sonnet-4-5", "Hello"));

        server.verify();
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
                          "data": [
                            {"id": "claude-sonnet-4-5-20250514", "created_at": "2025-05-14T00:00:00Z"},
                            {"id": "claude-3-5-haiku-20241022", "created_at": "2024-10-22T00:00:00Z"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var models = provider.listModels();
        assertThat(models).hasSize(2);
        assertThat(models.get(0).id()).isEqualTo("claude-sonnet-4-5-20250514");
        assertThat(models.get(0).ownedBy()).isEqualTo("anthropic");
        assertThat(models.get(1).id()).isEqualTo("claude-3-5-haiku-20241022");
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
    void chat_withTools_sendsAnthropicToolsAndParsesToolUse() {
        // Tools go out as {name, input_schema}; tool_use blocks come back as tool calls
        // and stop_reason "tool_use" maps to "tool_calls".
        server.expect(requestTo(containsString("/v1/messages")))
                .andExpect(jsonPath("$.tools[0].name").value("get_weather"))
                .andExpect(jsonPath("$.tools[0].input_schema").exists())
                .andRespond(withSuccess("""
                        {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
                         "stop_reason":"tool_use",
                         "content":[{"type":"tool_use","id":"toolu_1","name":"get_weather","input":{"city":"Paris"}}],
                         "usage":{"input_tokens":5,"output_tokens":3}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("claude-sonnet-4-5")
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

    @Test
    void chat_toolResult_becomesUserToolResultBlock() {
        // A tool-role result becomes an Anthropic user message with a tool_result block.
        server.expect(requestTo(containsString("/v1/messages")))
                .andExpect(jsonPath("$.messages[-1:].role").value("user"))
                .andExpect(jsonPath("$.messages[-1:].content[0].type").value("tool_result"))
                .andExpect(jsonPath("$.messages[-1:].content[0].tool_use_id").value("toolu_1"))
                .andRespond(withSuccess("""
                        {"id":"m","type":"message","role":"assistant","model":"claude-sonnet-4-5",
                         "stop_reason":"end_turn","content":[{"type":"text","text":"Sunny."}],
                         "usage":{"input_tokens":1,"output_tokens":1}}
                        """, MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("claude-sonnet-4-5")
                .messages(List.of(
                        MultimodalMessage.user("weather?"),
                        MultimodalMessage.toolResult("toolu_1", "sunny")))
                .build());

        server.verify();
    }
}