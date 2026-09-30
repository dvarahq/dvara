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

    // -------------------------------------------------------------------------
    // Tool calls on the stream
    // -------------------------------------------------------------------------

    /** The shape a live Ollama sends: both calls whole in one delta, then a tool_calls finish. */
    @Test
    void toolCallsInOneDelta_becomeToolCallDeltas() {
        List<SseChunk> chunks = drain("""
                data: {"id":"c1","model":"qwen3:4b-instruct","choices":[{"index":0,"delta":{"role":"assistant","content":"","tool_calls":[{"id":"call_a","index":0,"type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Paris\\"}"}},{"id":"call_b","index":1,"type":"function","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Rome\\"}"}}]},"finish_reason":null}]}

                data: {"id":"c1","model":"qwen3:4b-instruct","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """);

        assertThat(chunks).hasSize(2);
        var calls = chunks.get(0).getToolCalls();
        assertThat(calls).containsExactly(
                new com.dvarahq.core.model.ToolCallDelta(0, "call_a", "get_weather", "{\"city\":\"Paris\"}"),
                new com.dvarahq.core.model.ToolCallDelta(1, "call_b", "get_weather", "{\"city\":\"Rome\"}"));
        assertThat(chunks.get(1).getFinishReason()).isEqualTo("tool_calls");
        assertThat(chunks.get(1).isDone()).isTrue();
    }

    /** A call split across deltas: the opener carries id and name, the rest carry argument text by index. */
    @Test
    void aCallSplitAcrossDeltas_keepsOneIndex() {
        List<SseChunk> chunks = drain("""
                data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"id":"call_a","index":0,"type":"function","function":{"name":"get_rate","arguments":""}}]}}]}

                data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"lane\\":"}}]}}]}

                data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"CHI-DAL\\"}"}}]}}]}

                data: {"id":"c1","choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """);

        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).getToolCalls()).containsExactly(
                new com.dvarahq.core.model.ToolCallDelta(0, "call_a", "get_rate", null));
        assertThat(chunks.get(1).getToolCalls()).containsExactly(
                new com.dvarahq.core.model.ToolCallDelta(0, null, null, "{\"lane\":"));
        assertThat(chunks.get(2).getToolCalls()).containsExactly(
                new com.dvarahq.core.model.ToolCallDelta(0, null, null, "\"CHI-DAL\"}"));
    }

    /** Upstream positions become consecutive and zero-based; a call with only an id is keyed by it. */
    @Test
    void toolCallIndexesAreNormalised_andAnIdOnlyCallIsKeyedById() {
        List<SseChunk> chunks = drain("""
                data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"id":"x","index":3,"function":{"name":"a","arguments":"{}"}}]}}]}

                data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"id":"y","function":{"name":"b","arguments":"{"}}]}}]}

                data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"id":"y","function":{"arguments":"}"}}]}}]}

                data: {"id":"c1","choices":[{"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """);

        assertThat(chunks.get(0).getToolCalls().get(0).index()).isZero();
        assertThat(chunks.get(1).getToolCalls().get(0).index()).isEqualTo(1);
        assertThat(chunks.get(2).getToolCalls().get(0).index()).isEqualTo(1);
    }

    /** A text delta carries no tool calls. */
    @Test
    void aTextDeltaCarriesNoToolCalls() {
        assertThat(first("""
                data: {"id":"c1","choices":[{"delta":{"content":"hi"}}]}
                """).getToolCalls()).isNull();
    }

    /** A tool of any type but function cannot be relayed, so the stream ends with an error. */
    @Test
    void aNonFunctionToolCallIsAnError() {
        assertThatThrownBy(() -> drain("""
                data: {"id":"c1","choices":[{"delta":{"tool_calls":[{"id":"x","index":0,"type":"code_interpreter","function":{"name":"a"}}]}}]}

                data: {"id":"c1","choices":[{"delta":{},"finish_reason":"tool_calls"}]}
                """))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("code_interpreter")
                .extracting("code").isEqualTo("PROVIDER_ERROR");
    }
}
