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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Converse reports {@code cacheReadInputTokens} and {@code cacheWriteInputTokens} outside
 * {@code inputTokens}; the gateway's input count includes them and reports them as its breakdown.
 */
class BedrockCacheUsageTest {

    private static final String MODEL = "bedrock/anthropic.claude-3-sonnet-20240229-v1";

    private static void assertSubsets(ChatResponse.Usage u) {
        assertThat(u.getPromptTokens()).isGreaterThanOrEqualTo(u.getCachedInputTokens() + u.getCacheWriteTokens());
        assertThat(u.getCompletionTokens()).isGreaterThanOrEqualTo(u.getReasoningTokens());
        assertThat(u.getTotalTokens()).isEqualTo(u.getPromptTokens() + u.getCompletionTokens());
    }

    private static final String USAGE = "{\"inputTokens\":20,\"outputTokens\":7,\"totalTokens\":2327,"
            + "\"cacheReadInputTokens\":2000,\"cacheWriteInputTokens\":300}";

    @Test
    @DisplayName("cache reads and writes are counted as input and reported as its breakdown")
    void nonStreamed() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://bedrock-runtime.us-east-1.amazonaws.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/converse")))
                .andRespond(withSuccess("{\"output\":{\"message\":{\"role\":\"assistant\",\"content\":[{\"text\":\"hi\"}]}},"
                        + "\"stopReason\":\"end_turn\",\"usage\":" + USAGE + "}", MediaType.APPLICATION_JSON));

        ChatResponse.Usage u = new BedrockProvider(builder.build()).chat(ChatRequest.builder()
                .model(MODEL).messages(List.of(MultimodalMessage.user("hi"))).build()).getUsage();

        assertThat(u.getPromptTokens()).isEqualTo(2320);
        assertThat(u.getCachedInputTokens()).isEqualTo(2000);
        assertThat(u.getCacheWriteTokens()).isEqualTo(300);
        assertThat(u.getTotalTokens()).isEqualTo(2327);
        assertSubsets(u);
    }

    @Test
    @DisplayName("streamed: the metadata frame's cache counts reach the terminal chunk")
    void streamed() {
        byte[] body = EventStreamFrames.concat(
                EventStreamFrames.event("messageStart", "{\"role\":\"assistant\"}"),
                EventStreamFrames.event("contentBlockDelta", "{\"delta\":{\"text\":\"Hi\"},\"contentBlockIndex\":0}"),
                EventStreamFrames.event("contentBlockStop", "{\"contentBlockIndex\":0}"),
                EventStreamFrames.event("messageStop", "{\"stopReason\":\"end_turn\"}"),
                EventStreamFrames.event("metadata", "{\"usage\":" + USAGE + "}"));
        RestClient.Builder builder = RestClient.builder().baseUrl("https://bedrock-runtime.us-east-1.amazonaws.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/converse-stream")))
                .andRespond(withSuccess(body, MediaType.parseMediaType(EventStreamFrames.EVENT_STREAM)));

        var it = new BedrockProvider(builder.build()).streamChat(ChatRequest.builder()
                .model(MODEL).messages(List.of(MultimodalMessage.user("hi"))).stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        it.forEachRemaining(chunks::add);

        ChatResponse.Usage u = chunks.get(chunks.size() - 1).getUsage();
        assertThat(u.getPromptTokens()).isEqualTo(2320);
        assertThat(u.getCachedInputTokens()).isEqualTo(2000);
        assertThat(u.getCacheWriteTokens()).isEqualTo(300);
        assertThat(u.getTotalTokens()).isEqualTo(2327);
        assertSubsets(u);
    }
}
