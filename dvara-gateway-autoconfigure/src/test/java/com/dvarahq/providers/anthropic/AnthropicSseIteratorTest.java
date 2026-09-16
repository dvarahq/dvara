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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
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
 * Parses Anthropic's event-per-line SSE, where {@code event:} lines carry the type and
 * {@code data:} lines the payload. A {@code data:} line with no preceding {@code event:} is ignored
 * rather than parsed, and {@code jsonSchemaMode} switches which field the text comes from.
 */
class AnthropicSseIteratorTest {

    private static List<SseChunk> drain(String sse, boolean jsonSchemaMode) {
        var it = new AnthropicProvider.AnthropicSseIterator(
                new BufferedReader(new StringReader(sse)), "claude-3-5-sonnet", jsonSchemaMode);
        List<SseChunk> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next());
        }
        return out;
    }

    /** The first chunk only: these fixtures are not whole streams and would end without a stop reason. */
    private static SseChunk first(String sse) {
        return new AnthropicProvider.AnthropicSseIterator(
                new BufferedReader(new StringReader(sse)), "claude-3-5-sonnet", false).next();
    }

    private static final String STREAM = """
            event: message_start
            data: {"message":{"id":"msg_123"}}

            event: content_block_delta
            data: {"delta":{"type":"text_delta","text":"Hel"}}

            event: content_block_delta
            data: {"delta":{"type":"text_delta","text":"lo"}}

            event: message_delta
            data: {"delta":{"stop_reason":"end_turn"}}

            event: message_stop
            data: {}
            """;

    /** What Anthropic sends for text followed by one tool call: the tool_use block is block 1. */
    private static final String TOOL_STREAM = """
            event: message_start
            data: {"message":{"id":"msg_9"}}

            event: content_block_start
            data: {"index":0,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"index":0,"delta":{"type":"text_delta","text":"I'll check."}}

            event: content_block_stop
            data: {"index":0}

            event: content_block_start
            data: {"index":1,"content_block":{"type":"tool_use","id":"toolu_01","name":"get_weather","input":{}}}

            event: content_block_delta
            data: {"index":1,"delta":{"type":"input_json_delta","partial_json":""}}

            event: content_block_delta
            data: {"index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"loc"}}

            event: content_block_delta
            data: {"index":1,"delta":{"type":"input_json_delta","partial_json":"ation\\": \\"Paris\\"}"}}

            event: content_block_stop
            data: {"index":1}

            event: message_delta
            data: {"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}

            event: message_stop
            data: {}
            """;

    @Test
    void toolUseBlock_becomesStreamedToolCallFragments_numberedFromZero() {
        List<SseChunk> chunks = drain(TOOL_STREAM, false);

        assertThat(chunks).hasSize(5);
        assertThat(chunks.get(0).getDelta()).isEqualTo("I'll check.");
        assertThat(chunks.get(1).getToolCalls()).as("block 1 is tool call 0")
                .containsExactly(new ToolCallDelta(0, "toolu_01", "get_weather", null));
        assertThat(chunks.get(2).getToolCalls()).containsExactly(new ToolCallDelta(0, null, null, "{\"loc"));
        assertThat(chunks.get(3).getToolCalls()).containsExactly(new ToolCallDelta(0, null, null, "ation\": \"Paris\"}"));
        assertThat(chunks.get(4).isDone()).isTrue();
        assertThat(chunks.get(4).getFinishReason()).as("tool_use is tool_calls, as the non-streaming path says").isEqualTo("tool_calls");
        assertThat(chunks.get(4).getToolCalls()).isNull();
        assertThat(chunks.subList(1, 4)).allMatch(c -> c.getDelta() == null && !c.isDone());
    }

    @Test
    void inJsonSchemaMode_theToolUseBlockIsStillTheStructuredOutputText() {
        List<SseChunk> chunks = drain(TOOL_STREAM, true);

        assertThat(chunks).allMatch(c -> c.getToolCalls() == null);
        StringBuilder text = new StringBuilder();
        for (SseChunk c : chunks) if (c.getDelta() != null) text.append(c.getDelta());
        assertThat(text.toString()).isEqualTo("I'll check.{\"location\": \"Paris\"}");
        assertThat(chunks.getLast().getFinishReason()).isEqualTo("stop");
    }

    @Test
    void parsesDeltasAndCarriesTheMessageIdFromMessageStart() {
        List<SseChunk> chunks = drain(STREAM, false);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).getDelta()).isEqualTo("Hel");
        assertThat(chunks.get(1).getDelta()).isEqualTo("lo");
        // message_start carries no delta of its own; its job is to supply the id every later chunk
        // is stamped with.
        assertThat(chunks).allSatisfy(c -> assertThat(c.getId()).isEqualTo("msg_123"));
        assertThat(chunks).allSatisfy(c -> assertThat(c.getModel()).isEqualTo("claude-3-5-sonnet"));
    }

    /** {@code end_turn} is Anthropic's spelling of a normal stop; the gateway speaks OpenAI's. */
    @Test
    void mapsEndTurnToStopAndMarksTheStreamDone() {
        SseChunk last = drain(STREAM, false).getLast();

        assertThat(last.getFinishReason()).isEqualTo("stop");
        assertThat(last.isDone()).isTrue();
        assertThat(last.getDelta()).isNull();
    }

    /** An unmapped stop reason passes through as itself rather than being flattened to "stop". */
    @Test
    void unrecognisedStopReasonIsPassedThroughUnchanged() {
        SseChunk last = drain("""
                event: message_delta
                data: {"delta":{"stop_reason":"max_tokens"}}
                """, false).getLast();

        assertThat(last.getFinishReason()).isEqualTo("max_tokens");
    }

    /**
     * In json_schema mode the model answers through a tool call, so the text arrives as
     * {@code partial_json} and {@code tool_use} is a normal stop — not a truncation. Getting this
     * wrong yields an empty response and a finish reason the caller reads as failure.
     */
    @Test
    void jsonSchemaMode_readsPartialJsonAndTreatsToolUseAsANormalStop() {
        String sse = """
                event: content_block_delta
                data: {"delta":{"type":"input_json_delta","partial_json":"{\\"a\\":1}"}}

                event: message_delta
                data: {"delta":{"stop_reason":"tool_use"}}
                """;

        List<SseChunk> chunks = drain(sse, true);

        assertThat(chunks.get(0).getDelta()).isEqualTo("{\"a\":1}");
        assertThat(chunks.getLast().getFinishReason()).isEqualTo("stop");
    }

    /**
     * The same stream without json_schema mode is a tool call, not text, and this one is
     * malformed: the fragment continues a block nothing opened and names no index. Guessing would
     * invent a nameless call, so it is a provider error.
     */
    @Test
    void withoutJsonSchemaMode_anArgumentFragmentWithNoOpenerIsAProviderError() {
        String sse = """
                event: content_block_delta
                data: {"delta":{"type":"input_json_delta","partial_json":"{\\"a\\":1}"}}

                event: message_delta
                data: {"delta":{"stop_reason":"tool_use"}}
                """;

        var it = new AnthropicProvider.AnthropicSseIterator(
                new BufferedReader(new StringReader(sse)), "claude-3-5-sonnet", false);
        assertThatThrownBy(it::hasNext).isInstanceOf(GatewayException.class)
                .hasMessageContaining("never opened");
        assertThat(it.hasNext()).as("the stream is over after the error").isFalse();
    }

    /** A {@code data:} line with no preceding {@code event:} is ignored; the event type selects the parse. */
    @Test
    void dataWithoutAPrecedingEventLineIsIgnored() {
        assertThat(first("""
                data: {"delta":{"text":"orphaned"}}

                event: content_block_delta
                data: {"delta":{"text":"kept"}}
                """).getDelta()).isEqualTo("kept");
    }

    /** Unknown event types are skipped rather than aborting the stream. */
    @Test
    void unknownEventTypesAreSkipped() {
        assertThat(first("""
                event: ping
                data: {}

                event: content_block_start
                data: {"content_block":{"type":"text"}}

                event: content_block_delta
                data: {"delta":{"text":"hi"}}
                """).getDelta()).isEqualTo("hi");
    }

    /** An empty body is no answer at all, not an empty one. */
    @Test
    void anEmptyStreamIsAnError() {
        var it = new AnthropicProvider.AnthropicSseIterator(
                new BufferedReader(new StringReader("")), "claude", false);

        assertThatThrownBy(it::hasNext).isInstanceOf(GatewayException.class)
                .hasMessageContaining("before a stop reason");
        assertThat(it.hasNext()).as("the stream is over after the error").isFalse();
        assertThatThrownBy(it::next).isInstanceOf(NoSuchElementException.class);
    }

    /** A read failure mid-stream is a PROVIDER_ERROR, not an IOException leaking to the caller. */
    @Test
    void readFailureBecomesProviderError() {
        Reader exploding = new Reader() {
            @Override public int read(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("connection reset");
            }
            @Override public void close() {}
        };
        var it = new AnthropicProvider.AnthropicSseIterator(
                new BufferedReader(exploding), "claude", false);

        assertThatThrownBy(it::hasNext)
                .isInstanceOf(GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "PROVIDER_ERROR");
    }

    // ---- incomplete streams ----

    @Test
    void anErrorEventPartWayIsAnError() {
        String sse = """
                event: message_start
                data: {"message":{"id":"msg_1"}}

                event: content_block_delta
                data: {"delta":{"type":"text_delta","text":"Hello"}}

                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """;
        assertThatThrownBy(() -> drain(sse, false))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("Overloaded");
    }

    @Test
    void aStreamCutBeforeAStopReasonIsAnError() {
        String sse = """
                event: message_start
                data: {"message":{"id":"msg_1"}}

                event: content_block_delta
                data: {"delta":{"type":"text_delta","text":"Hel"}}

                """;
        assertThatThrownBy(() -> drain(sse, false))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("before a stop reason");
    }

    @Test
    void aStopReasonWithoutMessageStopIsStillComplete() {
        String sse = """
                event: message_start
                data: {"message":{"id":"msg_1"}}

                event: content_block_delta
                data: {"delta":{"type":"text_delta","text":"Hi"}}

                event: message_delta
                data: {"delta":{"stop_reason":"max_tokens"}}

                """;
        List<SseChunk> chunks = drain(sse, false);
        assertThat(chunks.get(chunks.size() - 1).isDone()).isTrue();
    }
}
