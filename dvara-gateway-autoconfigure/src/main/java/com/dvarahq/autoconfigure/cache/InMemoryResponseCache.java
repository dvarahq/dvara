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
package com.dvarahq.autoconfigure.cache;

import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A response cache held in this process, for a build with no shared cache.
 *
 * <p>It serves a stored response only when the request is byte-for-byte the same request. There is
 * no similarity matching: a cache serves one response as the answer to another request, and
 * topical similarity is not equivalence (a negated prompt scores as a near match for the sentence
 * it negates). An exact cache covers a client retrying, a duplicate request, and the same prompt
 * in a loop.
 *
 * <p>Two limits, stated at startup: it is per process, so a fleet of N replicas gets N caches and
 * a hit rate that falls as it grows; and it is lost on restart.
 *
 * <p>The key carries the workspace. Two workspaces sending an identical prompt to an identical
 * model get separate entries, so one workspace's stored response is never served to another. The
 * cost is hit rate, which on a per-process cache is small.
 */
public final class InMemoryResponseCache implements ResponseCache {

    private static final Logger log = LoggerFactory.getLogger(InMemoryResponseCache.class);

    private final Cache<String, ChatResponse> entries;

    public InMemoryResponseCache(int maxEntries, int ttlSeconds) {
        this.entries = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .build();
        log.warn("Response caching is ON, held IN THIS PROCESS ONLY ({} entries, {}s TTL) and lost on "
                        + "restart. Every replica keeps its own, so a fleet of N pods gets N caches and a "
                        + "hit rate that falls as it grows. It serves EXACT matches only — an identical "
                        + "model, messages, sampling settings, response format and tools, for the same "
                        + "workspace — and no "
                        + "property in this build makes it serve a similar request instead. A cache shared "
                        + "across a fleet is what a different distribution of this gateway provides.",
                maxEntries, ttlSeconds);
    }

    @Override
    public Optional<ChatResponse> get(ChatRequest request) {
        if (workspaceOf(request).isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.getIfPresent(key(request)));
    }

    /**
     * A request with no workspace is not cached. The workspace is the namespace, and a request that
     * has none would land every such caller in one shared namespace, where one caller's answer is
     * served to another. Every request the gateway serves has a workspace by the time it gets here;
     * an application driving the cache directly without one gets no cache rather than a shared one.
     */
    @Override
    public void put(ChatRequest request, ChatResponse response) {
        if (response != null && !workspaceOf(request).isEmpty()) {
            entries.put(key(request), response);
        }
    }

    @Override
    public void clear() {
        entries.invalidateAll();
    }

    /**
     * Removes one entry, so a response the output pipeline has just refused stops being served.
     *
     * <p>Implemented rather than left as the interface's no-op default: without it the execution
     * service re-scans and re-refuses the same content on every later request, and the cache goes
     * on holding a response the current policy will never allow.
     */
    @Override
    public void evict(ChatRequest request) {
        entries.invalidate(key(request));
    }

    /**
     * Everything that makes two requests the same call: the workspace, the model, every message with its tool
     * calls and tool results, the sampling settings, the response format, and the tools with the tool choice.
     * A field left out lets a request be served an answer that was produced for a different one: a request
     * for JSON gets stored prose, and a request with tools gets an answer made without them.
     *
     * <p>Hashed rather than kept whole because the plain form is the prompt, and a heap dump or a
     * diagnostic that prints a key should not print somebody's request. The input is
     * length-prefixed at each part so that two different message lists cannot flatten to one string
     * — without it, a message ending in the separator would collide with the next one starting.
     */
    private static String key(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        append(sb, workspaceOf(request));
        append(sb, request.getModel());
        if (request.getMessages() != null) {
            for (MultimodalMessage message : request.getMessages()) {
                append(sb, message.getRole());
                append(sb, textOf(message.getContent()));
                append(sb, message.getName());
                append(sb, message.getToolCallId());
                append(sb, toolCallsOf(message.getToolCalls()));
            }
        }
        append(sb, String.valueOf(request.getTemperature()));
        append(sb, String.valueOf(request.getMaxTokens()));
        append(sb, String.valueOf(request.getTopP()));
        append(sb, String.valueOf(request.getFrequencyPenalty()));
        append(sb, String.valueOf(request.getPresencePenalty()));
        append(sb, canonical(request.getStop()));
        append(sb, String.valueOf(request.getSeed()));
        append(sb, responseFormatOf(request.getResponseFormat()));
        append(sb, toolsOf(request.getTools()));
        append(sb, canonical(request.getToolChoice()));
        return sha256(sb.toString());
    }

    /** Blank for no format and for {@code text}, the default; otherwise the type, and for a schema its name,
     *  strict flag and schema. */
    private static String responseFormatOf(ResponseFormat format) {
        if (format == null || format instanceof ResponseFormat.Text) {
            return "";
        }
        if (format instanceof ResponseFormat.JsonObject) {
            return "json_object";
        }
        StringBuilder sb = new StringBuilder();
        append(sb, "json_schema");
        if (format instanceof ResponseFormat.JsonSchema schema) {
            append(sb, schema.name());
            append(sb, Boolean.toString(schema.strict()));
            append(sb, canonical(schema.schema()));
        }
        return sb.toString();
    }

    private static String toolsOf(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolDefinition tool : tools) {
            append(sb, tool.getName());
            append(sb, tool.getDescription());
            append(sb, canonical(tool.getParameters()));
        }
        return sb.toString();
    }

    private static String toolCallsOf(List<ToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ToolCall call : calls) {
            append(sb, call.getId());
            append(sb, call.getName());
            append(sb, call.getArguments());
        }
        return sb.toString();
    }

    /** A value as a string with every map's keys sorted, so the order of keys in a request does not change the key. */
    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            StringBuilder sb = new StringBuilder("{");
            sorted.forEach((k, v) -> {
                append(sb, k);
                append(sb, canonical(v));
            });
            return sb.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (Object item : list) {
                append(sb, canonical(item));
            }
            return sb.append(']').toString();
        }
        return value == null ? "null" : value.getClass().getSimpleName() + ":" + value;
    }

    private static String workspaceOf(ChatRequest request) {
        if (request.getMetadata() == null) {
            return "";
        }
        Object workspaceId = request.getMetadata().get("workspace_id");
        return workspaceId == null ? "" : workspaceId.toString();
    }

    private static String textOf(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.TextBlock text) {
                append(sb, text.text());
            } else if (block instanceof ContentBlock.ImageBlock image) {
                // A hash of the data, not only its type: two pictures with the same question are different
                // questions, and the same picture sent again with the same question is the same one.
                append(sb, "image:" + image.mediaType() + ":" + sha256(image.data() == null ? "" : image.data()));
            }
        }
        return sb.toString();
    }

    /** Length-prefixed, so no value can be confused with a boundary between two of them. */
    private static void append(StringBuilder sb, String value) {
        String safe = value == null ? "" : value;
        sb.append(safe.length()).append(':').append(safe).append('|');
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}
