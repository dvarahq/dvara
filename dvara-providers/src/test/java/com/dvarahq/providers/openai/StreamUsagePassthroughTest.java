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

import com.dvarahq.core.model.SseChunk;
import com.dvarahq.providers.support.OpenAiCompatibleStreamDecoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stream's exact usage block must survive the trip to the ledger. OpenAI and every
 * OpenAI-compatible upstream return one on a stream when the request sets
 * {@code stream_options: {"include_usage": true}}, and the iterator has to carry it.
 *
 * <p>The shape that makes this easy to get wrong: the usage-bearing chunk arrives <b>after</b> the
 * one carrying {@code finish_reason}, and it has an <b>empty choices array</b>. Code that stops at
 * {@code done}, or that discards chunks with no delta, drops exactly that chunk.</p>
 */
class StreamUsagePassthroughTest {

    private static List<SseChunk> drain(String sse) {
        var it = new OpenAiCompatibleStreamDecoder(
                new BufferedReader(new StringReader(sse)), "gpt-4o", "OpenAI");
        List<SseChunk> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }

    /** A real stream shape: two deltas, a finish_reason chunk, then the usage chunk, then [DONE]. */
    private static final String STREAM = """
            data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hel"}}]}

            data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"lo"}}]}

            data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"id":"c1","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}

            data: [DONE]
            """;

    @Test
    @DisplayName("the exact usage block reaches an SseChunk")
    void usageIsCarried() {
        SseChunk withUsage = drain(STREAM).stream()
                .filter(c -> c.getUsage() != null)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no chunk carried usage"));

        assertThat(withUsage.getUsage().getPromptTokens()).isEqualTo(11);
        assertThat(withUsage.getUsage().getCompletionTokens()).isEqualTo(3);
        assertThat(withUsage.getUsage().getTotalTokens()).isEqualTo(14);
    }

    /**
     * The upstream sends the usage one chunk after finish_reason, and every consumer stops at the
     * first done chunk. So the iterator holds the finish-reason chunk and emits one terminal chunk
     * carrying both; nothing follows it.
     */
    @Test
    @DisplayName("the terminal chunk carries the finish reason AND the usage, and nothing follows it")
    void theTerminalChunkCarriesFinishReasonAndUsage() {
        List<SseChunk> chunks = drain(STREAM);
        SseChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.isDone()).isTrue();
        assertThat(last.getFinishReason()).isEqualTo("stop");
        assertThat(last.getUsage()).isNotNull();
        assertThat(last.getUsage().getTotalTokens()).isEqualTo(14);
        assertThat(chunks.stream().filter(SseChunk::isDone)).as("exactly one terminal chunk").hasSize(1);
        assertThat(chunks).as("two deltas, then the terminal").hasSize(3);
    }

    @Test
    @DisplayName("[DONE] straight after finish_reason: terminal without usage, not a lost chunk")
    void finishReasonThenDone_isTerminalWithoutUsage() {
        List<SseChunk> chunks = drain("""
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hi"}}]}
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                data: [DONE]
                """);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).isDone()).isTrue();
        assertThat(chunks.get(1).getFinishReason()).isEqualTo("stop");
        assertThat(chunks.get(1).getUsage()).isNull();
    }

    @Test
    @DisplayName("content after finish_reason is a protocol error, not a silently dropped chunk")
    void contentAfterFinishReason_isAProviderError() {
        var it = new OpenAiCompatibleStreamDecoder(new BufferedReader(new StringReader("""
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"late"}}]}
                data: [DONE]
                """)), "gpt-4o", "OpenAI");
        org.assertj.core.api.Assertions.assertThatThrownBy(it::hasNext)
                .isInstanceOf(com.dvarahq.core.exception.GatewayException.class)
                .hasMessageContaining("after finish_reason");
    }

    @Test
    @DisplayName("an empty chunk without usage after finish_reason ends the stream: terminal without usage")
    void emptyChunkWithoutUsageAfterFinish_isTerminalWithoutUsage() {
        List<SseChunk> chunks = drain("""
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
                data: {"id":"c1","model":"gpt-4o","choices":[]}
                data: [DONE]
                """);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).isDone()).isTrue();
        assertThat(chunks.get(0).getUsage()).isNull();
    }

    @Test
    @DisplayName("every finish reason is terminal, not only stop")
    void lengthIsTerminalToo() {
        List<SseChunk> chunks = drain("""
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hi"}}]}
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}
                data: {"id":"c1","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":5,"completion_tokens":9,"total_tokens":14}}
                data: [DONE]
                """);
        SseChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.isDone()).isTrue();
        assertThat(last.getFinishReason()).isEqualTo("length");
        assertThat(last.getUsage().getCompletionTokens()).isEqualTo(9);
    }

    @Test
    @DisplayName("a chunk already fetched is served even though fetching it ended the stream")
    void hasNextTwiceBeforeNext_doesNotLoseTheTerminalChunk() {
        var it = new OpenAiCompatibleStreamDecoder(
                new BufferedReader(new StringReader(STREAM)), "gpt-4o", "OpenAI");
        it.next(); it.next();                       // the two deltas
        assertThat(it.hasNext()).isTrue();          // fetches the terminal, which ends the stream
        assertThat(it.hasNext()).as("asked again before next(): still there").isTrue();
        assertThat(it.next().isDone()).isTrue();
        assertThat(it.hasNext()).isFalse();
    }

    @Test
    @DisplayName("a stream with no usage block yields null, not zeros")
    void absentUsageIsNullNotZero() {
        String noUsage = """
                data: {"id":"c1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":"stop"}]}

                data: [DONE]
                """;
        // Zeros would look like a real zero-token call and bill nothing; null makes the metering
        // path fall back to the estimate and say so.
        assertThat(drain(noUsage)).allSatisfy(c -> assertThat(c.getUsage()).isNull());
    }

    @Test
    @DisplayName("the deltas still arrive unchanged — the usage chunk is additive, not a rewrite")
    void deltasAreUnaffected() {
        String text = drain(STREAM).stream()
                .map(SseChunk::getDelta)
                .filter(d -> d != null)
                .reduce("", String::concat);
        assertThat(text).isEqualTo("Hello");
    }
}