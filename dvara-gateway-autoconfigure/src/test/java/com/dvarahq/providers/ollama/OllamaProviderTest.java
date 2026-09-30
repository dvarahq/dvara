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

    /** Ollama decides which of its models can embed, so every ollama/ model is offered; no other is. */
    @Test
    void supportsEmbedding_everyOllamaModel_andNoOther() {
        assertThat(provider.supportsEmbedding("ollama/nomic-embed-text")).isTrue();
        assertThat(provider.supportsEmbedding("ollama/mxbai-embed-large")).isTrue();
        assertThat(provider.supportsEmbedding("ollama/all-minilm:l6-v2")).isTrue();
        assertThat(provider.supportsEmbedding("text-embedding-3-small")).isFalse();
        assertThat(provider.supportsEmbedding(null)).isFalse();
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
    // Per-workspace endpoints (#30, UC-105 A6/A7: DVARA Cloud)
    // -------------------------------------------------------------------------

    /** A provider whose platform client is {@link #server} and whose workspace client is {@code tenants}. */
    private OllamaProvider perWorkspace(MockRestServiceServer[] tenants, OllamaEndpointResolver resolver) {
        RestClient.Builder tenantBuilder = RestClient.builder();
        tenants[0] = MockRestServiceServer.bindTo(tenantBuilder).build();
        provider.usePerWorkspaceEndpoints(resolver, tenantBuilder.build());
        return provider;
    }

    private static final OllamaEndpointResolver ACME_ONLY = ws -> "acme".equals(ws)
            ? java.util.Optional.of(new OllamaEndpointResolver.Endpoint("https://ollama.acme.test/v1/", "acme-key"))
            : java.util.Optional.empty();

    /** Runs {@code body} as a call from {@code workspaceId}. */
    private static <T> T as(String workspaceId, java.util.function.Supplier<T> body) {
        java.util.concurrent.atomic.AtomicReference<T> out = new java.util.concurrent.atomic.AtomicReference<>();
        com.dvarahq.providers.support.WorkspaceScope.runWith(workspaceId, () -> out.set(body.get()));
        return out.get();
    }

    private static ChatRequest hi() {
        return ChatRequest.builder().model("ollama/qwen3:4b-instruct").messages(List.of(MultimodalMessage.user("hi"))).build();
    }

    @Test
    void perWorkspace_aCallGoesToTheWorkspacesOwnEndpoint_withItsKey() {
        MockRestServiceServer[] tenant = new MockRestServiceServer[1];
        perWorkspace(tenant, ACME_ONLY);
        tenant[0].expect(requestTo("https://ollama.acme.test/v1/chat/completions"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization", "Bearer acme-key"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.model").value("qwen3:4b-instruct"))
                .andRespond(withSuccess(ollamaSuccessBody("o-1", "qwen3:4b-instruct", "hello", 3, 1), MediaType.APPLICATION_JSON));

        ChatResponse response = as("acme", () -> provider.chat(hi()));

        tenant[0].verify();
        server.verify();   // the platform-wide Ollama was never called
        assertThat(response.getChoices().get(0).getMessage().getContent().get(0))
                .isEqualTo(new com.dvarahq.core.model.ContentBlock.TextBlock("hello"));
    }

    @Test
    void perWorkspace_aWorkspaceWithNoEndpoint_isRefused_andNeverReachesAnotherOrThePlatform() {
        MockRestServiceServer[] tenant = new MockRestServiceServer[1];
        perWorkspace(tenant, ACME_ONLY);

        assertThatThrownBy(() -> as("globex", () -> provider.chat(hi())))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("NO_PROVIDER"))
                .hasMessageContaining("No Ollama endpoint is registered for this workspace");
        assertThatThrownBy(() -> provider.chat(hi()))   // no workspace at all
                .isInstanceOf(GatewayException.class).hasMessageContaining("No Ollama endpoint");
        tenant[0].verify();
        server.verify();
    }

    @Test
    void perWorkspace_withoutAResolver_everyCallIsRefused() {
        MockRestServiceServer[] tenant = new MockRestServiceServer[1];
        perWorkspace(tenant, null);
        assertThatThrownBy(() -> as("acme", () -> provider.chat(hi())))
                .isInstanceOf(GatewayException.class).hasMessageContaining("No Ollama endpoint");
        server.verify();
    }

    @Test
    void perWorkspace_aRedirectIsRefused_notFollowed() {
        MockRestServiceServer[] tenant = new MockRestServiceServer[1];
        perWorkspace(tenant, ACME_ONLY);
        tenant[0].expect(requestTo("https://ollama.acme.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.FOUND).header("Location", "http://169.254.169.254/latest/meta-data/"));

        assertThatThrownBy(() -> as("acme", () -> provider.chat(hi())))
                .isInstanceOf(GatewayException.class).hasMessageContaining("redirect");
        tenant[0].verify();
    }

    @Test
    void perWorkspace_listsNoPlatformModels() {
        perWorkspace(new MockRestServiceServer[1], ACME_ONLY);
        assertThat(provider.listModels()).isEmpty();
        server.verify();
    }

    @Test
    void anEndpointNeverPrintsItsCredential() {
        assertThat(new OllamaEndpointResolver.Endpoint("https://x", "secret").toString()).doesNotContain("secret");
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
        assertThat(caps.maxContextTokens()).isEqualTo(32_000);
    }

    // -------------------------------------------------------------------------
    // response_format
    // -------------------------------------------------------------------------

    /**
     * JSON mode travels as OpenAI's response_format. Ollama's /v1 endpoint honours that field and ignores
     * its native top-level "format", so "format" would ask for nothing.
     */
    @Test
    void chat_jsonObjectFormat_travelsAsResponseFormat() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.response_format.type").value("json_object"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.format").doesNotExist())
              .andRespond(withSuccess(ollamaSuccessBody("o-j", "llama3.2", "{\\\"ok\\\":true}", 5, 3), MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("ollama/llama3.2")
                .messages(List.of(MultimodalMessage.user("Return JSON")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build());

        server.verify();
        assertThat(response.getChoices().get(0).getMessage().getContent().get(0))
                .isEqualTo(new com.dvarahq.core.model.ContentBlock.TextBlock("{\"ok\":true}"));
    }

    /** A streamed call asks for JSON the same way. */
    @Test
    void streamChat_jsonObjectFormat_travelsAsResponseFormat() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.stream").value(true))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.response_format.type").value("json_object"))
              .andRespond(withSuccess("""
                      data: {"id":"s","model":"llama3.2","choices":[{"index":0,"delta":{"content":"{}"},"finish_reason":null}]}

                      data: {"id":"s","model":"llama3.2","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                      data: [DONE]

                      """, MediaType.TEXT_EVENT_STREAM));

        var it = provider.streamChat(ChatRequest.builder()
                .model("ollama/llama3.2")
                .messages(List.of(MultimodalMessage.user("Return JSON")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build());
        it.forEachRemaining(c -> { });
        server.verify();
    }

    /** No response_format, or text, sends none. */
    @Test
    void chat_noResponseFormat_sendsNone() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.response_format").doesNotExist())
              .andRespond(withSuccess(ollamaSuccessBody("o-n", "llama3.2", "hi", 1, 1), MediaType.APPLICATION_JSON));
        provider.chat(chatRequest("ollama/llama3.2", "Hi"));
        server.verify();
    }

    private static ChatRequest schemaRequest(boolean strict) {
        return ChatRequest.builder()
                .model("ollama/qwen3:4b-instruct")
                .messages(List.of(MultimodalMessage.user("Largest city in France, as JSON")))
                .responseFormat(new ResponseFormat.JsonSchema("city", Map.of(
                        "type", "object",
                        "properties", Map.of("city", Map.of("type", "string"), "pop", Map.of("type", "integer")),
                        "required", List.of("city", "pop")), strict))
                .build();
    }

    /**
     * A JSON schema travels as OpenAI's response_format. Ollama's /v1 endpoint follows it there and ignores
     * a top-level "format" schema, so "format" would constrain nothing.
     */
    @Test
    void chat_jsonSchemaFormat_travelsAsResponseFormat() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.response_format.type").value("json_schema"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.response_format.json_schema.name").value("city"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.response_format.json_schema.schema.type").value("object"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.response_format.json_schema.schema.required[1]").value("pop"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.response_format.json_schema.strict").value(true))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.format").doesNotExist())
              .andRespond(withSuccess(ollamaSuccessBody("o-s", "qwen3:4b-instruct", "{\\\"city\\\":\\\"Paris\\\",\\\"pop\\\":2161000}", 20, 9), MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(schemaRequest(true));

        server.verify();
        assertThat(response.getChoices().get(0).getMessage().getContent().get(0))
                .isEqualTo(new com.dvarahq.core.model.ContentBlock.TextBlock("{\"city\":\"Paris\",\"pop\":2161000}"));
    }

    /** A streamed call carries the schema the same way. */
    @Test
    void streamChat_jsonSchemaFormat_travelsAsResponseFormat() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.stream").value(true))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.response_format.json_schema.name").value("city"))
              .andRespond(withSuccess("""
                      data: {"id":"s","model":"qwen3:4b-instruct","choices":[{"index":0,"delta":{"content":"{}"},"finish_reason":null}]}

                      data: {"id":"s","model":"qwen3:4b-instruct","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                      data: [DONE]

                      """, MediaType.TEXT_EVENT_STREAM));

        provider.streamChat(schemaRequest(false)).forEachRemaining(c -> { });
        server.verify();
    }

    /** Off (an Ollama older than 0.5): not declared, and a schema that still reaches the provider is refused. */
    @Test
    void structuredOutputsOff_isNotDeclared_andASchemaIsRefused() {
        provider.setStructuredOutputs(false);

        assertThat(provider.capabilities().supportsStructuredOutputs()).isFalse();
        assertThatThrownBy(() -> provider.chat(schemaRequest(false)))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("UNSUPPORTED_RESPONSE_FORMAT"));
        server.verify();
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
    // Vision
    // -------------------------------------------------------------------------

    private static ChatRequest imageRequest(com.dvarahq.core.model.ContentBlock.ImageBlock image) {
        return ChatRequest.builder()
                .model("ollama/qwen3.5:4b")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(
                                new com.dvarahq.core.model.ContentBlock.TextBlock("Describe this image."),
                                image))
                        .build()))
                .build();
    }

    /** An image travels as a content array, the base64 back in a data: URL, the shape Ollama's /v1 reads. */
    @Test
    void chat_imageBlock_travelsAsAContentArrayWithADataUrl() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[0].content[0].type").value("text"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[0].content[0].text").value("Describe this image."))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[0].content[1].type").value("image_url"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[0].content[1].image_url.url").value("data:image/png;base64,BASE64DATA"))
              .andRespond(withSuccess(ollamaSuccessBody("o-v", "qwen3.5:4b", "A red square.", 40, 4), MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(imageRequest(
                new com.dvarahq.core.model.ContentBlock.ImageBlock("image/png", "BASE64DATA")));

        server.verify();
        assertThat(response.getChoices().get(0).getMessage().getContent().get(0))
                .isEqualTo(new com.dvarahq.core.model.ContentBlock.TextBlock("A red square."));
    }

    /** A message with text only stays a plain string, as before. */
    @Test
    void chat_textOnlyMessage_staysAPlainString() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[0].content").value("Hi"))
              .andRespond(withSuccess(ollamaSuccessBody("o-t", "llama3.2", "Hello", 1, 1), MediaType.APPLICATION_JSON));

        provider.chat(chatRequest("ollama/llama3.2", "Hi"));
        server.verify();
    }

    /**
     * Ollama refuses an https image URL ("please use base64 encoded data instead"). So with fetching off the
     * gateway refuses it before the call, naming the fix; with fetching on it fetches the image and sends
     * the bytes.
     */
    @Test
    void chat_imageUrl_isRefusedWhenFetchingIsOff_andInlinedWhenOn() {
        ChatRequest request = imageRequest(new com.dvarahq.core.model.ContentBlock.ImageBlock(
                com.dvarahq.core.model.ContentBlock.ImageBlock.URL_MEDIA_TYPE, "https://example.com/cat.png"));
        assertThatThrownBy(() -> provider.chat(request))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("base64")
                .extracting("code").isEqualTo("UNSUPPORTED_CAPABILITY");

        List<String> fetched = new java.util.ArrayList<>();
        provider.setImageFetcher(stubFetcher(fetched));
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[0].content[1].image_url.url").value("data:image/png;base64,iVBORw0KGgo="))
              .andRespond(withSuccess(ollamaSuccessBody("o-u", "qwen3.5:4b", "A cat.", 40, 3), MediaType.APPLICATION_JSON));

        provider.chat(request);

        server.verify();
        assertThat(fetched).containsExactly("https://example.com/cat.png");
    }

    /** A streamed call carries the image the same way. */
    @Test
    void streamChat_imageBlock_travelsAsAContentArray() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.stream").value(true))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.messages[0].content[1].image_url.url").value("data:image/jpeg;base64,JPEGDATA"))
              .andRespond(withSuccess("""
                      data: {"id":"s","model":"qwen3.5:4b","choices":[{"index":0,"delta":{"content":"Red"},"finish_reason":null}]}

                      data: {"id":"s","model":"qwen3.5:4b","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                      data: [DONE]

                      """, MediaType.TEXT_EVENT_STREAM));

        var it = provider.streamChat(imageRequest(
                new com.dvarahq.core.model.ContentBlock.ImageBlock("image/jpeg", "JPEGDATA")));
        StringBuilder text = new StringBuilder();
        it.forEachRemaining(c -> { if (c.getDelta() != null) text.append(c.getDelta()); });

        server.verify();
        assertThat(text.toString()).isEqualTo("Red");
    }

    /** A fetcher that answers every URL with one PNG, standing in for the real fetch. */
    private static com.dvarahq.providers.support.ImageFetcher stubFetcher(List<String> fetched) {
        return new com.dvarahq.providers.support.ImageFetcher(true, 1024, java.time.Duration.ofSeconds(1),
                java.util.Set.of("image/png")) {
            @Override
            public FetchedImage fetch(String url) {
                fetched.add(url);
                return new FetchedImage("image/png", "iVBORw0KGgo=");
            }
        };
    }

    // -------------------------------------------------------------------------
    // Embeddings
    // -------------------------------------------------------------------------

    private static final String EMBEDDING_REPLY = """
            {"object":"list","model":"nomic-embed-text",
             "data":[{"object":"embedding","index":0,"embedding":[0.1,-0.2,0.3]},
                     {"object":"embedding","index":1,"embedding":[0.4,0.5,-0.6]}],
             "usage":{"prompt_tokens":4,"total_tokens":4}}
            """;

    private static com.dvarahq.core.model.EmbeddingRequest embedding(Integer dimensions) {
        return com.dvarahq.core.model.EmbeddingRequest.builder()
                .model("ollama/nomic-embed-text")
                .input(List.of("hello", "world"))
                .dimensions(dimensions)
                .build();
    }

    /** The call goes to /v1/embeddings without the prefix, and the vectors come back in the OpenAI shape. */
    @Test
    void embed_postsToV1Embeddings_andMapsTheVectors() {
        server.expect(requestTo("http://localhost:11434/v1/embeddings"))
              .andExpect(method(HttpMethod.POST))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.model").value("nomic-embed-text"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.input[1]").value("world"))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.dimensions").doesNotExist())
              .andRespond(withSuccess(EMBEDDING_REPLY, MediaType.APPLICATION_JSON));

        var response = provider.embed(embedding(null));

        server.verify();
        assertThat(response.getObject()).isEqualTo("list");
        assertThat(response.getModel()).isEqualTo("ollama/nomic-embed-text");
        assertThat(response.getData()).hasSize(2);
        assertThat(response.getData().get(1).getIndex()).isEqualTo(1);
        assertThat(response.getData().get(1).getObject()).isEqualTo("embedding");
        assertThat(response.getData().get(1).getEmbedding()).containsExactly(0.4, 0.5, -0.6);
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(4);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(4);
    }

    /** dimensions travels only when the caller set it. */
    @Test
    void embed_dimensionsTravelWhenSet() {
        server.expect(requestTo(containsString("/v1/embeddings")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.dimensions").value(64))
              .andRespond(withSuccess(EMBEDDING_REPLY, MediaType.APPLICATION_JSON));
        provider.embed(embedding(64));
        server.verify();
    }

    /** A model that cannot embed is Ollama's refusal, returned with its status and naming the model. */
    @Test
    void embed_upstreamRefusal_namesTheModel() {
        server.expect(requestTo(containsString("/v1/embeddings")))
              .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
                      .body("{\"error\":{\"message\":\"This server does not support embeddings\"}}"));

        assertThatThrownBy(() -> provider.embed(embedding(null)))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("nomic-embed-text")
                .satisfies(e -> assertThat(((GatewayException) e).getUpstreamStatus()).isEqualTo(500));
        server.verify();
    }

    /** An empty reply is the upstream's failure, not a null pointer. */
    @Test
    void embed_emptyReply_isAProviderError() {
        server.expect(requestTo(containsString("/v1/embeddings")))
              .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> provider.embed(embedding(null)))
                .isInstanceOf(GatewayException.class)
                .extracting("code").isEqualTo("PROVIDER_ERROR");
    }

    /** Per-workspace: embeddings go to the workspace's own endpoint with its key, never the platform one. */
    @Test
    void perWorkspace_embeddingsGoToTheWorkspacesOwnEndpoint() {
        MockRestServiceServer[] tenant = new MockRestServiceServer[1];
        perWorkspace(tenant, ACME_ONLY);
        tenant[0].expect(requestTo("https://ollama.acme.test/v1/embeddings"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("Authorization", "Bearer acme-key"))
                .andRespond(withSuccess(EMBEDDING_REPLY, MediaType.APPLICATION_JSON));

        var response = as("acme", () -> provider.embed(embedding(null)));

        tenant[0].verify();
        server.verify();
        assertThat(response.getData()).hasSize(2);
        assertThatThrownBy(() -> as("globex", () -> provider.embed(embedding(null))))
                .isInstanceOf(GatewayException.class).hasMessageContaining("No Ollama endpoint");
    }

    /** Per-workspace: a redirect on the embeddings call is refused, not followed. */
    @Test
    void perWorkspace_anEmbeddingsRedirectIsRefused() {
        MockRestServiceServer[] tenant = new MockRestServiceServer[1];
        perWorkspace(tenant, ACME_ONLY);
        tenant[0].expect(requestTo("https://ollama.acme.test/v1/embeddings"))
                .andRespond(withStatus(HttpStatus.FOUND).header("Location", "http://169.254.169.254/latest/meta-data/"));

        assertThatThrownBy(() -> as("acme", () -> provider.embed(embedding(null))))
                .isInstanceOf(GatewayException.class).hasMessageContaining("redirect");
        tenant[0].verify();
    }

    /** A streamed call sends its tools, and the model's tool call comes back on the stream. */
    @Test
    void streamChat_toolsTravel_andTheCallComesBackOnTheStream() {
        server.expect(requestTo(containsString("/v1/chat/completions")))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.stream").value(true))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.tools[0].function.name").value("get_rate"))
              .andRespond(withSuccess("""
                      data: {"id":"s","model":"qwen3:4b-instruct","choices":[{"index":0,"delta":{"role":"assistant","content":"","tool_calls":[{"id":"call_1","index":0,"type":"function","function":{"name":"get_rate","arguments":"{}"}}]},"finish_reason":null}]}

                      data: {"id":"s","model":"qwen3:4b-instruct","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                      data: [DONE]

                      """, MediaType.TEXT_EVENT_STREAM));

        List<com.dvarahq.core.model.SseChunk> chunks = new java.util.ArrayList<>();
        provider.streamChat(ChatRequest.builder()
                .model("ollama/qwen3:4b-instruct")
                .messages(List.of(MultimodalMessage.user("Rate for Chicago to Dallas?")))
                .tools(List.of(com.dvarahq.core.model.ToolDefinition.builder().name("get_rate").build()))
                .build()).forEachRemaining(chunks::add);

        server.verify();
        assertThat(chunks.get(0).getToolCalls()).containsExactly(
                new com.dvarahq.core.model.ToolCallDelta(0, "call_1", "get_rate", "{}"));
        assertThat(chunks.get(1).getFinishReason()).isEqualTo("tool_calls");
    }
}
