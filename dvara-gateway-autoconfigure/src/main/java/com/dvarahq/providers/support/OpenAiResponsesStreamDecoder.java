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
import com.dvarahq.core.model.OpenAiResponsesBody;
import com.dvarahq.core.model.ReleasableUpstream;
import com.dvarahq.core.model.ResponsesEvent;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.util.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Reads an OpenAI Responses API stream into chunks, each carrying the event it was read from
 * ({@link ResponsesEvent}), so the caller can be sent OpenAI's events as they came.
 *
 * <p>What governance reads is put on the chunk: {@code response.output_text.delta} as text; a function call's
 * opening item and {@code response.function_call_arguments.delta} as tool-call fragments; a custom tool call's
 * input as the arguments {@code {"input": "..."}}, opened with the item, escaped as it streams and closed when
 * the item is done. {@code response.completed} is the terminal chunk and carries the usage, cached input and
 * reasoning tokens included; {@code response.incomplete} ends the stream with the finish reason {@code length}.
 * {@code response.failed} and {@code error} are a {@code PROVIDER_ERROR}, as is a stream that ends before its
 * terminal event. Every other event is a chunk with no content.</p>
 */
public final class OpenAiResponsesStreamDecoder implements Iterator<SseChunk>, AutoCloseable, ReleasableUpstream {

    private final BufferedReader reader;
    private final AutoCloseable transport;
    private final String model;
    private final String upstreamLabel;
    private SseChunk next;
    private boolean done;
    /** Output item id → zero-based tool-call index, in the order calls open. */
    private final Map<String, Integer> callIndexes = new HashMap<>();
    private final Set<String> customCalls = new HashSet<>();

