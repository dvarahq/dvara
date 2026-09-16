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
package com.dvarahq.providers.support;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one decoder every OpenAI-compatible upstream streams through, against the event shapes
 * those upstreams actually send. Behavioural fixtures rather than a source scan: what has to stay true
 * is what a stream decodes to, not which class decodes it.
 */
class OpenAiCompatibleStreamDecoderTest {

    private static List<SseChunk> drain(String sse) {
        var it = new OpenAiCompatibleStreamDecoder(new BufferedReader(new StringReader(sse)), "m", "Upstream");
        List<SseChunk> out = new ArrayList<>();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    private static List<ToolCallDelta> fragments(List<SseChunk> chunks) {
        return chunks.stream().filter(c -> c.getToolCalls() != null).flatMap(c -> c.getToolCalls().stream()).toList();
    }

    private static String arguments(List<SseChunk> chunks, int index) {
        StringBuilder sb = new StringBuilder();
        fragments(chunks).stream().filter(f -> f.index() == index && f.argumentsFragment() != null)
                .forEach(f -> sb.append(f.argumentsFragment()));
        return sb.toString();
    }

    // ------------------------------------------------------------------ OpenAI

    /** What OpenAI sends for one function call: the opener carries id and name, the rest carry arguments. */
    static final String OPENAI_ONE_CALL = """
            data: {"id":"c1","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"get_weather","arguments":""}}]},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"lo"}}]},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"cation\\":\\"Paris\\"}"}}]},"finish_reason":null}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

            data: {"id":"c1","object":"chat.completion.chunk","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":40,"completion_tokens":12,"total_tokens":52}}

            data: [DONE]
            """;

    @Test
    @DisplayName("OpenAI: the opener carries id and name, later fragments carry arguments, the terminal carries tool_calls and usage")
    void openAiOneCall() throws Exception {
        List<SseChunk> chunks = drain(OPENAI_ONE_CALL);

        assertThat(chunks).hasSize(4);
        assertThat(fragments(chunks)).containsExactly(
                new ToolCallDelta(0, "call_abc", "get_weather", null),
                new ToolCallDelta(0, null, null, "{\"lo"),
                new ToolCallDelta(0, null, null, "cation\":\"Paris\"}"));
        assertThat(new ObjectMapper().readTree(arguments(chunks, 0)).get("location").textValue()).isEqualTo("Paris");
        SseChunk terminal = chunks.getLast();
        assertThat(terminal.isDone()).isTrue();
        assertThat(terminal.getFinishReason()).isEqualTo("tool_calls");
        assertThat(terminal.getUsage().getTotalTokens()).isEqualTo(52);
        assertThat(chunks.subList(0, 3)).allMatch(c -> !c.isDone() && c.getDelta() == null);
    }

    @Test
    @DisplayName("OpenAI parallel calls: two calls interleaved keep their own index, whatever the upstream numbered them")
    void openAiParallelCallsAreNormalised() {
        String sse = """
                data: {"id":"c2","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":3,"id":"call_a","type":"function","function":{"name":"first","arguments":""}},{"index":7,"id":"call_b","type":"function","function":{"name":"second","arguments":""}}]},"finish_reason":null}]}

                data: {"id":"c2","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":7,"function":{"arguments":"{\\"b\\":2}"}}]},"finish_reason":null}]}

                data: {"id":"c2","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":3,"function":{"arguments":"{\\"a\\":1}"}}]},"finish_reason":null}]}

                data: {"id":"c2","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;
        List<SseChunk> chunks = drain(sse);

        assertThat(fragments(chunks)).containsExactly(
                new ToolCallDelta(0, "call_a", "first", null),
                new ToolCallDelta(1, "call_b", "second", null),
                new ToolCallDelta(1, null, null, "{\"b\":2}"),
                new ToolCallDelta(0, null, null, "{\"a\":1}"));
        assertThat(arguments(chunks, 0)).isEqualTo("{\"a\":1}");
        assertThat(arguments(chunks, 1)).isEqualTo("{\"b\":2}");
        assertThat(chunks.getLast().getFinishReason()).isEqualTo("tool_calls");
        assertThat(chunks.getLast().getUsage()).isNull();
    }

    @Test
    @DisplayName("text and a tool-call fragment on one chunk both come through")
    void textAndCallOnOneChunk() {
        String sse = """
                data: {"id":"c3","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Let me check.","tool_calls":[{"index":0,"id":"call_x","type":"function","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":null}]}

                data: {"id":"c3","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;
        List<SseChunk> chunks = drain(sse);
        assertThat(chunks.getFirst().getDelta()).isEqualTo("Let me check.");
        assertThat(chunks.getFirst().getToolCalls()).containsExactly(new ToolCallDelta(0, "call_x", "lookup", "{}"));
    }

    // ------------------------------------------------------------------ Mistral

    @Test
    @DisplayName("Mistral: a call in one chunk without an index, finish reason and usage on the same chunk — one terminal")
    void mistralOneShotCallWithoutIndex() {
        String sse = """
                data: {"id":"m1","object":"chat.completion.chunk","created":1,"model":"mistral-large-latest","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

                data: {"id":"m1","object":"chat.completion.chunk","created":1,"model":"mistral-large-latest","choices":[{"index":0,"delta":{"content":"","tool_calls":[{"id":"9Ae3bDc2F","function":{"name":"get_weather","arguments":"{\\"city\\":\\"Paris\\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":70,"completion_tokens":20,"total_tokens":90}}

                data: [DONE]
                """;
        List<SseChunk> chunks = drain(sse);

        assertThat(chunks).hasSize(2);
        SseChunk terminal = chunks.getLast();
        assertThat(terminal.isDone()).isTrue();
        assertThat(terminal.getFinishReason()).isEqualTo("tool_calls");
        assertThat(terminal.getUsage().getTotalTokens()).isEqualTo(90);
        assertThat(terminal.getToolCalls()).containsExactly(
                new ToolCallDelta(0, "9Ae3bDc2F", "get_weather", "{\"city\":\"Paris\"}"));
    }

    @Test
    @DisplayName("Mistral: two calls without indices are told apart by id")
    void callsWithoutIndicesAreKeyedById() {
        String sse = """
                data: {"id":"m2","model":"mistral-large-latest","choices":[{"index":0,"delta":{"tool_calls":[{"id":"idA","function":{"name":"a","arguments":"{\\"x\\":1}"}},{"id":"idB","function":{"name":"b","arguments":"{\\"y\\":2}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;
        assertThat(fragments(drain(sse))).containsExactly(
                new ToolCallDelta(0, "idA", "a", "{\"x\":1}"),
                new ToolCallDelta(1, "idB", "b", "{\"y\":2}"));
    }

    @Test
    @DisplayName("Mistral: arguments sent as a JSON object rather than a string are relayed as compact JSON text")
    void mistralObjectValuedArguments() throws Exception {
        String sse = """
                data: {"id":"m3","model":"mistral-large-latest","choices":[{"index":0,"delta":{"tool_calls":[{"id":"idC","function":{"name":"get_weather","arguments":{"city":"Paris","days":3}}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;
        List<ToolCallDelta> fragments = fragments(drain(sse));
        assertThat(fragments).hasSize(1);
        assertThat(fragments.getFirst().name()).isEqualTo("get_weather");
        var tree = new ObjectMapper().readTree(fragments.getFirst().argumentsFragment());
        assertThat(tree.get("city").textValue()).isEqualTo("Paris");
        assertThat(tree.get("days").intValue()).isEqualTo(3);
    }

    // ------------------------------------------------------------------ Groq

    @Test
    @DisplayName("Groq: indexed fragments, and usage under x_groq on the finish chunk")
    void groqCallWithXGroqUsage() {
        String sse = """
                data: {"id":"g1","object":"chat.completion.chunk","model":"llama-3.3-70b","choices":[{"index":0,"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call_g","type":"function","function":{"name":"get_weather","arguments":"{\\"city\\": \\"Paris\\"}"}}]},"finish_reason":null}]}

                data: {"id":"g1","object":"chat.completion.chunk","model":"llama-3.3-70b","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"x_groq":{"id":"req","usage":{"prompt_tokens":30,"completion_tokens":9,"total_tokens":39}}}

                data: [DONE]
                """;
        List<SseChunk> chunks = drain(sse);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst().getToolCalls()).containsExactly(
                new ToolCallDelta(0, "call_g", "get_weather", "{\"city\": \"Paris\"}"));
        assertThat(chunks.getLast().isDone()).isTrue();
        assertThat(chunks.getLast().getUsage().getTotalTokens()).isEqualTo(39);
    }

    // ------------------------------------------------------------------ shared rules

    @Test
    @DisplayName("a fragment with neither index nor id is a call of its own, and cannot collide with a later indexed call")
    void anonymousCallDoesNotCollideWithAnIndexedOne() {
        String sse = """
                data: {"id":"c6","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"function":{"name":"anon","arguments":"{}"}}]},"finish_reason":null}]}

                data: {"id":"c6","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_z","function":{"name":"indexed","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]
                """;
        assertThat(fragments(drain(sse))).containsExactly(
                new ToolCallDelta(0, null, "anon", "{}"),
                new ToolCallDelta(1, "call_z", "indexed", "{}"));
    }

    @Test
    @DisplayName("a tool of a type other than function is a provider error, not a function call in disguise")
    void nonFunctionToolTypeIsAProviderError() {
        String sse = """
                data: {"id":"c7","model":"m","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_q","type":"custom","custom":{"name":"x","input":"y"}}]},"finish_reason":null}]}

                data: [DONE]
                """;
        boolean[] closed = {false};
        var reader = new BufferedReader(new StringReader(sse)) {
            @Override public void close() throws java.io.IOException { closed[0] = true; super.close(); }
        };
        var it = new OpenAiCompatibleStreamDecoder(reader, "m", "Upstream");
        assertThatThrownBy(it::hasNext).isInstanceOf(GatewayException.class).hasMessageContaining("type 'custom'");
        assertThat(it.hasNext()).as("the stream is over after the error").isFalse();
        assertThat(closed[0]).as("and the reader was closed on the way out").isTrue();
    }

    @Test
    @DisplayName("a held finish chunk that itself carries fragments keeps them on the terminal")
    void heldFinishChunkKeepsItsFragments() {
        String sse = """
                data: {"id":"c4","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_h","type":"function","function":{"name":"f","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                data: {"id":"c4","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}

                data: [DONE]
                """;
        List<SseChunk> chunks = drain(sse);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.getFirst().isDone()).isTrue();
        assertThat(chunks.getFirst().getToolCalls()).containsExactly(new ToolCallDelta(0, "call_h", "f", "{}"));
        assertThat(chunks.getFirst().getUsage().getTotalTokens()).isEqualTo(2);
    }

    @Test
    @DisplayName("a tool-call fragment after finish_reason is a provider error, like text after it")
    void fragmentAfterFinishIsAProviderError() {
        String sse = """
                data: {"id":"c5","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                data: {"id":"c5","model":"gpt-4o","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]},"finish_reason":null}]}

                data: [DONE]
                """;
        var it = new OpenAiCompatibleStreamDecoder(new BufferedReader(new StringReader(sse)), "m", "Upstream");
        assertThatThrownBy(it::hasNext).isInstanceOf(GatewayException.class)
                .hasMessageContaining("after finish_reason");
    }

    @Test
    @DisplayName("a plain text stream decodes to deltas, then one terminal with usage")
    void plainTextStreamIsUnchanged() {
        String sse = """
                data: {"id":"t1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"Hel"}}]}

                data: {"id":"t1","model":"gpt-4o","choices":[{"index":0,"delta":{"content":"lo"}}]}

                data: {"id":"t1","model":"gpt-4o","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: {"id":"t1","model":"gpt-4o","choices":[],"usage":{"prompt_tokens":11,"completion_tokens":3,"total_tokens":14}}

                data: [DONE]
                """;
        List<SseChunk> chunks = drain(sse);
        assertThat(chunks).extracting(SseChunk::getDelta).containsExactly("Hel", "lo", null);
        assertThat(chunks).allMatch(c -> c.getToolCalls() == null);
        assertThat(chunks.getLast().isDone()).isTrue();
        assertThat(chunks.getLast().getUsage().getTotalTokens()).isEqualTo(14);
    }

    // ------------------------------------------------------------------ incomplete streams

    private static void drainAll(OpenAiCompatibleStreamDecoder it) {
        while (it.hasNext()) {
            it.next();
        }
    }

    private static OpenAiCompatibleStreamDecoder decoder(String sse) {
        return new OpenAiCompatibleStreamDecoder(new BufferedReader(new StringReader(sse)), "m", "Upstream");
    }

    /** A connection that drops mid-answer must not read as a short, finished answer. */
    @Test
    void aStreamCutBeforeAFinishReasonIsAnError() {
        var it = decoder("""
                data: {"id":"c","choices":[{"index":0,"delta":{"content":"Hello wor"}}]}

                """);
        assertThat(it.next().getDelta()).isEqualTo("Hello wor");
        assertThatThrownBy(() -> drainAll(it))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("before a finish reason");
    }

    @Test
    void doneWithoutAFinishReasonIsAnError() {
        var it = decoder("""
                data: {"id":"c","choices":[{"index":0,"delta":{"content":"Hello"}}]}

                data: [DONE]

                """);
        assertThatThrownBy(() -> drainAll(it)).isInstanceOf(GatewayException.class);
    }

    @Test
    void anErrorPayloadPartWayIsAnError() {
        var it = decoder("""
                data: {"id":"c","choices":[{"index":0,"delta":{"content":"Hello"}}]}

                data: {"error":{"message":"server overloaded","type":"server_error"}}

                """);
        assertThatThrownBy(() -> drainAll(it))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("server overloaded");
    }

    @Test
    void aFinishReasonFollowedByTheEndOfInputIsStillComplete() {
        List<SseChunk> chunks = drain("""
                data: {"id":"c","choices":[{"index":0,"delta":{"content":"Hi"}}]}

                data: {"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"length"}]}

                """);
        assertThat(chunks.get(chunks.size() - 1).isDone()).isTrue();
        assertThat(chunks.get(chunks.size() - 1).getFinishReason()).isEqualTo("length");
    }
}
