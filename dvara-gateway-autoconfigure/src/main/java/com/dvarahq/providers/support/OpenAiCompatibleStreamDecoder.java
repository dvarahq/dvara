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
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ReleasableUpstream;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.util.JsonMapper;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Decodes an OpenAI-compatible chat-completions stream into {@link SseChunk}s.
 *
 * <p>One decoder for every upstream that speaks this wire: the OpenAI-compatible base provider,
 * Mistral and Groq.</p>
 *
 * <p>What it does with each {@code data:} line:</p>
 * <ul>
 *   <li><b>Text</b> — {@code choices[0].delta.content} becomes the chunk's delta.</li>
 *   <li><b>Tool calls</b> — every entry of {@code choices[0].delta.tool_calls[]} becomes a
 *       {@link ToolCallDelta}. The provider's {@code index} is normalised to a consecutive, zero-based
 *       position in order of first appearance; an upstream that omits it is keyed by the call id, and
 *       a fragment with neither is a call of its own. A call must keep one identity style across its
 *       fragments. Argument text is relayed whether the upstream sent it as a string or, as Mistral
 *       may, as a JSON object. A tool of any {@code type} but {@code function} is a
 *       {@code PROVIDER_ERROR}.</li>
 *   <li><b>Finish and usage</b> — the usage block arrives one chunk after the finish reason, so the
 *       chunk carrying {@code finish_reason} is held: a usage chunk (top-level, or Groq's
 *       {@code x_groq.usage}) merges into it and the pair is emitted as one terminal chunk;
 *       {@code [DONE]} or end of stream emits it without usage. Content after the finish reason is a
 *       {@code PROVIDER_ERROR}.</li>
 *   <li><b>Incomplete streams</b> — a stream that ends, or sends {@code [DONE]}, before any finish
 *       reason is a {@code PROVIDER_ERROR}, as is an {@code error} payload part-way.</li>
 * </ul>
 */
public final class OpenAiCompatibleStreamDecoder implements Iterator<SseChunk>, AutoCloseable, ReleasableUpstream {

    private final BufferedReader reader;
    private final AutoCloseable transport;   // the body stream; closing it bypasses the reader's lock
    private final String model;
    private final String upstreamLabel;
    private SseChunk next;
    private boolean done;
    /** The chunk carrying the finish reason, held until the usage chunk or the end decides its usage. */
    private SseChunk held;
    /** Provider tool-call key (its index, or its id when it sends none) → normalised zero-based index. */
    private final Map<String, Integer> toolCallIndexes = new LinkedHashMap<>();
    /** Fragments with neither index nor id: each is its own call, under a key no upstream index can share. */
    private int anonymousCalls;

    public OpenAiCompatibleStreamDecoder(BufferedReader reader, String model, String upstreamLabel) {
        this(() -> { }, reader, model, upstreamLabel);
    }

    public OpenAiCompatibleStreamDecoder(AutoCloseable transport, BufferedReader reader, String model,
                                         String upstreamLabel) {
        this.transport = transport;
        this.reader = reader;
        this.model = model;
        this.upstreamLabel = upstreamLabel;
    }

    @Override
    public boolean hasNext() {
        if (next != null) return true;
        if (done) return false;
        next = advance();
        return next != null;
    }

    @Override
    public SseChunk next() {
        if (!hasNext()) throw new NoSuchElementException();
        SseChunk result = next;
        next = null;
        return result;
    }

