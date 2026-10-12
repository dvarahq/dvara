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
package com.dvarahq.core.model;

import com.dvarahq.core.exception.GatewayException;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A Gemini {@code generateContent} request body, kept as it came, with the text the gateway governs read out
 * of it.
 *
 * <p>On a route to Gemini the body is sent on as it came: every field, part and their order, including what
 * the gateway does not read (thought parts and their signatures, {@code thinkingConfig},
 * {@code safetySettings}). The only edits are the ones governance makes: redacted or tokenized text, and a
 * token limit or sampling value a policy sets.</p>
 *
 * <p>{@link #messages()} is the gateway's view of the same conversation, as the messages its policy, PII and
 * guardrail checks take: {@code systemInstruction} as a system message, each {@code user} turn as a user
 * message, each {@code model} turn as an assistant message, a {@code functionCall} part as a tool call and a
 * {@code functionResponse} part as a tool result. Each piece of text is tied to the place in the body it came
 * from, so {@link #governedJson(ChatRequest)} can write the governed text back there. A part the gateway does
 * not read is left where it is and counted in {@link #opaqueParts()}. The same view is what a provider that is
 * not Gemini is sent; {@link #ignoredByOthers()} names what such a provider is not given.
 * {@link #googleTools()} names the tools in the request that Google would run itself.</p>
 */
public final class GeminiGenerateContentBody {

    /** Exact numbers both ways, so a value the gateway does not touch goes on as it came. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    /** The one kind of tool entry the caller runs itself; any other kind runs at Google. */
    private static final Set<String> FUNCTION_TOOL = Set.of("functionDeclarations");

    /** Part fields that describe a part rather than make it one kind or another. */
    private static final Set<String> PART_METADATA = Set.of("thought", "thoughtSignature", "partMetadata",
            "videoMetadata", "mediaResolution");

    /** The generation settings a provider that is not Gemini is given, through the gateway's own request. */
    private static final Set<String> TRANSLATED_CONFIG = Set.of("maxOutputTokens", "temperature", "topP",
            "stopSequences", "seed", "responseMimeType", "responseSchema", "responseJsonSchema", "candidateCount");

    /** The top-level fields a provider that is not Gemini is given, through the gateway's own request. */
    private static final Set<String> TRANSLATED_FIELDS = Set.of("contents", "systemInstruction", "tools",
            "toolConfig", "generationConfig", "model");

    private final ObjectNode raw;
    private final List<MultimodalMessage> view;
    private final List<Slot> slots;
    private final Map<String, Integer> opaque;
    private final Set<String> othersIgnore;
    private final List<String> googleTools;
    private volatile ObjectNode upstreamResponse;

    private GeminiGenerateContentBody(ObjectNode raw) {
        this.raw = raw;
        Reader reader = new Reader();
        reader.read(raw);
        this.view = List.copyOf(reader.messages);
        this.slots = List.copyOf(reader.slots);
        this.opaque = Collections.unmodifiableMap(reader.opaque);
        this.othersIgnore = Collections.unmodifiableSet(reader.othersIgnore);
        this.googleTools = List.copyOf(reader.googleTools);
    }

    /**
     * Reads a request body.
     *
     * @throws GatewayException {@code INVALID_REQUEST} when it is not a JSON object or its contents are malformed
     */
    public static GeminiGenerateContentBody parse(byte[] body) {
        JsonNode node;
        try {
            node = body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (Exception e) {
            throw new GatewayException("INVALID_REQUEST", "The request body is not valid JSON");
        }
        if (!(node instanceof ObjectNode object)) {
            throw new GatewayException("INVALID_REQUEST", "The request body must be a JSON object");
        }
        return new GeminiGenerateContentBody(object);
    }

    /** The body as it came. A copy: the original is never changed. */
    public ObjectNode raw() {
        return raw.deepCopy();
    }

    /** The conversation as the gateway's messages; see the class description. */
    public List<MultimodalMessage> messages() {
        return new ArrayList<>(view);
    }

    /** Part kinds (and roles) the gateway does not read, with how many of each. */
    public Map<String, Integer> opaqueParts() {
        return opaque;
    }

    /**
     * What a provider that is not Gemini is not given, by name: {@code part:<kind>} for a part the gateway
     * does not read, {@code role:<name>} for a turn whose role it does not know, and each top-level field
     * or {@code generationConfig} setting it does not translate, such as {@code thinkingConfig} and
     * {@code safetySettings}.
     */
    public Set<String> ignoredByOthers() {
        return othersIgnore;
    }

    /**
     * The built-in tools the request names that Google runs itself, such as {@code googleSearch},
     * {@code urlContext} or {@code codeExecution}, by their field names. Empty when the request names only
     * functions the caller runs.
     */
    public List<String> googleTools() {
        return googleTools;
    }

    /**
     * The body to send to Gemini: as it came, with the governed request's text written back where each piece
     * came from, and the token limit and sampling values the gateway set. A value governance did not change is
     * not touched.
     *
     * @throws GatewayException {@code UNSUPPORTED_CAPABILITY} when governance changed the conversation's shape
     *                          (added or removed messages or parts), which the body cannot carry
     */
    public String governedJson(ChatRequest governed) {
        return write(governedBody(governed));
    }

    /** As {@link #governedJson}, keeping only the named top-level fields: a token count takes fewer. */
    public ObjectNode governedBody(ChatRequest governed, Collection<String> fields) {
        ObjectNode body = governedBody(governed);
        body.retain(fields);
        return body;
    }

    private ObjectNode governedBody(ChatRequest governed) {
        ObjectNode copy = raw.deepCopy();
        List<Value> values = values(governed.getMessages());
        if (values.size() != slots.size()) {
            throw new GatewayException("UNSUPPORTED_CAPABILITY", "A gateway control changed the shape of the "
                    + "conversation (it added or removed messages), which a request sent on to Gemini as it "
                    + "came cannot carry. Send a shorter conversation.");
        }
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            Value value = values.get(i);
            if (slot.kind != value.kind) {
                throw new GatewayException("UNSUPPORTED_CAPABILITY", "A gateway control changed the shape of the "
                        + "conversation, which a request sent on to Gemini as it came cannot carry.");
            }
            slot.write(copy, value.text);
        }
        setIfChanged(copy, "maxOutputTokens", governed.getMaxTokens());
        setIfChanged(copy, "temperature", governed.getTemperature());
        setIfChanged(copy, "topP", governed.getTopP());
        return copy;
    }

    /** Sets a {@code generationConfig} value the gateway changed, creating the object only when needed. */
    private static void setIfChanged(ObjectNode body, String field, Number value) {
        if (value == null) {
            return;
        }
        JsonNode config = body.get("generationConfig");
        JsonNode current = config == null ? null : config.get(field);
        if (current != null && current.isNumber()
                && current.decimalValue().compareTo(new java.math.BigDecimal(value.toString())) == 0) {
            return;
        }
        ObjectNode target = config instanceof ObjectNode o ? o : body.putObject("generationConfig");
        if (value instanceof Integer n) {
            target.put(field, n);
        } else {
            target.put(field, new java.math.BigDecimal(value.toString()));
        }
    }

    // -------------------------------------------------------------------------
    // The response Gemini sent
    // -------------------------------------------------------------------------

    /** Records Gemini's own response to this request, for the caller to be handed back as it came. */
    public void upstreamResponse(ObjectNode response) {
        this.upstreamResponse = response;
    }

    /** Gemini's own response to this request, or null when no Gemini provider answered it. */
    public ObjectNode upstreamResponse() {
        return upstreamResponse;
    }

    /** Reads a response body, or one event of a streamed response. */
    public static ObjectNode parseResponse(String body) {
        try {
            JsonNode node = JSON.readTree(body == null ? "" : body);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (Exception e) {
            // fall through
        }
        throw new GatewayException("PROVIDER_ERROR", "Gemini returned a response that is not a JSON object");
    }

    /**
     * The first candidate's parts as the gateway's assistant message: each answer text part its own text
     * block, each thought part a thinking block (its {@code thoughtSignature} as the signature), each
     * {@code functionCall} a tool call, in order. A part of a kind the gateway does not read is left out here
     * and counted in {@code opaque}.
     */
    public static MultimodalMessage responseMessage(JsonNode response, Map<String, Integer> opaque) {
        List<ContentBlock> content = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        JsonNode parts = response.path("candidates").path(0).path("content").path("parts");
        int minted = 0;
        for (JsonNode part : parts) {
            if (part.has("text") && part.path("thought").asBoolean(false)) {
                content.add(new ContentBlock.ThinkingBlock(part.path("text").asText(""),
                        part.hasNonNull("thoughtSignature") ? part.path("thoughtSignature").asText() : null));
            } else if (part.has("text")) {
                content.add(new ContentBlock.TextBlock(part.path("text").asText("")));
            } else if (part.has("functionCall")) {
                JsonNode call = part.get("functionCall");
                String id = call.hasNonNull("id") && !call.path("id").asText().isEmpty()
                        ? call.path("id").asText() : "gemini-call-" + minted;
                minted++;
                calls.add(ToolCall.builder().id(id).name(call.path("name").asText(null))
                        .arguments(write(call.has("args") ? call.get("args") : JSON.createObjectNode())).build());
            } else {
                opaque.merge(kind(part), 1, Integer::sum);
            }
        }
        return MultimodalMessage.builder().role("assistant").content(content)
                .toolCalls(calls.isEmpty() ? null : calls).build();
    }

    /**
     * Gemini's response with the governed message's text, thoughts and call arguments written back in place;
     * every other part as it came. Null when the governed message no longer lines up part for part with the
     * response, and the caller renders the governed message instead.
     */
    public static ObjectNode governedResponse(ObjectNode response, MultimodalMessage governed) {
        if (governed == null) {
            return null;
        }
        ObjectNode copy = response.deepCopy();
        List<String> texts = new ArrayList<>();
        List<String> thoughts = new ArrayList<>();
        if (governed.getContent() != null) {
            for (ContentBlock b : governed.getContent()) {
                if (b instanceof ContentBlock.TextBlock t) {
                    texts.add(t.text());
                } else if (b instanceof ContentBlock.ThinkingBlock t) {
                    thoughts.add(t.thinking());
                }
            }
        }
        List<ToolCall> calls = governed.getToolCalls() == null ? List.of() : governed.getToolCalls();
        Iterator<String> text = texts.iterator();
        Iterator<String> thought = thoughts.iterator();
        Iterator<ToolCall> call = calls.iterator();
        for (JsonNode node : copy.path("candidates").path(0).path("content").path("parts")) {
            if (!(node instanceof ObjectNode part)) {
                continue;
            }
            if (part.has("text") && part.path("thought").asBoolean(false)) {
                if (!thought.hasNext()) {
                    return null;
                }
                putIfChanged(part, "text", thought.next());
            } else if (part.has("text")) {
                if (!text.hasNext()) {
                    return null;
                }
                putIfChanged(part, "text", text.next());
            } else if (part.get("functionCall") instanceof ObjectNode fc) {
                if (!call.hasNext() || !writeJson(fc, "args", call.next().getArguments(), false)) {
                    return null;
                }
            }
        }
        // Text the gateway added (an empty answer block is not one) is not in Gemini's response.
        while (text.hasNext()) {
            String extra = text.next();
            if (extra != null && !extra.isEmpty()) {
                return null;
            }
        }
        return thought.hasNext() || call.hasNext() ? null : copy;
    }

    /**
     * The first candidate's parts the gateway does not read (neither text nor a function call), such as
     * {@code executableCode}, {@code codeExecutionResult} or {@code inlineData}, in order. A response the gateway
     * rebuilds keeps them.
     */
    public static ArrayNode unreadParts(JsonNode response) {
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (!part.has("text") && !part.has("functionCall")) {
                out.add(part.deepCopy());
            }
        }
        return out;
    }

    /** JSON text, numbers exact. */
    public static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            throw new GatewayException("INTERNAL_ERROR", "Could not write JSON: " + e.getMessage());
        }
    }

    /** A part's kind, for counting: its first field that is not metadata. */
    static String kind(JsonNode part) {
        Iterator<String> names = part.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!PART_METADATA.contains(name)) {
                return name;
            }
        }
        return "empty";
    }

    private static void putIfChanged(ObjectNode node, String field, String value) {
        if (!Objects.equals(node.path(field).asText(null), value)) {
            node.put(field, value);
        }
    }

    /**
     * Writes JSON text back as an object field when it changed. Text that is not a JSON object is wrapped as
     * {@code {"output": text}} when {@code wrap} is set, and otherwise refused (false).
     */
    private static boolean writeJson(ObjectNode node, String field, String json, boolean wrap) {
        JsonNode current = node.has(field) ? node.get(field) : JSON.createObjectNode();
        String now = json == null || json.isBlank() ? "{}" : json;
        if (now.equals(write(current))) {
            return true;
        }
        try {
            JsonNode parsed = JSON.readTree(now);
            if (parsed instanceof ObjectNode) {
                if (!parsed.equals(current)) {
                    node.set(field, parsed);
                }
                return true;
            }
        } catch (Exception e) {
            // not JSON: below
        }
        if (!wrap) {
            return false;
        }
        node.set(field, JSON.createObjectNode().put("output", now));
        return true;
    }

    // -------------------------------------------------------------------------
    // Reading the request
    // -------------------------------------------------------------------------

    private enum Kind { TEXT, TOOL_INPUT, TOOL_RESULT }

    /** Where one piece of governed text came from: a field of the object at {@code parent}. */
    private record Slot(Kind kind, JsonPointer parent, String field) {

        void write(ObjectNode body, String value) {
            JsonNode node = body.at(parent);
            if (!(node instanceof ObjectNode object)) {
                return;
            }
            switch (kind) {
                case TEXT -> putIfChanged(object, field, value);
                case TOOL_INPUT -> {
                    if (!writeJson(object, field, value, false)) {
                        throw new GatewayException("UNSUPPORTED_CAPABILITY", "A gateway control left a function "
                                + "call's arguments as text that is not a JSON object, which cannot be sent on "
                                + "as functionCall.args.");
                    }
                }
                case TOOL_RESULT -> writeJson(object, field, value, true);
            }
        }
    }

    private record Value(Kind kind, String text) {}

    /** The governed request's text, in the order {@link Reader} read it out: per message, content, then calls. */
    private static List<Value> values(List<MultimodalMessage> messages) {
        List<Value> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (MultimodalMessage m : messages) {
            Kind textKind = "tool".equals(m.getRole()) ? Kind.TOOL_RESULT : Kind.TEXT;
            if (m.getContent() != null) {
                for (ContentBlock b : m.getContent()) {
                    if (b instanceof ContentBlock.TextBlock t) {
                        out.add(new Value(textKind, t.text()));
                    }
                }
            }
            if (m.getToolCalls() != null) {
                for (ToolCall c : m.getToolCalls()) {
                    out.add(new Value(Kind.TOOL_INPUT, c.getArguments()));
                }
            }
        }
        return out;
    }

    /** Reads the view and its slots in one pass, in the same order {@link #values} walks a request. */
    private static final class Reader {

        final List<MultimodalMessage> messages = new ArrayList<>();
        final List<Slot> slots = new ArrayList<>();
        final Map<String, Integer> opaque = new LinkedHashMap<>();
        final Set<String> othersIgnore = new LinkedHashSet<>();
        final List<String> googleTools = new ArrayList<>();
        /** Function name → ids of its calls not yet answered, oldest first, so a result finds its call. */
        private final Map<String, Deque<String>> pending = new HashMap<>();
        private int minted;

        void read(ObjectNode body) {
            body.fieldNames().forEachRemaining(name -> {
                if (!TRANSLATED_FIELDS.contains(name)) {
                    othersIgnore.add(name);
                }
            });
            if (body.get("generationConfig") instanceof ObjectNode config) {
                config.fieldNames().forEachRemaining(name -> {
                    if (!TRANSLATED_CONFIG.contains(name)) {
                        othersIgnore.add("generationConfig." + name);
                    }
                });
            }
            for (JsonNode tool : body.path("tools")) {
                tool.fieldNames().forEachRemaining(name -> {
                    if (!FUNCTION_TOOL.contains(name)) {
                        googleTools.add(name);
                    }
                });
            }
            system(body.get("systemInstruction"));
            JsonNode list = body.get("contents");
            if (list != null && !list.isNull() && !(list instanceof ArrayNode)) {
                throw invalid("contents must be a list");
            }
            if (list instanceof ArrayNode array) {
                for (int i = 0; i < array.size(); i++) {
                    content(array.get(i), "/contents/" + i);
                }
            }
        }

        private void system(JsonNode system) {
            if (system == null || system.isNull()) {
                return;
            }
            if (system.isTextual()) {
                if (!system.asText().isEmpty()) {
                    add(MultimodalMessage.builder().role("system")
                            .content(List.of(new ContentBlock.TextBlock(system.asText()))).build(),
                            List.of(new Slot(Kind.TEXT, JsonPointer.compile(""), "systemInstruction")));
                }
                return;
            }
            JsonNode parts = system.get("parts");
            if (!(system instanceof ObjectNode) || (parts != null && !(parts instanceof ArrayNode))) {
                throw invalid("systemInstruction must be a content object with a list of parts");
            }
            List<ContentBlock> content = new ArrayList<>();
            List<Slot> textSlots = new ArrayList<>();
            for (int i = 0; parts != null && i < parts.size(); i++) {
                JsonNode part = parts.get(i);
                if (part.has("text") && !part.path("thought").asBoolean(false)) {
                    content.add(new ContentBlock.TextBlock(part.path("text").asText("")));
                    textSlots.add(new Slot(Kind.TEXT, JsonPointer.compile("/systemInstruction/parts/" + i), "text"));
                } else {
                    opaque(kind(part));
                }
            }
            if (!content.isEmpty()) {
                add(MultimodalMessage.builder().role("system").content(content).build(), textSlots);
            }
        }

        private void content(JsonNode c, String at) {
            if (!(c instanceof ObjectNode)) {
                throw invalid("each entry of contents must be an object");
            }
            // An absent role is a user turn; "function" is the older name of the turn that carries results.
            String role = c.path("role").asText("user");
            if (role.isEmpty() || "function".equals(role)) {
                role = "user";
            }
            if (!"user".equals(role) && !"model".equals(role)) {
                opaque.merge("role:" + role, 1, Integer::sum);
                othersIgnore.add("role:" + role);
                return;
            }
            JsonNode parts = c.get("parts");
            if (parts != null && !(parts instanceof ArrayNode)) {
                throw invalid("parts must be a list");
            }
            if ("model".equals(role)) {
                model(parts, at);
            } else {
                user(parts, at);
            }
        }

        /** A model turn: one assistant message, its text first, then its calls, as {@link #values} walks it. */
        private void model(JsonNode parts, String at) {
            List<ContentBlock> content = new ArrayList<>();
            List<Slot> textSlots = new ArrayList<>();
            List<ToolCall> calls = new ArrayList<>();
            List<Slot> callSlots = new ArrayList<>();
            for (int i = 0; parts != null && i < parts.size(); i++) {
                JsonNode part = parts.get(i);
                String partAt = at + "/parts/" + i;
                if (part.has("text") && part.path("thought").asBoolean(false)) {
                    opaque("thought");
                } else if (part.has("text")) {
                    content.add(new ContentBlock.TextBlock(part.path("text").asText("")));
                    textSlots.add(new Slot(Kind.TEXT, JsonPointer.compile(partAt), "text"));
                } else if (part.get("functionCall") instanceof ObjectNode call) {
                    String name = call.path("name").asText("");
                    String id = call.hasNonNull("id") && !call.path("id").asText().isEmpty()
                            ? call.path("id").asText() : "gemini-call-" + (minted++);
                    pending.computeIfAbsent(name, n -> new ArrayDeque<>()).add(id);
                    calls.add(ToolCall.builder().id(id).name(name)
                            .arguments(write(call.has("args") ? call.get("args") : JSON.createObjectNode())).build());
                    callSlots.add(new Slot(Kind.TOOL_INPUT, JsonPointer.compile(partAt + "/functionCall"), "args"));
                } else {
                    opaque(kind(part));
                }
            }
            if (content.isEmpty() && calls.isEmpty()) {
                return;
            }
            List<Slot> all = new ArrayList<>(textSlots);
            all.addAll(callSlots);
            add(MultimodalMessage.builder().role("assistant").content(content)
                    .toolCalls(calls.isEmpty() ? null : calls).build(), all);
        }

        /** A user turn: each function result its own tool message, then the turn's text and images. */
        private void user(JsonNode parts, String at) {
            List<ContentBlock> content = new ArrayList<>();
            List<Slot> textSlots = new ArrayList<>();
            for (int i = 0; parts != null && i < parts.size(); i++) {
                JsonNode part = parts.get(i);
                String partAt = at + "/parts/" + i;
                if (part.has("text") && part.path("thought").asBoolean(false)) {
                    opaque("thought");
                } else if (part.has("text")) {
                    content.add(new ContentBlock.TextBlock(part.path("text").asText("")));
                    textSlots.add(new Slot(Kind.TEXT, JsonPointer.compile(partAt), "text"));
                } else if (part.get("functionResponse") instanceof ObjectNode result) {
                    String name = result.path("name").asText("");
                    String id = result.hasNonNull("id") && !result.path("id").asText().isEmpty()
                            ? result.path("id").asText() : null;
                    Deque<String> open = pending.get(name);
                    if (id != null && open != null) {
                        open.remove(id);
                    } else if (id == null) {
                        id = open == null || open.isEmpty() ? name : open.poll();
                    }
                    add(MultimodalMessage.builder().role("tool").toolCallId(id).name(name)
                            .content(List.of(new ContentBlock.TextBlock(write(result.has("response")
                                    ? result.get("response") : JSON.createObjectNode())))).build(),
                            List.of(new Slot(Kind.TOOL_RESULT, JsonPointer.compile(partAt + "/functionResponse"),
                                    "response")));
                } else if (part.get("inlineData") instanceof ObjectNode data
                        && data.path("mimeType").asText("").startsWith("image/")) {
                    content.add(new ContentBlock.ImageBlock(data.path("mimeType").asText(),
                            data.path("data").asText("")));
                } else {
                    opaque(kind(part));
                }
            }
            if (!content.isEmpty()) {
                add(MultimodalMessage.builder().role("user").content(content).build(), textSlots);
            }
        }

        private void add(MultimodalMessage message, List<Slot> messageSlots) {
            messages.add(message);
            slots.addAll(messageSlots);
        }

        private void opaque(String kind) {
            opaque.merge(kind, 1, Integer::sum);
            othersIgnore.add("part:" + kind);
        }

        private static GatewayException invalid(String message) {
            return new GatewayException("INVALID_REQUEST", message);
        }
    }
}
