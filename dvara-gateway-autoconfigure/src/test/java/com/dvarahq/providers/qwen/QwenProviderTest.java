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
package com.dvarahq.providers.qwen;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The full test for the OpenAI-compatible providers, using Qwen. DeepSeek, Moonshot, ChatGLM and
 * Grok have lighter tests because the shared chat, streaming and response-mapping behaviour of
 * {@link com.dvarahq.providers.openai.AbstractOpenAiCompatibleProvider} is tested here and in
 * {@code OpenAiProviderTest}.
 *
 * <p>What this file checks: {@code supports()} prefix matching for {@code qwen-*} models, and that
 * other providers' model names are not claimed; the {@code "Qwen"} label on
 * {@link GatewayException} messages from the chat, streaming and error paths; that
 * {@code response_format} is forwarded; the conservative {@code capabilities()} declaration; and
 * that {@code listModels()} returns an empty list rather than throwing on an upstream error.
 */
class QwenProviderTest {

    private MockRestServiceServer server;
    private QwenProvider           provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new QwenProvider(builder.build());
    }

    // ---------- supports() ----------

    @Test
    void supports_qwenChatModel_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("qwen2.5-72b-instruct").messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_qwenMaxModel_returnsTrue() {
        ChatRequest request = ChatRequest.builder().model("qwen-max").messages(List.of()).build();
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
    void name_returnsQwen() {
        assertThat(provider.name()).isEqualTo("qwen");
    }

    // ---------- chat() ----------

    @Test
    void chat_successResponse_returnsMappedChatResponse() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-qwen-abc",
                        "object": "chat.completion",
                        "created": 1704067200,
                        "model": "qwen2.5-72b-instruct",
                        "choices": [{
                          "index": 0,
                          "message": {"role": "assistant", "content": "Beijing"},
                          "finish_reason": "stop"
                        }],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 1, "total_tokens": 11}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("qwen2.5-72b-instruct")
                .messages(List.of(MultimodalMessage.user("Capital of China?")))
                .build();

        ChatResponse response = provider.chat(request);

        assertThat(response.getId()).isEqualTo("chatcmpl-qwen-abc");
        assertThat(response.getModel()).isEqualTo("qwen2.5-72b-instruct");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(11);

        server.verify();
    }

    @Test
    void chat_serverError_throwsGatewayException_withQwenLabel() {
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("qwen-max")
                        .messages(List.of(MultimodalMessage.user("Hello")))
                        .build()))
                .isInstanceOf(GatewayException.class)
                // The upstreamLabel override on QwenProvider supplies this string.
                .hasMessageContaining("Qwen API error")
                .extracting("code").isEqualTo("PROVIDER_ERROR");

        server.verify();
    }

    // ---------- streaming ----------

    @Test
    void streamChat_parsesQwenSseChunks() {
        String sseResponse = """
                data: {"id":"chatcmpl-qwen-1","object":"chat.completion.chunk","created":1704067200,"model":"qwen2.5","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello"},"finish_reason":null}]}

                data: {"id":"chatcmpl-qwen-1","object":"chat.completion.chunk","created":1704067200,"model":"qwen2.5","choices":[{"index":0,"delta":{"content":" Qwen"},"finish_reason":null}]}

                data: {"id":"chatcmpl-qwen-1","object":"chat.completion.chunk","created":1704067200,"model":"qwen2.5","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(sseResponse, MediaType.TEXT_EVENT_STREAM));

        ChatRequest request = ChatRequest.builder()
                .model("qwen2.5")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stream(true)
                .build();

        Iterator<SseChunk> iterator = provider.streamChat(request);
        List<SseChunk> chunks = new ArrayList<>();
        while (iterator.hasNext()) chunks.add(iterator.next());

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getDelta()).isEqualTo("Hello");
        assertThat(chunks.get(1).getDelta()).isEqualTo(" Qwen");
        assertThat(chunks.get(2).isDone()).isTrue();

        server.verify();
    }

    // ---------- response_format pass-through (smoke test) ----------

    @Test
    void chat_jsonObjectFormat_passesResponseFormatInBody() {
        // A smoke test: the abstract base's applyResponseFormat() runs for a subclass and the
        // call still goes through. The body itself is not inspected.
        server.expect(requestTo(containsString("/chat/completions")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                      {
                        "id": "chatcmpl-qwen-rf", "object": "chat.completion", "created": 1704067200,
                        "model": "qwen-max",
                        "choices": [{"index": 0, "message": {"role": "assistant", "content": "{\\"ok\\":true}"}, "finish_reason": "stop"}],
                        "usage": {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("qwen-max")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getId()).isEqualTo("chatcmpl-qwen-rf");

        server.verify();
    }

    // ---------- capabilities() ----------

    @Test
    void capabilities_returnsConservativeDeclaration() {
        // The conservative declaration: streaming on, everything else off. Widening a
        // capability (vision, tools, structured outputs) requires a deliberate change here.
        ProviderCapabilities caps = provider.capabilities();
        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.supportsVision()).isFalse();
        assertThat(caps.supportsToolCalls()).isFalse();
        assertThat(caps.supportsStreamingToolCalls()).isFalse();
        assertThat(caps.supportsStructuredOutputs()).isFalse();
        assertThat(caps.supportsJsonMode()).isFalse();
        assertThat(caps.maxContextTokens()).isEqualTo(32_768);
    }

    // ---------- listModels() ----------

    @Test
    void listModels_returnsEmptyList_onUpstreamError() {
        // Qwen's compatible-mode endpoint may not expose /models, or may answer in a different
        // shape. listModels() tolerates that rather than throwing, so GET /v1/models on the
        // gateway does not fail for every caller.
        server.expect(requestTo(containsString("/models")))
              .andRespond(withServerError());

        assertThat(provider.listModels()).isEmpty();
    }

    @Test
    void listModels_parsesOaiCompatResponse() {
        server.expect(requestTo(containsString("/models")))
              .andRespond(withSuccess("""
                      {
                        "object": "list",
                        "data": [
                          {"id": "qwen2.5-72b-instruct", "owned_by": "alibaba", "created": 1700000000},
                          {"id": "qwen-max", "owned_by": "alibaba", "created": 1700000001}
                        ]
                      }
                      """, MediaType.APPLICATION_JSON));

        var models = provider.listModels();
        assertThat(models).hasSize(2);
        assertThat(models.get(0).id()).isEqualTo("qwen2.5-72b-instruct");
        assertThat(models.get(0).ownedBy()).isEqualTo("alibaba");
    }
}