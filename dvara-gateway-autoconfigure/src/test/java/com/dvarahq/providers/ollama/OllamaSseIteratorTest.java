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
import com.dvarahq.core.model.SseChunk;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ollama speaks the OpenAI-compatible SSE shape but parses it through its own iterator rather
 * than the shared decoder, so the shared decoder's tests do not exercise it.
 *
 * <p>The branch that matters is the model name: a local Ollama build may omit it from the chunk,
 * and the iterator falls back to the requested model so that metering and the access log, which
 * key on it, still see one.</p>
 */
class OllamaSseIteratorTest {

    private static List<SseChunk> drain(String sse) {
        var it = new OllamaProvider.OllamaSseIterator(
                new BufferedReader(new StringReader(sse)), "llama3.2");
        List<SseChunk> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }

    /** The first chunk only: these fixtures are not whole streams and would end without a finish. */
    private static SseChunk first(String sse) {
        return new OllamaProvider.OllamaSseIterator(new BufferedReader(new StringReader(sse)), "llama3.2").next();
    }

    @Test
    void parsesDeltasUntilDone() {
        List<SseChunk> chunks = drain("""
                data: {"id":"c1","model":"llama3.2","choices":[{"delta":{"content":"Hel"}}]}

                data: {"id":"c1","model":"llama3.2","choices":[{"delta":{"content":"lo"}}]}

                data: {"id":"c1","model":"llama3.2","choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]
                """);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getDelta()).isEqualTo("Hel");
        assertThat(chunks.get(1).getDelta()).isEqualTo("lo");
        assertThat(chunks.getLast().getFinishReason()).isEqualTo("stop");
        assertThat(chunks.getLast().isDone()).isTrue();
    }

    /** A chunk with no model falls back to the requested one — metering keys on it. */
    @Test
    void chunkWithoutAModelFallsBackToTheRequestedModel() {
        SseChunk chunk = first("""
                data: {"id":"c1","choices":[{"delta":{"content":"hi"}}]}
                """);

        assertThat(chunk.getModel()).isEqualTo("llama3.2");
    }

    @Test
    void chunkWithNoChoicesYieldsANullDeltaRatherThanFailing() {
        SseChunk chunk = first("""
                data: {"id":"c1","model":"llama3.2","choices":[]}
                """);

        assertThat(chunk.getDelta()).isNull();
        assertThat(chunk.getFinishReason()).isNull();
    }

    /** Comment lines and blank lines are SSE keep-alives, not payload. */
    @Test
    void blankAndCommentLinesAreSkipped() {
        assertThat(first("""
                : keep-alive

                data: {"id":"c1","choices":[{"delta":{"content":"hi"}}]}
                """).getDelta()).isEqualTo("hi");
    }

    /** Everything after [DONE] is unreachable — the reader is closed on it. */
    @Test
    void stopsAtDoneAndIgnoresAnythingAfterIt() {
        assertThat(drain("""
                data: {"id":"c1","choices":[{"delta":{"content":"hi"},"finish_reason":"stop"}]}

                data: [DONE]

                data: {"id":"c1","choices":[{"delta":{"content":"never"}}]}
                """)).hasSize(1);
    }

    /** An empty body is no answer at all, not an empty one. */
    @Test
    void anEmptyStreamIsAnError() {
        var it = new OllamaProvider.OllamaSseIterator(
                new BufferedReader(new StringReader("")), "llama3.2");

        assertThatThrownBy(it::hasNext).isInstanceOf(GatewayException.class)
                .hasMessageContaining("before a finish reason");
        assertThat(it.hasNext()).as("the stream is over after the error").isFalse();
        assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void readFailureBecomesProviderError() {
        Reader exploding = new Reader() {
            @Override public int read(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("connection reset");
            }
            @Override public void close() {}
        };
        var it = new OllamaProvider.OllamaSseIterator(new BufferedReader(exploding), "llama3.2");

        assertThatThrownBy(it::hasNext)
                .isInstanceOf(GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "PROVIDER_ERROR");
    }

    // ---- incomplete streams ----

    @Test
    void aStreamCutBeforeAFinishReasonIsAnError() {
        assertThatThrownBy(() -> drain("""
                data: {"id":"c1","model":"llama3.2","choices":[{"delta":{"content":"Hel"}}]}

                """))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("before a finish reason");
    }

    @Test
    void doneWithoutAFinishReasonIsAnError() {
        assertThatThrownBy(() -> drain("""
                data: {"id":"c1","model":"llama3.2","choices":[{"delta":{"content":"Hel"}}]}

                data: [DONE]

                """))
                .isInstanceOf(GatewayException.class);
    }

    @Test
    void aLengthFinishEndsTheAnswer() {
        List<SseChunk> chunks = drain("""
                data: {"id":"c1","model":"llama3.2","choices":[{"delta":{"content":"Hel"}}]}

                data: {"id":"c1","model":"llama3.2","choices":[{"delta":{},"finish_reason":"length"}]}

                data: [DONE]

                """);
        assertThat(chunks.get(chunks.size() - 1).isDone()).isTrue();
        assertThat(chunks.get(chunks.size() - 1).getFinishReason()).isEqualTo("length");
    }
}
