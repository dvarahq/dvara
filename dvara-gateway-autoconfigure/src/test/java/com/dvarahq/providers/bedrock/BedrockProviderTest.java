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
package com.dvarahq.providers.bedrock;

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

class BedrockProviderTest {

    private MockRestServiceServer server;
    private BedrockProvider       provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://bedrock-runtime.us-east-1.amazonaws.com");
        server   = MockRestServiceServer.bindTo(builder).build();
        provider = new BedrockProvider(builder.build());
    }

    // -------------------------------------------------------------------------
    // Routing / capability
    // -------------------------------------------------------------------------

    @Test
    void supports_bedrockPrefixModel_returnsTrue() {
        ChatRequest request = ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of()).build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_bedrockTitan_returnsTrue() {
        ChatRequest request = ChatRequest.builder()
                .model("bedrock/amazon.titan-text-express-v1")
                .messages(List.of()).build();
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
        assertThat(provider.supportsEmbedding("bedrock/amazon.titan-embed-text-v2:0")).isFalse();
    }

    @Test
    void name_returnsBedrock() {
        assertThat(provider.name()).isEqualTo("bedrock");
    }

    // -------------------------------------------------------------------------
    // chat() — happy path
    // -------------------------------------------------------------------------

    @Test
    void chat_stop_travelsAsStopSequences() {
        server.expect(requestTo(containsString("/model/anthropic.claude-3-sonnet-20240229-v1/converse")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(org.hamcrest.Matchers.containsString("\"stopSequences\":[\"END\"]")))
              .andRespond(withSuccess(converseSuccessBody("end_turn", "hi", 1, 1), MediaType.APPLICATION_JSON));

        provider.chat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stop(List.of("END"))
                .build());
        server.verify();
    }

    @Test
    void chat_seed_isRefusedNotDropped() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> provider.chat(ChatRequest.builder()
                        .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                        .messages(List.of(MultimodalMessage.user("Hi")))
                        .seed(42L)
                        .build()))
                .isInstanceOf(com.dvarahq.core.exception.GatewayException.class)
                .hasMessageContaining("seed is not supported by Bedrock")
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                        ((com.dvarahq.core.exception.GatewayException) e).getCode()).isEqualTo("UNSUPPORTED_CAPABILITY"));
    }

    @Test
    void chat_successResponse_returnsMappedChatResponse() {
        server.expect(requestTo(containsString("/model/anthropic.claude-3-sonnet-20240229-v1/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(converseSuccessBody("end_turn", "Paris is the capital.", 10, 5),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = chatRequest("bedrock/anthropic.claude-3-sonnet-20240229-v1", "Capital of France?");
        ChatResponse response = provider.chat(request);

        assertThat(response.getModel()).isEqualTo("bedrock/anthropic.claude-3-sonnet-20240229-v1");
        assertThat(response.getChoices()).hasSize(1);
        assertThat(response.getChoices().get(0).getMessage().getRole()).isEqualTo("assistant");
        assertThat(response.getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(5);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(15);

        server.verify();
    }

    @Test
    void chat_aResponseWithNoUsageBlock_leavesUsageNullRatherThanZeroed() {
        // A zeroed block looks like a call that cost nothing, and the metering path drops a
        // response whose total is not positive. Null is what the estimator fallback and the
        // estimated flag exist for.
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(
                      "{\"stopReason\":\"end_turn\",\"output\":{\"message\":{\"role\":\"assistant\","
                      + "\"content\":[{\"text\":\"hi\"}]}}}",
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(
                chatRequest("bedrock/anthropic.claude-3-sonnet-20240229-v1", "hello"));

        assertThat(response.getUsage())
                .as("no usage block upstream means no usage, not three zeros")
                .isNull();

        server.verify();
    }

    @Test
    void chat_bedrockPrefixIsStrippedFromModelBeforeSending() {
        // The "bedrock/" prefix is stripped before the Bedrock URI is built.
        server.expect(requestTo(containsString("/model/amazon.titan-text-express-v1/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(converseSuccessBody("end_turn", "ok", 4, 3),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("bedrock/amazon.titan-text-express-v1", "Hi"));

        assertThat(response.getModel()).isEqualTo("bedrock/amazon.titan-text-express-v1");

        server.verify();
    }

    @Test
    void chat_endTurnStopReason_isMappedToStop() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(converseSuccessBody("end_turn", "done", 5, 2),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("bedrock/anthropic.claude-3-sonnet-20240229-v1", "Hello"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");

        server.verify();
    }

    @Test
    void chat_maxTokensStopReason_isMappedToLength() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(converseSuccessBody("max_tokens", "truncated...", 5, 100),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("bedrock/anthropic.claude-3-sonnet-20240229-v1", "Write a novel"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("length");

        server.verify();
    }

    @Test
    void chat_usageTokensAreMappedCorrectly() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(converseSuccessBody("end_turn", "hi", 42, 7),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("bedrock/anthropic.claude-3-sonnet-20240229-v1", "Hi"));

        assertThat(response.getUsage().getPromptTokens()).isEqualTo(42);
        assertThat(response.getUsage().getCompletionTokens()).isEqualTo(7);
        assertThat(response.getUsage().getTotalTokens()).isEqualTo(49);

        server.verify();
    }

    @Test
    void chat_systemMessageIsExtractedFromMessages() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(converseSuccessBody("end_turn", "Sure!", 15, 4),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
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
    void chat_contentFilteredStopReason_isMappedToContentFilter() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(converseSuccessBody("content_filtered", "Content blocked.", 5, 3),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("bedrock/anthropic.claude-3-sonnet-20240229-v1", "Something"));

        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("content_filter");

        server.verify();
    }

    @Test
    void chat_assistantContentIsMapped() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(converseSuccessBody("end_turn", "Hello there!", 3, 2),
                      MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(chatRequest("bedrock/anthropic.claude-3-sonnet-20240229-v1", "Say hello"));

        MultimodalMessage message = response.getChoices().get(0).getMessage();
        assertThat(message.getRole()).isEqualTo("assistant");
        assertThat(message.getContent()).hasSize(1);

        server.verify();
    }

    // -------------------------------------------------------------------------
    // chat() — errors
    // -------------------------------------------------------------------------

    @Test
    void chat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.chat(chatRequest("bedrock/anthropic.claude-3-sonnet-20240229-v1", "Hello")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Bedrock API error")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    @Test
    void chat_unauthorizedError_throwsGatewayException() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withUnauthorizedRequest());

        assertThatThrownBy(() -> provider.chat(chatRequest("bedrock/anthropic.claude-3-sonnet-20240229-v1", "Hello")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid API key")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    // -------------------------------------------------------------------------
    // streamChat()
    // -------------------------------------------------------------------------

    private static final MediaType EVENT_STREAM = MediaType.parseMediaType(EventStreamFrames.EVENT_STREAM);

    private static List<SseChunk> drain(Iterator<SseChunk> iterator) {
        List<SseChunk> chunks = new ArrayList<>();
        while (iterator.hasNext()) chunks.add(iterator.next());
        return chunks;
    }

    /**
     * The real wire format: binary Amazon Event Stream frames whose event name is a frame header
     * and whose payload is the bare structure, with metadata (the exact usage) after messageStop.
     */
    @Test
    void streamChat_decodesConverseStreamFrames_andEmitsUsageOnTheFinalChunk() {
        byte[] body = EventStreamFrames.concat(
                EventStreamFrames.event("messageStart", "{\"role\":\"assistant\"}"),
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"text\":\"Hello\"},\"contentBlockIndex\":0}"),
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"text\":\" world\"},\"contentBlockIndex\":0}"),
                EventStreamFrames.event("contentBlockStop", "{\"contentBlockIndex\":0}"),
                EventStreamFrames.event("messageStop", "{\"stopReason\":\"end_turn\"}"),
                EventStreamFrames.event("metadata", "{\"usage\":{\"inputTokens\":10,\"outputTokens\":2,\"totalTokens\":12},\"metrics\":{\"latencyMs\":123}}"));

        server.expect(requestTo(containsString("/model/anthropic.claude-3-sonnet-20240229-v1/converse-stream")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(body, EVENT_STREAM));

        List<SseChunk> chunks = drain(provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Hi")))
                .stream(true)
                .build()));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getDelta()).isEqualTo("Hello");
        assertThat(chunks.get(0).getModel()).isEqualTo("bedrock/anthropic.claude-3-sonnet-20240229-v1");
        assertThat(chunks.get(0).isDone()).isFalse();
        assertThat(chunks.get(1).getDelta()).isEqualTo(" world");
        assertThat(chunks.get(2).isDone()).isTrue();
        assertThat(chunks.get(2).getFinishReason()).isEqualTo("stop");
        assertThat(chunks.get(2).getUsage()).as("the exact usage from metadata, after messageStop").isNotNull();
        assertThat(chunks.get(2).getUsage().getPromptTokens()).isEqualTo(10);
        assertThat(chunks.get(2).getUsage().getCompletionTokens()).isEqualTo(2);
        assertThat(chunks.get(2).getUsage().getTotalTokens()).isEqualTo(12);

        server.verify();
    }

    /** In JSON-schema mode the structured output arrives as tool-use input deltas: partial JSON strings. */
    @Test
    void streamChat_jsonSchemaMode_routesToolUseInputDeltas() {
        byte[] body = EventStreamFrames.concat(
                EventStreamFrames.event("contentBlockStart", "{\"start\":{\"toolUse\":{\"toolUseId\":\"tu-1\",\"name\":\"structured_output\"}},\"contentBlockIndex\":0}"),
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"toolUse\":{\"input\":\"{\\\"name\\\"\"}},\"contentBlockIndex\":0}"),
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"toolUse\":{\"input\":\": \\\"John\\\"}\"}},\"contentBlockIndex\":0}"),
                EventStreamFrames.event("contentBlockStop", "{\"contentBlockIndex\":0}"),
                EventStreamFrames.event("messageStop", "{\"stopReason\":\"tool_use\"}"),
                EventStreamFrames.event("metadata", "{\"usage\":{\"inputTokens\":20,\"outputTokens\":6,\"totalTokens\":26}}"));

        server.expect(requestTo(containsString("/converse-stream")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(body, EVENT_STREAM));

        List<SseChunk> chunks = drain(provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("return a person")))
                .stream(true)
                .responseFormat(new ResponseFormat.JsonSchema("person", Map.of(
                        "type", "object",
                        "properties", Map.of("name", Map.of("type", "string"))), false))
                .build()));

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getDelta()).isEqualTo("{\"name\"");
        assertThat(chunks.get(1).getDelta()).isEqualTo(": \"John\"}");
        assertThat(chunks.get(2).isDone()).isTrue();
        assertThat(chunks.get(2).getFinishReason()).as("tool_use is how structured output ends; the caller sees stop").isEqualTo("stop");
        assertThat(chunks.get(2).getUsage().getTotalTokens()).isEqualTo(26);

        server.verify();
    }

    /** A toolUse block after a text block: contentBlockIndex 1 is tool call 0. */
    @Test
    void streamChat_toolUseBlock_becomesStreamedToolCallFragments() {
        byte[] body = EventStreamFrames.concat(
                EventStreamFrames.event("messageStart", "{\"role\":\"assistant\"}"),
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"text\":\"Let me look.\"},\"contentBlockIndex\":0}"),
                EventStreamFrames.event("contentBlockStop", "{\"contentBlockIndex\":0}"),
                EventStreamFrames.event("contentBlockStart", "{\"start\":{\"toolUse\":{\"toolUseId\":\"tu-9\",\"name\":\"get_weather\"}},\"contentBlockIndex\":1}"),
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"toolUse\":{\"input\":\"{\\\"city\\\"\"}},\"contentBlockIndex\":1}"),
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"toolUse\":{\"input\":\": \\\"Paris\\\"}\"}},\"contentBlockIndex\":1}"),
                EventStreamFrames.event("contentBlockStop", "{\"contentBlockIndex\":1}"),
                EventStreamFrames.event("messageStop", "{\"stopReason\":\"tool_use\"}"),
                EventStreamFrames.event("metadata", "{\"usage\":{\"inputTokens\":20,\"outputTokens\":6,\"totalTokens\":26}}"));
        server.expect(requestTo(containsString("/converse-stream")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess(body, EVENT_STREAM));

        List<SseChunk> chunks = drain(provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("weather in Paris?")))
                .stream(true)
                .build()));

        assertThat(chunks).hasSize(5);
        assertThat(chunks.get(0).getDelta()).isEqualTo("Let me look.");
        assertThat(chunks.get(1).getToolCalls()).containsExactly(new ToolCallDelta(0, "tu-9", "get_weather", null));
        assertThat(chunks.get(2).getToolCalls()).containsExactly(new ToolCallDelta(0, null, null, "{\"city\""));
        assertThat(chunks.get(3).getToolCalls()).containsExactly(new ToolCallDelta(0, null, null, ": \"Paris\"}"));
        assertThat(chunks.get(4).isDone()).isTrue();
        assertThat(chunks.get(4).getFinishReason()).isEqualTo("tool_calls");
        assertThat(chunks.get(4).getUsage().getTotalTokens()).isEqualTo(26);
        server.verify();
    }

    /** toolUse input for a block that was never opened is a provider error, not a nameless call. */
    @Test
    void streamChat_toolUseInputOnAnUnopenedBlock_isAProviderError() {
        byte[] body = EventStreamFrames.concat(
                EventStreamFrames.event("messageStart", "{\"role\":\"assistant\"}"),
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"toolUse\":{\"input\":\"{}\"}},\"contentBlockIndex\":3}"),
                EventStreamFrames.event("messageStop", "{\"stopReason\":\"tool_use\"}"));
        server.expect(requestTo(containsString("/converse-stream")))
              .andRespond(withSuccess(body, EVENT_STREAM));

        Iterator<SseChunk> it = provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("x")))
                .stream(true)
                .build());
        assertThatThrownBy(it::hasNext).isInstanceOf(GatewayException.class).hasMessageContaining("never opened");
    }

    /** A modeled mid-stream exception is a provider error, not an empty stream. */
    @Test
    void streamChat_midStreamException_isAProviderError() {
        byte[] body = EventStreamFrames.concat(
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"text\":\"Hel\"},\"contentBlockIndex\":0}"),
                EventStreamFrames.exception("throttlingException", "{\"message\":\"Too many requests\"}"));
        server.expect(requestTo(containsString("/converse-stream"))).andRespond(withSuccess(body, EVENT_STREAM));

        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());
        assertThat(iterator.next().getDelta()).isEqualTo("Hel");
        assertThatThrownBy(iterator::hasNext)
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("throttlingException")
                .hasMessageContaining("Too many requests")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));
    }

    /** An unmodeled error frame, headers only, is a provider error too. */
    @Test
    void streamChat_unmodeledErrorFrame_isAProviderError() {
        server.expect(requestTo(containsString("/converse-stream")))
              .andRespond(withSuccess(EventStreamFrames.error("InternalError", "boom"), EVENT_STREAM));
        Iterator<SseChunk> iterator = provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());
        assertThatThrownBy(iterator::hasNext).isInstanceOf(GatewayException.class)
                .hasMessageContaining("InternalError").hasMessageContaining("boom");
    }

    /** A stream that ends before metadata still owes its consumer the final chunk. */
    @Test
    void streamChat_endWithoutMetadata_stillFinishes() {
        byte[] body = EventStreamFrames.concat(
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"text\":\"Hi\"},\"contentBlockIndex\":0}"),
                EventStreamFrames.event("messageStop", "{\"stopReason\":\"max_tokens\"}"));
        server.expect(requestTo(containsString("/converse-stream"))).andRespond(withSuccess(body, EVENT_STREAM));
        List<SseChunk> chunks = drain(provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build()));
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).isDone()).isTrue();
        assertThat(chunks.get(1).getFinishReason()).isEqualTo("length");
        assertThat(chunks.get(1).getUsage()).isNull();
    }

    /** The iterator owns the response: closed once, on exhaustion, on error, or explicitly. */
    @Test
    void streamIterator_closesTheResponseExactlyOnce() {
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        AutoCloseable response = closes::incrementAndGet;

        byte[] ok = EventStreamFrames.concat(
                EventStreamFrames.event("messageStop", "{\"stopReason\":\"end_turn\"}"),
                EventStreamFrames.event("metadata", "{\"usage\":{\"inputTokens\":1,\"outputTokens\":1,\"totalTokens\":2}}"));
        BedrockProvider.BedrockStreamIterator it = new BedrockProvider.BedrockStreamIterator(
                new java.io.ByteArrayInputStream(ok), response, "bedrock/m", false);
        drain(it);
        it.close();
        assertThat(closes.get()).as("exhaustion closed it; the explicit close was a no-op").isEqualTo(1);

        closes.set(0);
        byte[] bad = EventStreamFrames.event("messageStop", "{}");
        bad[bad.length - 1] ^= 0x55;
        BedrockProvider.BedrockStreamIterator failing = new BedrockProvider.BedrockStreamIterator(
                new java.io.ByteArrayInputStream(bad), response, "bedrock/m", false);
        assertThatThrownBy(failing::hasNext).isInstanceOf(GatewayException.class).hasMessageContaining("message CRC");
        failing.close();
        assertThat(closes.get()).as("the error closed it; the explicit close was a no-op").isEqualTo(1);

        closes.set(0);
        BedrockProvider.BedrockStreamIterator abandoned = new BedrockProvider.BedrockStreamIterator(
                new java.io.ByteArrayInputStream(ok), response, "bedrock/m", false);
        abandoned.close();
        abandoned.close();
        assertThat(closes.get()).as("explicit close, idempotent").isEqualTo(1);
    }

    @Test
    void streamChat_serverError_throwsGatewayException() {
        server.expect(requestTo(containsString("/converse-stream")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withServerError());

        assertThatThrownBy(() -> provider.streamChat(ChatRequest.builder()
                        .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                        .messages(List.of(MultimodalMessage.user("Hi")))
                        .stream(true)
                        .build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Bedrock streaming error")
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("PROVIDER_ERROR"));

        server.verify();
    }

    /** On an HTTP error no iterator exists to own the response, so the opener closes it — once. */
    @Test
    void streamChat_serverError_closesTheResponseExactlyOnce() {
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.http.client.ClientHttpResponse res = new org.springframework.http.client.ClientHttpResponse() {
            @Override public org.springframework.http.HttpStatusCode getStatusCode() { return org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE; }
            @Override public String getStatusText() { return "Service Unavailable"; }
            @Override public void close() { closes.incrementAndGet(); }
            @Override public java.io.InputStream getBody() { return java.io.InputStream.nullInputStream(); }
            @Override public org.springframework.http.HttpHeaders getHeaders() { return new org.springframework.http.HttpHeaders(); }
        };
        assertThatThrownBy(() -> BedrockProvider.openStream(res, "bedrock/m", false))
                .isInstanceOf(GatewayException.class).hasMessageContaining("503");
        assertThat(closes.get()).isEqualTo(1);
    }

    /** A 200 whose body cannot even be opened: no iterator exists, so the opener closes it — once. */
    @Test
    void streamChat_bodyReadFailure_closesTheResponseExactlyOnce() {
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        org.springframework.http.client.ClientHttpResponse res = new org.springframework.http.client.ClientHttpResponse() {
            @Override public org.springframework.http.HttpStatusCode getStatusCode() { return org.springframework.http.HttpStatus.OK; }
            @Override public String getStatusText() { return "OK"; }
            @Override public void close() { closes.incrementAndGet(); }
            @Override public java.io.InputStream getBody() throws java.io.IOException { throw new java.io.IOException("connection reset"); }
            @Override public org.springframework.http.HttpHeaders getHeaders() { return new org.springframework.http.HttpHeaders(); }
        };
        assertThatThrownBy(() -> BedrockProvider.openStream(res, "bedrock/m", false))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("connection reset");
        assertThat(closes.get()).isEqualTo(1);
    }

    /** An empty 200 body, or a connection closed between frames, is an error — not a short answer. */
    @Test
    void streamChat_endBeforeMessageStop_isAProviderError() {
        server.expect(requestTo(containsString("/converse-stream"))).andRespond(withSuccess(new byte[0], EVENT_STREAM));
        Iterator<SseChunk> empty = provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());
        assertThatThrownBy(empty::hasNext).isInstanceOf(GatewayException.class).hasMessageContaining("before messageStop");

        server.reset();
        server.expect(requestTo(containsString("/converse-stream"))).andRespond(withSuccess(
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"text\":\"Hel\"},\"contentBlockIndex\":0}"), EVENT_STREAM));
        Iterator<SseChunk> cut = provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());
        assertThat(cut.next().getDelta()).isEqualTo("Hel");
        assertThatThrownBy(cut::hasNext).isInstanceOf(GatewayException.class).hasMessageContaining("before messageStop");
    }

    /** Metadata is specified to follow messageStop; one that arrives first is a broken stream. */
    @Test
    void streamChat_metadataBeforeMessageStop_isAProviderError() {
        server.expect(requestTo(containsString("/converse-stream"))).andRespond(withSuccess(
                EventStreamFrames.event("metadata", "{\"usage\":{\"inputTokens\":1,\"outputTokens\":1,\"totalTokens\":2}}"), EVENT_STREAM));
        Iterator<SseChunk> it = provider.streamChat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Hi"))).stream(true).build());
        assertThatThrownBy(it::hasNext).isInstanceOf(GatewayException.class).hasMessageContaining("metadata before messageStop");
    }

    // -------------------------------------------------------------------------
    // Multimodal (vision)
    // -------------------------------------------------------------------------

    @Test
    void chat_imageBlockInUserMessage_isForwardedAsNativeBedrockImage() {
        // An ImageBlock reaches the Bedrock Converse API as an {image: {format, source: {bytes}}}
        // content entry alongside the {text: "..."} entry. The format field carries the bare
        // subtype ("png"), not the full MIME type.
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("\"image\"")))
              .andExpect(content().string(containsString("\"format\":\"png\"")))
              .andExpect(content().string(containsString("\"bytes\":\"iVBORw0KGgo=\"")))
              .andExpect(content().string(containsString("\"text\":\"Describe this image\"")))
              .andRespond(withSuccess(converseSuccessBody("end_turn", "I see a small image.", 50, 8),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
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
    void chat_jsonObjectFormat_injectsSystemMessage() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("Respond with valid JSON")))
              .andRespond(withSuccess(converseSuccessBody("end_turn", "valid json output", 10, 5),
                      MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Return JSON")))
                .responseFormat(new ResponseFormat.JsonObject())
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getChoices()).hasSize(1);

        server.verify();
    }

    @Test
    void chat_jsonSchemaFormat_addsToolConfig() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andExpect(content().string(containsString("toolConfig")))
              .andExpect(content().string(containsString("structured_output")))
              .andRespond(withSuccess("""
                      {
                        "output": {"message": {"role": "assistant", "content": [
                          {"toolUse": {"toolUseId": "tu1", "name": "structured_output", "input": {"key": "value"}}}
                        ]}},
                        "stopReason": "tool_use",
                        "usage": {"inputTokens": 10, "outputTokens": 5, "totalTokens": 15}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonSchema("test", Map.of("type", "object"), false))
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("stop");
        String text = response.getChoices().get(0).getMessage().getContent().get(0).toString();
        assertThat(text).contains("key");
        assertThat(text).contains("value");

        server.verify();
    }

    @Test
    void chat_jsonSchemaStrict_setsDowngradeHeader() {
        server.expect(requestTo(containsString("/converse")))
              .andExpect(method(HttpMethod.POST))
              .andRespond(withSuccess("""
                      {
                        "output": {"message": {"role": "assistant", "content": [
                          {"toolUse": {"toolUseId": "tu2", "name": "structured_output", "input": {"key": "val"}}}
                        ]}},
                        "stopReason": "tool_use",
                        "usage": {"inputTokens": 10, "outputTokens": 5, "totalTokens": 15}
                      }
                      """, MediaType.APPLICATION_JSON));

        ChatRequest request = ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet-20240229-v1")
                .messages(List.of(MultimodalMessage.user("Return structured")))
                .responseFormat(new ResponseFormat.JsonSchema("test", Map.of("type", "object"), true))
                .build();

        ChatResponse response = provider.chat(request);
        assertThat(response.getGatewayHeaders()).containsEntry("X-Gateway-Strict-Downgraded", "true");

        server.verify();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String converseSuccessBody(String stopReason, String text,
                                               int inputTokens, int outputTokens) {
        return """
                {
                  "output": {
                    "message": {
                      "role": "assistant",
                      "content": [{"text": "%s"}]
                    }
                  },
                  "stopReason": "%s",
                  "usage": {
                    "inputTokens": %d,
                    "outputTokens": %d,
                    "totalTokens": %d
                  }
                }
                """.formatted(text, stopReason, inputTokens, outputTokens,
                inputTokens + outputTokens);
    }

    @Test
    void chat_withTools_sendsToolConfigAndParsesToolUse() {
        // Tools go out under toolConfig.tools[].toolSpec; a toolUse block comes back as a
        // tool call with finish_reason tool_calls.
        server.expect(requestTo(containsString("/converse")))
                .andExpect(jsonPath("$.toolConfig.tools[0].toolSpec.name").value("get_weather"))
                .andRespond(withSuccess("""
                        {"output":{"message":{"role":"assistant","content":[
                            {"toolUse":{"toolUseId":"tu_1","name":"get_weather","input":{"city":"Paris"}}}]}},
                         "stopReason":"tool_use",
                         "usage":{"inputTokens":5,"outputTokens":3,"totalTokens":8}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = provider.chat(ChatRequest.builder()
                .model("bedrock/anthropic.claude-3-sonnet")
                .messages(List.of(MultimodalMessage.user("weather in Paris?")))
                .tools(List.of(ToolDefinition.builder().name("get_weather")
                        .description("Get weather").parameters(Map.of("type", "object")).build()))
                .build());

        server.verify();
        var calls = response.getChoices().get(0).getMessage().getToolCalls();
        assertThat(calls).hasSize(1);
        assertThat(calls.get(0).getId()).isEqualTo("tu_1");
        assertThat(calls.get(0).getName()).isEqualTo("get_weather");
        assertThat(calls.get(0).getArguments()).contains("Paris");
        assertThat(response.getChoices().get(0).getFinishReason()).isEqualTo("tool_calls");
    }
}