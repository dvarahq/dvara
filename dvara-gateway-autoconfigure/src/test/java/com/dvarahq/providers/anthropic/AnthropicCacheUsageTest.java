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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Anthropic's {@code input_tokens} leaves out the tokens read from and written to the prompt cache,
 * so the gateway's input count adds them back and reports them as its breakdown.
 */
class AnthropicCacheUsageTest {

    private static void assertSubsets(ChatResponse.Usage u) {
        assertThat(u.getPromptTokens()).isGreaterThanOrEqualTo(u.getCachedInputTokens() + u.getCacheWriteTokens());
        assertThat(u.getCompletionTokens()).isGreaterThanOrEqualTo(u.getReasoningTokens());
        assertThat(u.getTotalTokens()).isEqualTo(u.getPromptTokens() + u.getCompletionTokens());
    }

    @Test
    @DisplayName("cache reads and writes are counted as input and reported as its breakdown")
    void nonStreamed() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.anthropic.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/v1/messages")))
                .andRespond(withSuccess("""
                        {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
                         "stop_reason":"end_turn","content":[{"type":"text","text":"hi"}],
                         "usage":{"input_tokens":20,"output_tokens":7,
                                  "cache_read_input_tokens":2000,"cache_creation_input_tokens":300}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse.Usage u = new AnthropicProvider(builder.build()).chat(ChatRequest.builder()
                .model("claude-sonnet-4-5").messages(List.of(MultimodalMessage.user("hi"))).build()).getUsage();

        assertThat(u.getPromptTokens()).isEqualTo(2320);
        assertThat(u.getCachedInputTokens()).isEqualTo(2000);
        assertThat(u.getCacheWriteTokens()).isEqualTo(300);
        assertThat(u.getCompletionTokens()).isEqualTo(7);
        assertThat(u.getTotalTokens()).isEqualTo(2327);
        assertSubsets(u);
    }

    @Test
    @DisplayName("a usage block with no cache fields prices exactly as before")
    void noCacheFields() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.anthropic.com");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/v1/messages")))
                .andRespond(withSuccess("""
                        {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
                         "stop_reason":"end_turn","content":[{"type":"text","text":"hi"}],
                         "usage":{"input_tokens":20,"output_tokens":7}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse.Usage u = new AnthropicProvider(builder.build()).chat(ChatRequest.builder()
                .model("claude-sonnet-4-5").messages(List.of(MultimodalMessage.user("hi"))).build()).getUsage();

        assertThat(u.getPromptTokens()).isEqualTo(20);
        assertThat(u.getTotalTokens()).isEqualTo(27);
        assertThat(u.getCachedInputTokens()).isZero();
        assertThat(u.getCacheWriteTokens()).isZero();
    }

    @Test
    @DisplayName("streamed: the terminal chunk carries the usage from message_start and message_delta")
    void streamed() {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":20,"output_tokens":1,"cache_read_input_tokens":2000,"cache_creation_input_tokens":300}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi"}}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":7}}

                event: message_stop
                data: {"type":"message_stop"}

                """;
        var it = new AnthropicProvider.AnthropicSseIterator(new BufferedReader(new StringReader(sse)),
                "claude-sonnet-4-5", false);
        List<SseChunk> chunks = new ArrayList<>();
        it.forEachRemaining(chunks::add);

        SseChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.isDone()).isTrue();
        ChatResponse.Usage u = last.getUsage();
        assertThat(u).as("a streamed call reports its exact usage").isNotNull();
        assertThat(u.getPromptTokens()).isEqualTo(2320);
        assertThat(u.getCachedInputTokens()).isEqualTo(2000);
        assertThat(u.getCacheWriteTokens()).isEqualTo(300);
        assertThat(u.getCompletionTokens()).isEqualTo(7);
        assertSubsets(u);
    }
}
