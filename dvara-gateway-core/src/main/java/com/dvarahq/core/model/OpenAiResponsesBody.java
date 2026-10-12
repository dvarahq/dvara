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
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * An OpenAI Responses API request body, kept as it came, with the text the gateway governs read out of it.
 *
 * <p>On a route to OpenAI or Azure OpenAI the body is sent to the provider's own {@code /responses} as it came:
 * every field, input item and tool, including what the gateway does not read, such as encrypted reasoning from
 * an earlier answer. The only edits are the ones governance makes — redacted text, the model a route maps to,
 * a token limit or sampling value a policy sets. Nothing is rebuilt from the gateway's own model, so a field a
 * client adds tomorrow reaches OpenAI without a gateway change.</p>
 *
 * <p>{@link #messages()} is the gateway's view of the same conversation, which its policy, PII and guardrail
 * checks read, and which any other provider is sent. Each piece of text in the view is tied to the place in the
 * body it came from ({@link Slot}), so {@link #governedJson} can write the governed text back there.</p>
 */
public final class OpenAiResponsesBody {

    /** Exact numbers both ways, so a value the gateway does not touch goes on as it came. */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    /** What a slot holds in the gateway's view. */
    public enum Kind {
        /** A text block of a message: {@code index} is the block's position in the message's content. */
        TEXT,
        /** A tool call's JSON arguments: {@code index} is the call's position on the message. */
        ARGUMENTS,
        /** A custom tool call's free-text input, carried in the view as {@code {"input": ...}} arguments. */
        CUSTOM_INPUT
    }

    /**
     * Where one piece of the view came from: message {@code message} of the view, the block or call at
     * {@code index}, and the JSON pointer of the string in the body.
     */
    public record Slot(int message, Kind kind, int index, String pointer) {
    }

    private final ObjectNode raw;
    private final List<MultimodalMessage> view;
    private final List<Slot> slots;
    private volatile ObjectNode upstreamResponse;

    /**
     * @throws IllegalArgumentException when a slot does not hold, in the body, the text the view has for it:
     *                                  the view and the body do not describe the same conversation
     */
    public OpenAiResponsesBody(ObjectNode raw, List<MultimodalMessage> view, List<Slot> slots) {
        this.raw = raw.deepCopy();
        this.view = List.copyOf(view);
        this.slots = List.copyOf(slots);
        for (Slot slot : this.slots) {
            JsonNode at = this.raw.at(slot.pointer());
            String inView = value(this.view, slot);
            if (!at.isTextual() || !at.textValue().equals(inView)) {
                throw new IllegalArgumentException("slot " + slot.pointer() + " does not match the view");
            }
        }
    }

    /** Reads a request body. */
    public static ObjectNode parse(byte[] body) {
        JsonNode node;
        try {
            node = body == null || body.length == 0 ? null : JSON.readTree(body);
        } catch (Exception e) {
            throw new GatewayException("INVALID_REQUEST", "The request body is not valid JSON");
        }
        if (!(node instanceof ObjectNode object)) {
            throw new GatewayException("INVALID_REQUEST", "The request body must be a JSON object");
        }
        return object;
    }

    /** Binds a body to a typed request, for the fields the gateway models. */
    public static <T> T bind(ObjectNode raw, Class<T> type) {
        try {
            return JSON.treeToValue(raw, type);
        } catch (Exception e) {
            throw new GatewayException("INVALID_REQUEST", "The request body could not be read: " + e.getMessage());
        }
    }

    /** The body as it came. A copy: the original is never changed. */
    public ObjectNode raw() {
        return raw.deepCopy();
    }

    /** The conversation as the gateway's messages. */
    public List<MultimodalMessage> messages() {
        return new ArrayList<>(view);
    }

    /**
     * The body to send: as it came, with the governed request's text written back where each piece came from,
     * {@code model} as the provider names it, {@code stream} as the call is made, and the token limit and
     * sampling values the gateway set. A value governance did not change is not touched.
     *
     * @throws GatewayException {@code UNSUPPORTED_CAPABILITY} when governance changed the conversation's shape,
     *                          which the body cannot carry
     */
    public String governedJson(ChatRequest governed, String model, boolean stream) {
        ObjectNode copy = raw.deepCopy();
        List<MultimodalMessage> messages = governed.getMessages() == null ? List.of() : governed.getMessages();
        if (messages.size() != view.size()) {
            throw shapeChanged();
        }
        for (Slot slot : slots) {
            String value;
            try {
                value = value(messages, slot);
            } catch (RuntimeException e) {
                throw shapeChanged();
            }
            JsonPointer pointer = JsonPointer.compile(slot.pointer());
            if (!Objects.equals(copy.at(pointer).asText(null), value)) {
                ((ObjectNode) copy.at(pointer.head())).put(pointer.last().getMatchingProperty(), value);
            }
        }
        if (model != null) {
            copy.put("model", model);
        }
        copy.put("stream", stream);
        setIfChanged(copy, "max_output_tokens", governed.getMaxTokens());
        setIfChanged(copy, "temperature", governed.getTemperature());
        setIfChanged(copy, "top_p", governed.getTopP());
        return write(copy);
    }

    private static GatewayException shapeChanged() {
        return new GatewayException("UNSUPPORTED_CAPABILITY", "A gateway control changed the shape of the "
                + "conversation (it added or removed messages or parts), which a request sent on to OpenAI as it "
                + "came cannot carry.");
    }

    private static String value(List<MultimodalMessage> messages, Slot slot) {
        MultimodalMessage m = messages.get(slot.message());
        return switch (slot.kind()) {
            case TEXT -> ((ContentBlock.TextBlock) m.getContent().get(slot.index())).text();
            case ARGUMENTS -> m.getToolCalls().get(slot.index()).getArguments();
            case CUSTOM_INPUT -> ToolDefinition.customInput(m.getToolCalls().get(slot.index()).getArguments());
        };
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
    // The response OpenAI sent
    // -------------------------------------------------------------------------

    /** Records the provider's own response to this request, for the caller to be handed back as it came. */
    public void upstreamResponse(ObjectNode response) {
        this.upstreamResponse = response;
    }

    /** The provider's own response to this request, or null when no OpenAI provider answered it. */
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
        throw new GatewayException("PROVIDER_ERROR", "The provider returned a response that is not a JSON object");
    }

    /**
     * A response's output as the gateway's assistant message: each {@code output_text} part a text block, each
     * function, custom or tool-search call a tool call, in order. Reasoning and any other item is not read.
     */
    public static MultimodalMessage responseMessage(JsonNode response) {
        List<ContentBlock> content = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode item : response.path("output")) {
            switch (item.path("type").asText("")) {
                case "message" -> {
                    for (JsonNode part : item.path("content")) {
                        if ("output_text".equals(part.path("type").asText(""))) {
                            content.add(new ContentBlock.TextBlock(part.path("text").asText("")));
                        }
                    }
                }
                case "function_call" -> calls.add(ToolCall.builder().id(item.path("call_id").asText(null))
                        .name(callName(item)).arguments(item.path("arguments").asText("")).build());
                case "custom_tool_call" -> calls.add(ToolCall.builder().id(item.path("call_id").asText(null))
                        .name(callName(item))
                        .arguments(ToolDefinition.customArguments(item.path("input").asText(""))).build());
                case "tool_search_call" -> calls.add(ToolCall.builder().id(item.path("call_id").asText(null))
                        .name("tool_search").arguments(write(item.path("arguments"))).build());
                default -> { }
            }
        }
        return MultimodalMessage.builder().role("assistant").content(content)
                .toolCalls(calls.isEmpty() ? null : calls).build();
    }

    private static String callName(JsonNode item) {
        String name = item.path("name").asText(null);
        String namespace = item.path("namespace").asText(null);
        return namespace == null || namespace.isEmpty() ? name : ToolDefinition.namespaced(namespace, name);
    }

    /** A Responses API usage object as the gateway's, its cached input and reasoning tokens included; null for none. */
    public static ChatResponse.Usage usage(JsonNode usage) {
        if (usage == null || usage.isNull() || usage.isMissingNode()) {
            return null;
        }
        int input = usage.path("input_tokens").asInt(0);
        int output = usage.path("output_tokens").asInt(0);
        int total = usage.path("total_tokens").asInt(input + output);
        return new ChatResponse.Usage(input, output, total,
                usage.path("input_tokens_details").path("cached_tokens").asInt(0), 0,
                usage.path("output_tokens_details").path("reasoning_tokens").asInt(0));
    }

    /**
     * The provider's response with the governed message's text and tool-call input written back in place; every
     * other part (reasoning, ids, usage) as it came. Null when the governed message no longer lines up with the
     * response, and the caller renders the governed message instead.
     */
    public static ObjectNode governedResponse(ObjectNode response, MultimodalMessage governed) {
        if (governed == null) {
            return null;
        }
        ObjectNode copy = response.deepCopy();
        List<String> texts = new ArrayList<>();
        if (governed.getContent() != null) {
            for (ContentBlock b : governed.getContent()) {
                if (b instanceof ContentBlock.TextBlock t) {
                    texts.add(t.text());
                }
            }
        }
        Iterator<String> text = texts.iterator();
        Iterator<ToolCall> call = (governed.getToolCalls() == null ? List.<ToolCall>of() : governed.getToolCalls()).iterator();
        for (JsonNode node : copy.path("output")) {
            if (!(node instanceof ObjectNode item)) {
                continue;
            }
            switch (item.path("type").asText("")) {
                case "message" -> {
                    for (JsonNode p : item.path("content")) {
                        if (p instanceof ObjectNode part && "output_text".equals(part.path("type").asText(""))) {
                            if (!text.hasNext()) {
                                return null;
                            }
                            putIfChanged(part, "text", text.next());
                        }
                    }
                }
                case "function_call" -> {
                    if (!call.hasNext()) {
                        return null;
                    }
                    putIfChanged(item, "arguments", call.next().getArguments());
                }
                case "custom_tool_call" -> {
                    if (!call.hasNext()) {
                        return null;
                    }
                    putIfChanged(item, "input", ToolDefinition.customInput(call.next().getArguments()));
                }
                case "tool_search_call" -> {
                    // Read for governance; its arguments are search terms, sent back as they came.
                    if (!call.hasNext()) {
                        return null;
                    }
                    call.next();
                }
                default -> { }
            }
        }
        while (text.hasNext()) {
            String extra = text.next();
            if (extra != null && !extra.isEmpty()) {
                return null;
            }
        }
        return call.hasNext() ? null : copy;
    }

    private static void putIfChanged(ObjectNode node, String field, String value) {
        if (!Objects.equals(node.path(field).asText(null), value)) {
            node.put(field, value);
        }
    }

    /** JSON text, numbers exact. */
    public static String write(JsonNode node) {
        try {
            return JSON.writeValueAsString(node);
        } catch (Exception e) {
            throw new GatewayException("INTERNAL_ERROR", "Could not write JSON: " + e.getMessage());
        }
    }
}
