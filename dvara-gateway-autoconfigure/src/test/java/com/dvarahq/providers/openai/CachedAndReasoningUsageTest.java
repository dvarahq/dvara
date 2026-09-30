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
package com.dvarahq.providers.openai;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.providers.support.OpenAiCompatibleStreamDecoder;
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
 * The OpenAI-compatible family reports cached input under {@code prompt_tokens_details} and
 * reasoning under {@code completion_tokens_details}; both are parts of the existing counts.
 */
class CachedAndReasoningUsageTest {

    private static final String BODY = """
            {"id":"c1","object":"chat.completion","created":1,"model":"o3",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":1200,"completion_tokens":500,"total_tokens":1700,
                      "prompt_tokens_details":{"cached_tokens":1024},
                      "completion_tokens_details":{"reasoning_tokens":448}}}
            """;

    static void assertSubsets(ChatResponse.Usage u) {
        assertThat(u.getPromptTokens()).as("cached input is part of input").isGreaterThanOrEqualTo(
                u.getCachedInputTokens() + u.getCacheWriteTokens());
        assertThat(u.getCompletionTokens()).as("reasoning is part of output").isGreaterThanOrEqualTo(
                u.getReasoningTokens());
        assertThat(u.getTotalTokens()).as("the total is not inflated by the breakdown")
                .isEqualTo(u.getPromptTokens() + u.getCompletionTokens());
    }

    @Test
    @DisplayName("OpenAI: cached and reasoning tokens are read, and the totals are unchanged")
    void openAiNonStreamed() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/chat/completions")))
                .andRespond(withSuccess(BODY, MediaType.APPLICATION_JSON));

        ChatResponse.Usage u = new OpenAiProvider(builder.build()).chat(ChatRequest.builder()
                .model("o3").messages(List.of(MultimodalMessage.user("hi"))).build()).getUsage();

        assertThat(u.getPromptTokens()).isEqualTo(1200);
        assertThat(u.getCompletionTokens()).isEqualTo(500);
        assertThat(u.getTotalTokens()).isEqualTo(1700);
        assertThat(u.getCachedInputTokens()).isEqualTo(1024);
        assertThat(u.getReasoningTokens()).isEqualTo(448);
        assertThat(u.getCacheWriteTokens()).isZero();
        assertSubsets(u);
    }

    @Test
    @DisplayName("a usage block without details leaves the breakdown at zero, as before")
    void noDetailsIsZero() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.openai.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(containsString("/chat/completions")))
                .andRespond(withSuccess("""
                        {"id":"c1","model":"gpt-4o","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},
                         "finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}
                        """, MediaType.APPLICATION_JSON));

        ChatResponse.Usage u = new OpenAiProvider(builder.build()).chat(ChatRequest.builder()
                .model("gpt-4o").messages(List.of(MultimodalMessage.user("hi"))).build()).getUsage();

        assertThat(u.getTotalTokens()).isEqualTo(12);
        assertThat(u.getCachedInputTokens()).isZero();
        assertThat(u.getReasoningTokens()).isZero();
    }

    @Test
    @DisplayName("streamed: the terminal chunk's usage carries cached and reasoning tokens")
    void streamed() {
        String sse = """
                data: {"id":"c1","model":"o3","choices":[{"index":0,"delta":{"content":"Hi"}}]}

                data: {"id":"c1","model":"o3","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"c1","model":"o3","choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":500,"total_tokens":1700,"prompt_tokens_details":{"cached_tokens":1024},"completion_tokens_details":{"reasoning_tokens":448}}}

                data: [DONE]
                """;
        var it = new OpenAiCompatibleStreamDecoder(new BufferedReader(new StringReader(sse)), "o3", "OpenAI");
        List<SseChunk> chunks = new ArrayList<>();
        it.forEachRemaining(chunks::add);

        ChatResponse.Usage u = chunks.get(chunks.size() - 1).getUsage();
        assertThat(u.getCachedInputTokens()).isEqualTo(1024);
        assertThat(u.getReasoningTokens()).isEqualTo(448);
        assertSubsets(u);
    }

    @Test
    @DisplayName("streamed Groq: usage under x_groq carries the cached part too")
    void streamedGroq() {
        String sse = """
                data: {"id":"c1","model":"llama","choices":[{"index":0,"delta":{"content":"Hi"},"finish_reason":"stop"}],"x_groq":{"usage":{"prompt_tokens":50,"completion_tokens":5,"total_tokens":55,"prompt_tokens_details":{"cached_tokens":32}}}}

                data: [DONE]
                """;
        var it = new OpenAiCompatibleStreamDecoder(new BufferedReader(new StringReader(sse)), "llama", "Groq");
        List<SseChunk> chunks = new ArrayList<>();
        it.forEachRemaining(chunks::add);

        ChatResponse.Usage u = chunks.get(chunks.size() - 1).getUsage();
        assertThat(u.getCachedInputTokens()).isEqualTo(32);
        assertSubsets(u);
    }
}
