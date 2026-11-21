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
 * Gemini's {@code promptTokenCount} includes the cached content, and its thinking tokens
 * ({@code thoughtsTokenCount}) are billed as output but are not in {@code candidatesTokenCount}.
 */
class GeminiCacheUsageTest {

    private static void assertSubsets(ChatResponse.Usage u) {
        assertThat(u.getPromptTokens()).isGreaterThanOrEqualTo(u.getCachedInputTokens() + u.getCacheWriteTokens());
        assertThat(u.getCompletionTokens()).isGreaterThanOrEqualTo(u.getReasoningTokens());
        assertThat(u.getTotalTokens()).isEqualTo(u.getPromptTokens() + u.getCompletionTokens());
    }

    private static final String USAGE = "\"usageMetadata\":{\"promptTokenCount\":1500,\"cachedContentTokenCount\":1024,"
            + "\"candidatesTokenCount\":40,\"thoughtsTokenCount\":260,\"totalTokenCount\":1800}";

    @Test
    @DisplayName("cached content is part of the input; thinking tokens are output and reported as reasoning")
    void nonStreamed() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString(":generateContent")))
                .andRespond(withSuccess("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}],\"role\":\"model\"},"
                        + "\"finishReason\":\"STOP\"}]," + USAGE + "}", MediaType.APPLICATION_JSON));

        ChatResponse.Usage u = new GeminiProvider(builder.build()).chat(ChatRequest.builder()
                .model("gemini-2.5-pro").messages(List.of(MultimodalMessage.user("hi"))).build()).getUsage();

        assertThat(u.getPromptTokens()).isEqualTo(1500);
        assertThat(u.getCachedInputTokens()).isEqualTo(1024);
        assertThat(u.getCompletionTokens()).as("thinking is billed as output").isEqualTo(300);
        assertThat(u.getReasoningTokens()).isEqualTo(260);
        assertThat(u.getTotalTokens()).isEqualTo(1800);
        assertSubsets(u);
    }

    @Test
    @DisplayName("streamed: the terminal chunk carries the final usage")
    void streamed() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://generativelanguage.googleapis.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString(":streamGenerateContent")))
                .andRespond(withSuccess("""
                        data: {"candidates":[{"content":{"parts":[{"text":"Hel"}],"role":"model"}}],"usageMetadata":{"promptTokenCount":1500,"candidatesTokenCount":1,"totalTokenCount":1501}}

                        data: {"candidates":[{"content":{"parts":[{"text":"lo"}],"role":"model"},"finishReason":"STOP"}],%s}

                        """.formatted(USAGE), MediaType.TEXT_EVENT_STREAM));

        var it = new GeminiProvider(builder.build()).streamChat(ChatRequest.builder()
                .model("gemini-2.5-pro").messages(List.of(MultimodalMessage.user("hi"))).stream(true).build());
        List<SseChunk> chunks = new ArrayList<>();
        it.forEachRemaining(chunks::add);

        SseChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.isDone()).isTrue();
        ChatResponse.Usage u = last.getUsage();
        assertThat(u).as("a streamed call reports its exact usage").isNotNull();
        assertThat(u.getCachedInputTokens()).isEqualTo(1024);
        assertThat(u.getReasoningTokens()).isEqualTo(260);
        assertThat(u.getCompletionTokens()).isEqualTo(300);
        assertSubsets(u);
        assertThat(chunks.get(0).getUsage()).as("only the terminal chunk carries usage").isNull();
    }
}