    public OpenAiResponsesStreamDecoder(AutoCloseable transport, BufferedReader reader, String model, String upstreamLabel) {
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
                if (!line.startsWith("data:")) continue;   // event: lines repeat the type the data carries
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JsonNode event = JsonMapper.instance().readTree(data);
                SseChunk chunk = map(event, data);
                if (chunk.isDone()) {
                    done = true;
                    closeReader();
                }
                return chunk;
            }
            done = true;
            closeReader();
            throw new GatewayException("PROVIDER_ERROR",
                    upstreamLabel + " stream ended before the response completed; the answer is incomplete");
        } catch (IOException e) {
            done = true;
            closeReader();
            throw new GatewayException("PROVIDER_ERROR", "Error reading " + upstreamLabel + " stream: " + e.getMessage(), e);
        } catch (GatewayException e) {
            done = true;
            closeReader();
            throw e;
        }
    }

    private SseChunk map(JsonNode event, String data) {
        String type = event.path("type").asText("");
        SseChunk.SseChunkBuilder chunk = SseChunk.builder().model(model).responsesEvent(new ResponsesEvent(type, data));
        switch (type) {
            case "response.output_text.delta" -> chunk.delta(event.path("delta").asText(""));
            case "response.output_item.added" -> {
                JsonNode item = event.path("item");
                String itemType = item.path("type").asText("");
                if ("function_call".equals(itemType) || "custom_tool_call".equals(itemType)
                        || "tool_search_call".equals(itemType)) {
                    String itemId = item.path("id").asText(null);
                    int index = callIndexes.computeIfAbsent(itemId, k -> callIndexes.size());
                    boolean custom = "custom_tool_call".equals(itemType);
                    if (custom) {
                        customCalls.add(itemId);
                    }
                    String name = "tool_search_call".equals(itemType) ? "tool_search" : name(item);
                    chunk.toolCalls(List.of(ToolCallDelta.open(index, item.path("call_id").asText(null),
                            name, custom ? "{\"input\":\"" : null)));
                }
            }
            case "response.function_call_arguments.delta" -> {
                Integer index = callIndexes.get(event.path("item_id").asText(null));
                String delta = event.path("delta").asText("");
                if (index != null && !delta.isEmpty()) {
                    chunk.toolCalls(List.of(ToolCallDelta.arguments(index, delta)));
                }
            }
            case "response.custom_tool_call_input.delta" -> {
                Integer index = callIndexes.get(event.path("item_id").asText(null));
                String delta = event.path("delta").asText("");
                if (index != null && !delta.isEmpty()) {
                    chunk.toolCalls(List.of(ToolCallDelta.arguments(index, escape(delta))));
                }
            }
            case "response.output_item.done" -> {
                String itemId = event.path("item").path("id").asText(null);
                if ("tool_search_call".equals(event.path("item").path("type").asText(""))
                        && callIndexes.containsKey(itemId)) {
                    // A tool search's arguments come whole, on the finished item.
                    chunk.toolCalls(List.of(ToolCallDelta.arguments(callIndexes.get(itemId),
                            OpenAiResponsesBody.write(event.path("item").path("arguments")))));
                } else if (customCalls.remove(itemId)) {
                    chunk.toolCalls(List.of(ToolCallDelta.arguments(callIndexes.get(itemId), "\"}")));
                }
            }
            case "response.completed" -> chunk.done(true)
                    .id(event.path("response").path("id").asText(null))
                    .finishReason(callIndexes.isEmpty() ? "stop" : "tool_calls")
                    .usage(OpenAiResponsesBody.usage(event.path("response").get("usage")));
            case "response.incomplete" -> chunk.done(true)
                    .id(event.path("response").path("id").asText(null))
                    .finishReason("length")
                    .usage(OpenAiResponsesBody.usage(event.path("response").get("usage")));
            case "response.failed", "error" -> throw failure(type, event, data);
            default -> { }
        }
        return chunk.build();
    }

    /**
     * The provider's failure as the caller's error: the status its code stands for, and the provider's own code
     * and message. OpenAI puts the error at {@code response.error} on {@code response.failed}, and on an
     * {@code error} event either at the top level or under {@code error}.
     */
    private com.dvarahq.core.exception.ProviderStreamFailure failure(String type, JsonNode event, String data) {
        JsonNode error = "response.failed".equals(type) ? event.path("response").path("error")
                : event.path("error").isObject() ? event.path("error") : event;
        String code = text(error, "code");
        if (code == null) {
            code = text(error, "type");
        }
        String message = text(error, "message");
        String reason = code == null ? message : message == null ? code : code + ": " + message;
        int status = statusFor(code);
        String said = reason == null ? upstreamLabel + " ended the stream with an error" + GatewayException.describeHttpStatus(status)
                : upstreamLabel + " ended the stream with an error" + GatewayException.describeHttpStatus(status) + ": "
                        + ProviderErrors.masked(reason);
        return new com.dvarahq.core.exception.ProviderStreamFailure(status, said, code, new ResponsesEvent(type, data));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    /**
     * The HTTP status a provider's error code stands for, so a client can tell waiting out a limit (429) from a
     * request it must change (400) and a fault at the provider (502).
     */
    static int statusFor(String code) {
        if (code == null) {
            return 502;
        }
        String c = code.toLowerCase(java.util.Locale.ROOT);
        if (c.contains("rate_limit") || c.contains("quota") || c.contains("credit") || c.contains("billing")) {
            return 429;
        }
        if (c.startsWith("invalid") || c.contains("context_length") || c.contains("unsupported")
                || c.contains("not_found") || c.contains("too_large")) {
            return 400;
        }
        return 502;
    }

    private static String name(JsonNode item) {
        String name = item.path("name").asText(null);
        String namespace = item.path("namespace").asText(null);
        return namespace == null || namespace.isEmpty() ? name
                : com.dvarahq.core.model.ToolDefinition.namespaced(namespace, name);
    }

    /** Text as it reads inside a JSON string, without the quotes. */
    private static String escape(String text) {
        try {
            String quoted = JsonMapper.instance().writeValueAsString(text);
            return quoted.substring(1, quoted.length() - 1);
        } catch (Exception e) {
            throw new IllegalStateException("could not escape a custom tool input", e);
        }
    }

    @Override
    public void releaseTransport() {
        try { transport.close(); } catch (Exception ignored) { }
    }

    @Override
    public void close() { closeReader(); }

    private void closeReader() {
        try { reader.close(); } catch (IOException ignored) {}
    }
}
