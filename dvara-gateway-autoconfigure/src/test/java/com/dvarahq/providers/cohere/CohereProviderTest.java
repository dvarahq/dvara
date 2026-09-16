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
        assertThat(caps.supportsVision()).isFalse();
        // Cohere's v2 API supports tool calls, but this provider does not serialize tool
        // definitions or deserialize tool_calls, so the flag says what actually flows end to end.
        // The dispatcher's capability filter relies on it to keep a request carrying tools off
        // this provider.
        assertThat(caps.supportsToolCalls()).isFalse();
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

    @Test
    void chat_rejectsImageBlockWithUnsupportedCapability() {
        ChatRequest request = ChatRequest.builder()
                .model("command-r-plus")
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
