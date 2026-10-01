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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An Anthropic Messages API request body, kept as it came, with the text the gateway governs read out of it.
 *
 * <p>On a route to Anthropic the body is sent on as it came: every field, message role, content block and
 * their order. The only edits are the ones governance makes — redacted or tokenized text, the model a route
 * maps to, a token limit a policy sets. Nothing is rebuilt from the gateway's own model, so a field or block
 * a client adds tomorrow reaches Anthropic without a gateway change.</p>
 *
 * <p>{@link #messages()} is the gateway's view of the same conversation: every piece of text it can read, as
 * the messages its policy, PII and guardrail checks already take. Each piece is tied to the place in the body
 * it came from, so {@link #governedJson(ChatRequest)} can write the governed text back to that place. A block
 * whose type the gateway does not read is opaque: it is left where it is, never dropped, and named in
 * {@link #opaqueBlocks()} so a new type is visible. The same view is what a provider that is not Anthropic is
 * sent, and {@link #ignoredByOthers()} names what such a provider is not given.</p>
 */
public final class AnthropicMessagesBody {

    /** Exact numbers both ways, so a value the gateway does not touch goes on as it came. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private final ObjectNode raw;
    private final List<MultimodalMessage> view;
    private final List<Slot> slots;
    private final Map<String, Integer> opaque;
    private final Set<String> othersIgnore;
    private volatile ObjectNode upstreamResponse;

    private AnthropicMessagesBody(ObjectNode raw) {
        this.raw = raw;
        Reader reader = new Reader();
        reader.read(raw);
        this.view = List.copyOf(reader.messages);
        this.slots = List.copyOf(reader.slots);
        this.opaque = Collections.unmodifiableMap(reader.opaque);
        this.othersIgnore = Collections.unmodifiableSet(reader.othersIgnore);
    }

    /**
     * Reads a request body.
     *
     * @throws GatewayException {@code INVALID_REQUEST} when it is not a JSON object
     */
    public static AnthropicMessagesBody parse(byte[] body) {
        JsonNode node;
        try {
            node = body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (Exception e) {
            throw new GatewayException("INVALID_REQUEST", "The request body is not valid JSON");
        }
        if (!(node instanceof ObjectNode object)) {
            throw new GatewayException("INVALID_REQUEST", "The request body must be a JSON object");
        }
        return new AnthropicMessagesBody(object);
    }

    /** The body as it came. A copy: the original is never changed. */
    public ObjectNode raw() {
        return raw.deepCopy();
    }

    /** Binds the body to a typed request, for the fields the gateway models. */
    public <T> T bind(Class<T> type) {
        try {
            return JSON.treeToValue(raw, type);
        } catch (Exception e) {
            throw new GatewayException("INVALID_REQUEST", "The request body could not be read: "
                    + rootMessage(e));
        }
    }

    /**
     * The conversation as the gateway's messages: the top-level {@code system} first, then each message in
     * order. A {@code system} message inside {@code messages} stays a system message at its place. A
     * {@code tool_result} becomes a {@code tool} message, a {@code tool_use} a tool call.
     */
    public List<MultimodalMessage> messages() {
        return new ArrayList<>(view);
    }

    /** Block types (and message roles) the gateway does not read, with how many of each. */
    public Map<String, Integer> opaqueBlocks() {
        return opaque;
    }

    /**
     * What in the body the gateway's view leaves out, so a provider that is not Anthropic does not get it:
     * {@code block:<type>} for a block type the gateway does not read, {@code role:<name>} for a message
     * role it does not know, {@code cache_control}, and {@code tool_choice.disable_parallel_tool_use}.
     * Top-level fields are named by {@link AnthropicPassthrough}, which holds them.
     */
    public Set<String> ignoredByOthers() {
        return othersIgnore;
    }

    /**
     * The body to send to Anthropic: as it came, with the governed request's text written back where each
     * piece came from, and the model and sampling values the gateway set. A value governance did not change
     * is not touched.
     *
     * @throws GatewayException {@code UNSUPPORTED_CAPABILITY} when governance changed the conversation's shape
     *                          (added or removed messages or blocks), which the body cannot carry
     */
    public String governedJson(ChatRequest governed) {
        return write(governedBody(governed));
    }

    /** As {@link #governedJson}, keeping only the named top-level fields: a token count takes fewer. */
    public String governedJson(ChatRequest governed, Collection<String> fields) {
        ObjectNode body = governedBody(governed);
        body.retain(fields);
        return write(body);
    }

    private ObjectNode governedBody(ChatRequest governed) {
        ObjectNode copy = raw.deepCopy();
        List<Value> values = values(governed.getMessages());
        if (values.size() != slots.size()) {
            throw new GatewayException("UNSUPPORTED_CAPABILITY", "A gateway control changed the shape of the "
                    + "conversation (it added or removed messages), which a request sent on to Anthropic as it "
                    + "came cannot carry. Send a shorter conversation.");
        }
        for (int i = 0; i < slots.size(); i++) {
            Slot slot = slots.get(i);
            Value value = values.get(i);
            if (slot.kind != value.kind) {
                throw new GatewayException("UNSUPPORTED_CAPABILITY", "A gateway control changed the shape of the "
                        + "conversation, which a request sent on to Anthropic as it came cannot carry.");
            }
            slot.write(copy, value.text);
        }
        if (governed.getModel() != null && !governed.getModel().equals(copy.path("model").asText(null))) {
            copy.put("model", governed.getModel());
        }
        setIfChanged(copy, "max_tokens", governed.getMaxTokens());
        setIfChanged(copy, "temperature", governed.getTemperature());
        setIfChanged(copy, "top_p", governed.getTopP());
        return copy;
    }

    private static void setIfChanged(ObjectNode body, String field, Number value) {
        if (value == null) {
            return;
        }
        JsonNode current = body.get(field);
        if (current != null && current.isNumber()
                && current.decimalValue().compareTo(new java.math.BigDecimal(value.toString())) == 0) {
            return;
        }
        if (value instanceof Integer n) {
            body.put(field, n);
        } else {
            body.put(field, new java.math.BigDecimal(value.toString()));
        }
    }

    // -------------------------------------------------------------------------
    // The response Anthropic sent
    // -------------------------------------------------------------------------

    /** Records Anthropic's own response to this request, for the caller to be handed back as it came. */
    public void upstreamResponse(ObjectNode response) {
        this.upstreamResponse = response;
    }

    /** Anthropic's own response to this request, or null when no Anthropic provider answered it. */
    public ObjectNode upstreamResponse() {
        return upstreamResponse;
    }

    /** Reads a response body. */
    public static ObjectNode parseResponse(String body) {
        try {
            JsonNode node = JSON.readTree(body == null ? "" : body);
            if (node instanceof ObjectNode object) {
                return object;
            }
        } catch (Exception e) {
            // fall through
        }
        throw new GatewayException("PROVIDER_ERROR", "Anthropic returned a response that is not a JSON object");
    }

    /**
     * A response's content as the gateway's assistant message: each text block its own text block, thinking
     * and redacted thinking as they came, each {@code tool_use} a tool call, in order. A block of a type the
     * gateway does not read is left out here and counted in {@code opaque}.
     */
    public static MultimodalMessage responseMessage(JsonNode response, Map<String, Integer> opaque) {
        List<ContentBlock> content = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode block : response.path("content")) {
            String type = block.path("type").asText("");
            switch (type) {
                case "text" -> content.add(new ContentBlock.TextBlock(block.path("text").asText("")));
                case "thinking" -> content.add(new ContentBlock.ThinkingBlock(block.path("thinking").asText(""),
                        block.hasNonNull("signature") ? block.path("signature").asText() : null));
                case "redacted_thinking" -> content.add(new ContentBlock.RedactedThinkingBlock(block.path("data").asText("")));
                case "tool_use" -> calls.add(ToolCall.builder().id(block.path("id").asText(null))
                        .name(block.path("name").asText(null))
                        .arguments(write(block.has("input") ? block.get("input") : JSON.createObjectNode())).build());
                default -> opaque.merge(type, 1, Integer::sum);
            }
        }
        return MultimodalMessage.builder().role("assistant").content(content)
                .toolCalls(calls.isEmpty() ? null : calls).build();
    }

    /**
     * Anthropic's response with the governed message's text, thinking and tool input written back in place;
     * every other part as it came. Null when the governed message no longer lines up block for block with
     * the response, and the caller renders the governed message instead.
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
        for (JsonNode node : copy.path("content")) {
            if (!(node instanceof ObjectNode block)) {
                continue;
            }
            switch (block.path("type").asText("")) {
                case "text" -> {
                    if (!text.hasNext()) {
                        return null;
                    }
                    putIfChanged(block, "text", text.next());
                }
                case "thinking" -> {
                    if (!thought.hasNext()) {
                        return null;
                    }
                    putIfChanged(block, "thinking", thought.next());
                }
                case "tool_use" -> {
                    if (!call.hasNext()) {
                        return null;
                    }
                    if (!writeInput(block, call.next().getArguments())) {
                        return null;
                    }
                }
                default -> { }
            }
        }
        // Text the gateway added (an empty answer block is not one) is not in Anthropic's response.
        while (text.hasNext()) {
            String extra = text.next();
            if (extra != null && !extra.isEmpty()) {
                return null;
            }
        }
        return text.hasNext() || thought.hasNext() || call.hasNext() ? null : copy;
    }

    /** JSON text, numbers exact. */
    public static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            throw new GatewayException("INTERNAL_ERROR", "Could not write JSON: " + e.getMessage());
        }
    }

    private static void putIfChanged(ObjectNode block, String field, String value) {
        if (!Objects.equals(block.path(field).asText(null), value)) {
            block.put(field, value);
        }
    }

    /** Writes tool-call arguments back as a block's {@code input} when they changed; false when unreadable. */
    private static boolean writeInput(ObjectNode block, String arguments) {
        JsonNode current = block.has("input") ? block.get("input") : JSON.createObjectNode();
        String now = arguments == null || arguments.isBlank() ? "{}" : arguments;
        if (now.equals(write(current))) {
            return true;
        }
        try {
            JsonNode parsed = JSON.readTree(now);
            if (!parsed.equals(current)) {
                block.set("input", parsed);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Reading the request
    // -------------------------------------------------------------------------

    private enum Kind { TEXT, THINKING, TOOL_INPUT }

    /** Where one piece of governed text came from: a field of the object at {@code parent}, or nowhere. */
    private record Slot(Kind kind, JsonPointer parent, String field) {

        void write(ObjectNode body, String value) {
            if (field == null) {
                return;
            }
            JsonNode node = body.at(parent);
            if (!(node instanceof ObjectNode object)) {
                return;
            }
            if (kind == Kind.TOOL_INPUT) {
                if (!writeInput(object, value)) {
                    throw new GatewayException("UNSUPPORTED_CAPABILITY", "A gateway control left a tool call's "
                            + "arguments as text that is not JSON, which cannot be sent on as a tool_use input.");
                }
                return;
            }
            putIfChanged(object, field, value);
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
            if (m.getContent() != null) {
                for (ContentBlock b : m.getContent()) {
                    if (b instanceof ContentBlock.TextBlock t) {
                        out.add(new Value(Kind.TEXT, t.text()));
                    } else if (b instanceof ContentBlock.ThinkingBlock t) {
                        out.add(new Value(Kind.THINKING, t.thinking()));
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

        void read(ObjectNode body) {
            if (body.path("tool_choice").path("disable_parallel_tool_use").asBoolean(false)) {
                othersIgnore.add("tool_choice.disable_parallel_tool_use");
            }
            for (JsonNode tool : body.path("tools")) {
                cacheControl(tool);
            }
            system(body.get("system"));
            JsonNode list = body.get("messages");
            if (list instanceof ArrayNode array) {
                for (int i = 0; i < array.size(); i++) {
                    message(array.get(i), "/messages/" + i);
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
                            List.of(new Slot(Kind.TEXT, JsonPointer.compile(""), "system")), List.of());
                }
                return;
            }
            if (!(system instanceof ArrayNode blocks)) {
                throw invalid("system must be a string or a list of text blocks");
            }
            List<ContentBlock> content = new ArrayList<>();
            List<Slot> textSlots = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode block = blocks.get(i);
                if ("text".equals(type(block))) {
                    content.add(new ContentBlock.TextBlock(block.path("text").asText("")));
                    textSlots.add(new Slot(Kind.TEXT, JsonPointer.compile("/system/" + i), "text"));
                } else {
                    opaque("system", type(block));
                }
            }
            if (!content.isEmpty()) {
                add(MultimodalMessage.builder().role("system").content(content).build(), textSlots, List.of());
            }
        }

        private void message(JsonNode m, String at) {
            if (!(m instanceof ObjectNode)) {
                throw invalid("messages must be objects");
            }
            String role = m.path("role").asText("");
            if (!"user".equals(role) && !"assistant".equals(role) && !"system".equals(role)) {
                opaque.merge("role:" + role, 1, Integer::sum);
                othersIgnore.add("role:" + role);
                return;
            }
            JsonNode content = m.get("content");
            if (content != null && content.isTextual()) {
                add(MultimodalMessage.builder().role(role)
                        .content(List.of(new ContentBlock.TextBlock(content.asText()))).build(),
                        List.of(new Slot(Kind.TEXT, JsonPointer.compile(at), "content")), List.of());
                return;
            }
            if (!(content instanceof ArrayNode blocks)) {
                throw invalid("message content must be a string or a list of blocks");
            }
            switch (role) {
                case "assistant" -> assistant(blocks, at);
                case "user" -> user(blocks, at);
                default -> systemMessage(blocks, at);
            }
        }

        private void systemMessage(ArrayNode blocks, String at) {
            List<ContentBlock> content = new ArrayList<>();
            List<Slot> textSlots = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode block = blocks.get(i);
                if ("text".equals(type(block))) {
                    content.add(new ContentBlock.TextBlock(block.path("text").asText("")));
                    textSlots.add(new Slot(Kind.TEXT, JsonPointer.compile(at + "/content/" + i), "text"));
                } else {
                    opaque("system", type(block));
                }
            }
            add(MultimodalMessage.builder().role("system").content(content).build(), textSlots, List.of());
        }

        private void assistant(ArrayNode blocks, String at) {
            List<ContentBlock> content = new ArrayList<>();
            List<Slot> contentSlots = new ArrayList<>();
            List<ToolCall> calls = new ArrayList<>();
            List<Slot> callSlots = new ArrayList<>();
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode block = blocks.get(i);
                JsonPointer here = JsonPointer.compile(at + "/content/" + i);
                switch (type(block)) {
                    case "text" -> {
                        content.add(new ContentBlock.TextBlock(block.path("text").asText("")));
                        contentSlots.add(new Slot(Kind.TEXT, here, "text"));
                    }
                    // Signed by Anthropic, so carried exactly as they came.
                    case "thinking" -> {
                        content.add(new ContentBlock.ThinkingBlock(block.path("thinking").asText(""),
                                block.hasNonNull("signature") ? block.path("signature").asText() : null));
                        contentSlots.add(new Slot(Kind.THINKING, here, "thinking"));
                    }
                    case "redacted_thinking" ->
                            content.add(new ContentBlock.RedactedThinkingBlock(block.path("data").asText("")));
                    case "tool_use" -> {
                        calls.add(ToolCall.builder()
                                .id(block.path("id").asText(""))
                                .name(block.path("name").asText(""))
                                .arguments(write(block.has("input") ? block.get("input") : JSON.createObjectNode()))
                                .build());
                        callSlots.add(new Slot(Kind.TOOL_INPUT, here, "input"));
                    }
                    default -> opaque("assistant", type(block));
                }
            }
            add(MultimodalMessage.builder().role("assistant").content(content)
                    .toolCalls(calls.isEmpty() ? null : calls).build(), contentSlots, callSlots);
        }

        private void user(ArrayNode blocks, String at) {
            List<ContentBlock> content = new ArrayList<>();
            List<Slot> contentSlots = new ArrayList<>();
            boolean added = false;
            for (int i = 0; i < blocks.size(); i++) {
                JsonNode block = blocks.get(i);
                String here = at + "/content/" + i;
                switch (type(block)) {
                    case "text" -> {
                        content.add(new ContentBlock.TextBlock(block.path("text").asText("")));
                        contentSlots.add(new Slot(Kind.TEXT, JsonPointer.compile(here), "text"));
                    }
                    case "image" -> {
                        ContentBlock image = image(block);
                        if (image == null) {
                            opaque("user", "image");
                        } else {
                            content.add(image);
                        }
                    }
                    case "tool_result" -> {
                        if (!content.isEmpty()) {
                            // A result after other content: keep the order the caller gave.
                            add(MultimodalMessage.builder().role("user").content(content).build(), contentSlots, List.of());
                            content = new ArrayList<>();
                            contentSlots = new ArrayList<>();
                        }
                        toolResult(block, here);
                        added = true;
                    }
                    default -> opaque("user", type(block));
                }
            }
            if (!content.isEmpty() || !added) {
                add(MultimodalMessage.builder().role("user").content(content).build(), contentSlots, List.of());
            }
        }

        private void toolResult(JsonNode block, String at) {
            JsonNode content = block.get("content");
            List<ContentBlock> texts = new ArrayList<>();
            List<Slot> textSlots = new ArrayList<>();
            if (content == null || content.isNull()) {
                texts.add(new ContentBlock.TextBlock(""));
                textSlots.add(new Slot(Kind.TEXT, null, null));
            } else if (content.isTextual()) {
                texts.add(new ContentBlock.TextBlock(content.asText()));
                textSlots.add(new Slot(Kind.TEXT, JsonPointer.compile(at), "content"));
            } else if (content instanceof ArrayNode parts) {
                for (int i = 0; i < parts.size(); i++) {
                    JsonNode part = parts.get(i);
                    if ("text".equals(type(part))) {
                        texts.add(new ContentBlock.TextBlock(part.path("text").asText("")));
                        textSlots.add(new Slot(Kind.TEXT, JsonPointer.compile(at + "/content/" + i), "text"));
                    } else {
                        opaque("tool_result", type(part));
                    }
                }
                if (texts.isEmpty()) {
                    texts.add(new ContentBlock.TextBlock(""));
                    textSlots.add(new Slot(Kind.TEXT, null, null));
                }
            } else {
                throw invalid("tool_result content must be a string or a list of blocks");
            }
            MultimodalMessage result = MultimodalMessage.builder().role("tool")
                    .toolCallId(block.path("tool_use_id").asText("")).content(texts).build();
            if (block.path("is_error").asBoolean(false)) {
                result.setToolError(true);
            }
            add(result, textSlots, List.of());
        }

        private static ContentBlock image(JsonNode block) {
            JsonNode source = block.path("source");
            return switch (source.path("type").asText("")) {
                case "base64" -> new ContentBlock.ImageBlock(source.path("media_type").asText(""),
                        source.path("data").asText(""));
                case "url" -> new ContentBlock.ImageBlock(ContentBlock.ImageBlock.URL_MEDIA_TYPE,
                        source.path("url").asText(""));
                default -> null;
            };
        }

        private void add(MultimodalMessage message, List<Slot> contentSlots, List<Slot> callSlots) {
            messages.add(message);
            slots.addAll(contentSlots);
            slots.addAll(callSlots);
        }

        private void opaque(String where, String type) {
            opaque.merge(type, 1, Integer::sum);
            othersIgnore.add("block:" + type);
        }

        private void cacheControl(JsonNode block) {
            if (block != null && block.has("cache_control")) {
                othersIgnore.add("cache_control");
            }
        }

        private String type(JsonNode block) {
            if (!(block instanceof ObjectNode)) {
                throw invalid("content blocks must be objects");
            }
            cacheControl(block);
            return block.path("type").asText("");
        }

        private static GatewayException invalid(String message) {
            return new GatewayException("INVALID_REQUEST", message);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.lines().findFirst().orElse(m);
    }
}