    private SseChunk advance() {
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith(":")) continue;
                if (!line.startsWith("data: ")) continue;
                String data = line.substring(6).trim();
                if ("[DONE]".equals(data)) {
                    done = true;
                    closeReader();
                    if (held == null) {
                        throw incomplete();
                    }
                    return releaseHeld(null);
                }
                StreamChunk chunk = JsonMapper.instance().readValue(data, StreamChunk.class);
                if (chunk.getError() != null && !chunk.getError().isNull()) {
                    done = true;
                    closeReader();
                    JsonNode message = chunk.getError().path("message");
                    throw new GatewayException("PROVIDER_ERROR", upstreamLabel + " stream sent an error part-way: "
                            + (message.isTextual() ? message.textValue() : chunk.getError().toString()));
                }
                SseChunk mapped;
                try {
                    mapped = map(chunk);
                } catch (GatewayException e) {
                    done = true;
                    closeReader();
                    throw e;
                }
                // The usage block arrives one chunk after the one carrying finish_reason, and every
                // consumer stops at the first done chunk. So the finish-reason chunk is held, and
                // whatever comes next decides it: a usage chunk merges into it and the pair is emitted
                // as the one terminal chunk; [DONE] or the end of the stream emits it without usage.
                if (held != null) {
                    if (carriesContent(mapped)) {
                        done = true;
                        closeReader();
                        throw new GatewayException("PROVIDER_ERROR",
                                "Upstream sent content after finish_reason; the stream is not a valid completion");
                    }
                    done = true;
                    closeReader();
                    return releaseHeld(mapped.getUsage());
                }
                if (mapped.getFinishReason() != null) {
                    if (mapped.getUsage() != null) {
                        // finish reason and usage on one chunk: nothing to wait for
                        done = true;
                        closeReader();
                        return terminal(mapped, mapped.getUsage());
                    }
                    held = mapped;
                    continue;
                }
                return mapped;
            }
            done = true;
            closeReader();
            if (held == null) {
                throw incomplete();
            }
            return releaseHeld(null);
        } catch (IOException e) {
            done = true;
            closeReader();
            throw new GatewayException("PROVIDER_ERROR",
                    "Error reading " + upstreamLabel + " stream: " + e.getMessage(), e);
        }
    }

    /** The stream ended before any finish reason, so the answer is incomplete. */
    private GatewayException incomplete() {
        return new GatewayException("PROVIDER_ERROR",
                upstreamLabel + " stream ended before a finish reason; the answer is incomplete");
    }

    private static boolean carriesContent(SseChunk chunk) {
        return (chunk.getDelta() != null && !chunk.getDelta().isEmpty())
                || (chunk.getToolCalls() != null && !chunk.getToolCalls().isEmpty());
    }

    private SseChunk map(StreamChunk chunk) {
        String delta = null;
        String finishReason = null;
        List<ToolCallDelta> toolCalls = null;
        if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
            StreamChunk.Choice choice = chunk.getChoices().get(0);
            if (choice.getDelta() != null) {
                delta = choice.getDelta().getContent();
                toolCalls = toolCalls(choice.getDelta().getToolCalls());
            }
            finishReason = choice.getFinishReason();
        }
        // The usage-bearing chunk arrives after the one carrying finish_reason and has an empty choices
        // array, so it is a normal chunk with a null delta. Groq reports usage under x_groq.
        StreamChunk.Usage reported = chunk.getUsage() != null ? chunk.getUsage()
                : chunk.getXGroq() != null ? chunk.getXGroq().getUsage() : null;
        ChatResponse.Usage usage = reported == null ? null : ChatResponse.Usage.builder()
                .promptTokens(reported.getPromptTokens())
                .completionTokens(reported.getCompletionTokens())
                .totalTokens(reported.getTotalTokens())
                .build();
        return SseChunk.builder()
                .id(chunk.getId())
                .model(chunk.getModel() != null ? chunk.getModel() : model)
                .delta(delta)
                .toolCalls(toolCalls)
                .finishReason(finishReason)
                .done(false)   // the terminal chunk is decided in advance()
                .usage(usage)
                .build();
    }

    /** The fragments on one delta, indices normalised; null when the delta carries none. */
    private List<ToolCallDelta> toolCalls(List<StreamChunk.ToolCall> raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        List<ToolCallDelta> out = new ArrayList<>(raw.size());
        for (StreamChunk.ToolCall call : raw) {
            if (call.getType() != null && !"function".equals(call.getType())) {
                throw new GatewayException("PROVIDER_ERROR",
                        upstreamLabel + " streamed a tool call of type '" + call.getType()
                                + "', which this gateway cannot relay");
            }
            String key = call.getIndex() != null ? "i:" + call.getIndex()
                    : call.getId() != null ? "id:" + call.getId()
                    : "anonymous:" + anonymousCalls++; // neither: a new call each time is the only reading
            int index = toolCallIndexes.computeIfAbsent(key, k -> toolCallIndexes.size());
            String name = call.getFunction() != null ? call.getFunction().getName() : null;
            out.add(new ToolCallDelta(index, call.getId(), name,
                    call.getFunction() != null ? argumentsText(call.getFunction().getArguments()) : null));
        }
        return out;
    }

    /**
     * The argument text on a fragment: the string as sent, or — Mistral's schema allows it — a JSON
     * object or array serialised compactly. Empty text is null: an opener with {@code "arguments": ""}
     * carries no argument characters.
     */
    static String argumentsText(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return null;
        }
        String text = arguments.isTextual() ? arguments.textValue() : arguments.toString();
        return text.isEmpty() ? null : text;
    }

    private SseChunk releaseHeld(ChatResponse.Usage usage) {
        if (held == null) return null;
        SseChunk t = terminal(held, usage);
        held = null;
        return t;
    }

    /** The held chunk as the terminal one: everything it carried, plus the usage that decided it. */
    private static SseChunk terminal(SseChunk chunk, ChatResponse.Usage usage) {
        return chunk.toBuilder().usage(usage).done(true).build();
    }

    /** Close the body stream, not the reader: a read parked in readLine() holds the reader's lock. */
    @Override
    public void releaseTransport() {
        try { transport.close(); } catch (Exception ignored) { }
    }

    @Override
    public void close() { closeReader(); }

    private void closeReader() {
        try { reader.close(); } catch (IOException ignored) {}
    }

    /** One {@code data:} payload of the stream, as every OpenAI-compatible upstream shapes it. */
    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class StreamChunk {
        private String id;
        private String model;
        private List<Choice> choices;
        /** Present only on the final chunk, and only when asked for with {@code stream_options}. */
        private Usage usage;
        /** Groq reports streamed usage here instead. */
        @JsonProperty("x_groq") private XGroq xGroq;
        /** An upstream that fails part-way sends {@code {"error": {...}}} as a data payload. */
        private JsonNode error;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class XGroq {
            private Usage usage;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Usage {
            @JsonProperty("prompt_tokens")     private int promptTokens;
            @JsonProperty("completion_tokens") private int completionTokens;
            @JsonProperty("total_tokens")      private int totalTokens;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Choice {
            private int index;
            private Delta delta;
            @JsonProperty("finish_reason") private String finishReason;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Delta {
            private String role;
            private String content;
            @JsonProperty("tool_calls") private List<ToolCall> toolCalls;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class ToolCall {
            /** The upstream's own position; OpenAI sends it, some compatible upstreams send only the id. */
            private Integer index;
            private String id;
            private String type;
            private Function function;

            @Data @JsonIgnoreProperties(ignoreUnknown = true)
            static class Function {
                private String name;
                /** A string on OpenAI's wire; Mistral's schema also allows a JSON object. */
                private JsonNode arguments;
            }
        }
    }
}
