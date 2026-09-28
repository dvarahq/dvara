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
package com.dvarahq.providers.ollama;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OllamaProviderTest {

    private MockRestServiceServer server;
    private OllamaProvider        provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("http://localhost:11434");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new OllamaProvider(builder.build());
    }

    // -------------------------------------------------------------------------
    // Routing / capability
    // -------------------------------------------------------------------------

    @Test
    void supports_ollamaPrefixModel_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("ollama/llama3.2").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_ollamaSlashMistral_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("ollama/mistral").messages(List.of()).build();
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
        assertThat(provider.supportsEmbedding("ollama/llama3.2")).isFalse();
        assertThat(provider.supportsEmbedding("any-model")).isFalse();
    }

    @Test
    void name_returnsOllama() {
        assertThat(provider.name()).isEqualTo("ollama");
    }

    // -------------------------------------------------------------------------
    // chat() — happy path
    // -------------------------------------------------------------------------

    // A caller's stop sequences and seed reach the wire.
    @Test
    void chat_stopAndSeed_travelInTheOpenAiShape() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"stop\":[\"END\"]")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"seed\":42")))
              .andRespond(withSuccess(ollamaSuccessBody("ollama-1", "llama3.2", "hi", 1, 1), MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("ollama/llama3.2")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stop(List.of("END"))
                .seed(42L)
                .build());
        server.verify();
    }

    @Test
    void chat_successResponse_returnsMappedChatResponse() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(ollamaSuccessBody("ollama-1", "llama3.2", "Hello!", 8, 5),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = chatRequest("ollama/llama3.2", "Say hello");
        ChatResponse response = provider.chat(request);

        assertThat(response.getId()).isEqualTo("ollama-1");
        assertThat(response.getModel()).isEqualTo("ollama/llama3.2");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getMessage().getRole()).isEqualTo("assistant");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(8);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(5);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(13);

        server.verify();
    }

    @Test
    void chat_upstreamReportsNoUsage_leavesTheFieldNull() {
        // Not a zeroed block: three zeros cannot be told apart from a call that genuinely consumed
        // nothing, and the metering path drops a response whose total is not positive. Null is what
        // the upstream actually said.
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                      {"id":"ollama-2","model":"llama3.2","choices":[
                        {"index":0,"message":{"role":"assistant","content":"Hi"},"finish_reason":"stop"}]}
                      """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("ollama/llama3.2", "Say hello"));

        assertThat(response.getUsage()).isNull();
        server.verify();
    }

    @Test
    void chat_ollamaPrefixIsStrippedFromModelBeforeSending() {
        // Ollama does not understand the "ollama/" routing prefix, so it is stripped before
        // sending, and the response carries the original prefixed model name.
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(ollamaSuccessBody("ollama-2", "mistral", "ok", 4, 3),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("ollama/mistral", "Hi"));

        // Model returned in the response should be the original prefixed name
        assertThat(response.getModel()).isEqualTo("ollama/mistral");

        server.verify();
    }

    @Test
    void chat_assistantMessageContentIsMapped() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(ollamaSuccessBody("ollama-3", "llama3.2", "Deep answer", 10, 8),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("ollama/llama3.2", "What is life?"));

        MultimodalMessage message = response.getChoices().get(0).getMessage();
        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getContent()).hasSize(1);

        server.verify();
    }

    @Test
    void chat_finishReasonIsMapped() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(ollamaSuccessBody("ollama-4", "llama3.2", "done", 5, 3),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("ollama/llama3.2", "Hi"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");

        server.verify();
    }

    @Test
    void chat_usageTokensAreMappedCorrectly() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(ollamaSuccessBody("ollama-5", "llama3.2", "result", 20, 15),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("ollama/llama3.2", "Count to ten"));

        assertThat(response.getUsage().getPromptTokens()).isEqualTo(20);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(15);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(35);

        server.verify();
    }

    // -------------------------------------------------------------------------
    // chat() — errors
    // -------------------------------------------------------------------------

    @Test
    void chat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(chatRequest("ollama/llama3.2", "Hello")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Ollama error")
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

    private static String ollamaSuccessBody(String id, String model,
                                            String text, int promptTokens, int completionTokens) {
        return """
                {
                  "id": "%s",
                  "object": "chat.completion",
                  "created": 1704067200,
                  "model": "%s",
                  "choices": [{
                    "index": 0,
                    "message": {"role": "assistant", "content": "%s"},
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": %d,
                    "completion_tokens": %d,
                    "total_tokens": %d
                  }
                }
                """.formatted(id, model, text, promptTokens, completionTokens,
                promptTokens + completionTokens);
    }

    // -------------------------------------------------------------------------
    // Tool calls (#30)
    // -------------------------------------------------------------------------

    private static final String TOOL_CALL_REPLY = """
            {"id":"chatcmpl-7","object":"chat.completion","created":1,"model":"qwen3:4b-instruct",
             "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
               "tool_calls":[{"id":"call_1","type":"function",
                 "function":{"name":"get_rate","arguments":"{\\"lane\\":\\"CHI-DAL\\"}"}}]}}],
             "usage":{"prompt_tokens":120,"completion_tokens":18,"total_tokens":138}}
            """;

    /** The tool definitions and tool_choice reach Ollama; the model's tool call comes back in OpenAI shape. */
    @Test
    void chat_toolsTravelToOllama_andTheModelsToolCallComesBack() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.model").value("qwen3:4b-instruct"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.tools[0].type").value("function"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.tools[0].function.name").value("get_rate"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.tools[0].function.parameters.type").value("object"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.tool_choice").value("auto"))
              .andRespond(withSuccess(TOOL_CALL_REPLY, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("ollama/qwen3:4b-instruct")
                .messages(List.of(MultimodalMessage.user("Rate for Chicago to Dallas?")))
                .tools(List.of(com.dvarahq.core.model.ToolDefinition.builder().name("get_rate")
                        .description("Freight rate for a lane")
                        .parameters(Map.of("type", "object", "properties", Map.of("lane", Map.of("type", "string"))))
                        .build()))
                .toolChoice("auto")
                .build());

        server.verify();
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("tool_calls");
        var call = response.getChoices().get(0).getMessage().getToolCalls().get(0);
        assertThat(call.getId()).isEqualTo("call_1");
        assertThat(call.getName()).isEqualTo("get_rate");
        assertThat(call.getArguments()).isEqualTo("{\"lane\":\"CHI-DAL\"}");
    }

    /** The next turn: the assistant's tool call and the tool's result go back to Ollama in OpenAI shape. */
    @Test
    void chat_theAssistantsToolCallAndTheToolResult_goBackToOllama() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[1].role").value("assistant"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[1].tool_calls[0].id").value("call_1"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[1].tool_calls[0].type").value("function"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[1].tool_calls[0].function.name").value("get_rate"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[1].tool_calls[0].function.arguments").value("{\"lane\":\"CHI-DAL\"}"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[2].role").value("tool"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[2].tool_call_id").value("call_1"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[2].content").value("$1,840"))
              .andRespond(withSuccess(ollamaSuccessBody("ollama-2", "qwen3:4b-instruct", "It is $1,840.", 140, 6),
                      MediaType.APPLICATION_JSON));

        MultimodalMessage assistant = MultimodalMessage.builder().role("assistant")
                .content(List.of(new com.dvarahq.core.model.ContentBlock.TextBlock("")))
                .toolCalls(List.of(com.dvarahq.core.model.ToolCall.builder().id("call_1").name("get_rate")
                        .arguments("{\"lane\":\"CHI-DAL\"}").build()))
                .build();
        MultimodalMessage toolResult = MultimodalMessage.builder().role("tool").toolCallId("call_1")
                .content(List.of(new com.dvarahq.core.model.ContentBlock.TextBlock("$1,840"))).build();

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("ollama/qwen3:4b-instruct")
                .messages(List.of(MultimodalMessage.user("Rate for Chicago to Dallas?"), assistant, toolResult))
                .build());

        server.verify();
        assertThat(response.getChoices().get(0).getMessage().getToolCalls()).isNullOrEmpty();
    }

    /** A1: a model without tool support is Ollama's rejection, named with the model; tools are never dropped. */
    @Test
    void chat_aModelWithoutToolSupport_isAnUpstreamRejectionNamingTheModel() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                      .body("{\"error\":{\"message\":\"registry.ollama.ai/library/gemma:2b does not support tools\"}}"));

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                .model("ollama/gemma:2b")
                .messages(List.of(MultimodalMessage.user("hi")))
                .tools(List.of(com.dvarahq.core.model.ToolDefinition.builder().name("get_rate").build()))
                .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("gemma:2b")
                .satisfies(e -> assertThat(((GatewayException) e).getUpstreamStatus()).isEqualTo(400));
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
        assertThat(caps.supportsToolCalls()).isTrue();
        assertThat(caps.supportsStreamingToolCalls()).isFalse();
        assertThat(caps.supportsStructuredOutputs()).isFalse();
        assertThat(caps.supportsJsonMode()).isFalse();
        assertThat(caps.maxContextTokens()).isEqualTo(32_000);
    }

    // -------------------------------------------------------------------------
    // response_format
    // -------------------------------------------------------------------------

    @Test
    void chat_jsonObjectFormat_throwsUnsupportedResponseFormat() {
        ChatRequest request = ChatRequest.builder()
                .model("ollama/llama3.2")
                .messages(List.of(MultimodalMessage.user("Return JSON")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        assertThatThrownBy(() -> provider.chat(request))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("UNSUPPORTED_RESPONSE_FORMAT"));
    }

    @Test
    void chat_textFormat_isAllowed() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(ollamaSuccessBody("ollama-txt", "llama3.2", "Hello!", 8, 5),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("ollama/llama3.2")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .responseFormat(new ResponseFormat.Text())
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
        server.expect(requestTo(containsString("/api/tags")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "models": [
                            {"name": "llama3.2"},
                            {"name": "mistral:7b"}
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var models = provider.listModels();
        assertThat(models).hasSize(2);
        assertThat(models.get(0).id()).isEqualTo("ollama/llama3.2");
        assertThat(models.get(0).ownedBy()).isEqualTo("ollama");
        assertThat(models.get(1).id()).isEqualTo("ollama/mistral:7b");
        server.verify();
    }

    @Test
    void listModels_throwsOnError() {
        server.expect(requestTo(containsString("/api/tags")))
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
                .model("ollama/llama3.2")
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

}