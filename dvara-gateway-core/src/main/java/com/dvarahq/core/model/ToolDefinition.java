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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * A tool definition the client asks the model to consider: a function, or a custom tool ({@link #custom}).
 * Modelled after the OpenAI {@code tools[].function} shape — the only
 * tool type the gateway relays (hosted/built-in tools are an explicit
 * non-goal). Each provider's {@code buildBody} translates this into its native
 * tool shape (OpenAI {@code tools}, Anthropic {@code tools}, Gemini
 * {@code functionDeclarations}, Bedrock {@code toolConfig}).
 *
 * <p>The gateway never inspects or executes a tool — it relays the definition
 * to the provider and relays the resulting {@link ToolCall}s back to the
 * client. The agent loop belongs to the caller's framework.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {
    /** The function name the model calls. */
    private String name;
    /** Human-readable description that helps the model decide when to call it. */
    private String description;
    /** JSON-Schema object describing the function's arguments. Null on a custom tool. */
    private Map<String, Object> parameters;
    /**
     * OpenAI's {@code strict}: the model's arguments must follow {@link #parameters} exactly. Sent to OpenAI;
     * other providers have no such switch and are not sent it. Null when the client did not say.
     */
    private Boolean strict;
    /**
     * A custom tool: the caller's own tool whose input is free text rather than JSON arguments, for example a
     * patch. OpenAI takes it as it is. Every other provider is given it as a function with one string
     * argument, {@code input} ({@link #asFunction()}).
     */
    private boolean custom;
    /**
     * A custom tool's input format, in the Responses API's shape: {@code {"type": "text"}} or
     * {@code {"type": "grammar", "syntax": "lark" | "regex", "definition": "..."}}. Null for free text.
     */
    private Map<String, Object> format;

    /** A function tool. */
    public ToolDefinition(String name, String description, Map<String, Object> parameters) {
        this(name, description, parameters, null, false, null);
    }

    /** The one argument a custom tool's input travels in when it is given to a provider as a function. */
    public static final String CUSTOM_INPUT = "input";

    /**
     * This custom tool as a function with one string argument, {@code input}, for a provider that has no
     * custom tools. A grammar the input must follow is added to the description, because only OpenAI can
     * hold the model to it.
     */
    public ToolDefinition asFunction() {
        if (!custom) {
            return this;
        }
        StringBuilder text = new StringBuilder(description == null ? "" : description);
        if (format != null && "grammar".equals(String.valueOf(format.get("type")))) {
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append("Pass the whole input as the string argument input. It must follow this ")
                    .append(format.get("syntax")).append(" grammar:\n").append(format.get("definition"));
        }
        Map<String, Object> schema = new java.util.LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(CUSTOM_INPUT, Map.of("type", "string")));
        schema.put("required", java.util.List.of(CUSTOM_INPUT));
        schema.put("additionalProperties", false);
        return new ToolDefinition(name, text.toString(), schema, null, false, null);
    }

    /**
     * A custom tool call's input as the JSON arguments the gateway's request carries for it:
     * {@code {"input": "..."}}. One form for every provider, so a call can be replayed to any of them.
     */
    public static String customArguments(String input) {
        try {
            return com.dvarahq.core.util.JsonMapper.instance()
                    .writeValueAsString(Map.of(CUSTOM_INPUT, input == null ? "" : input));
        } catch (Exception e) {
            throw new IllegalStateException("could not write a custom tool input", e);
        }
    }

    /**
     * A custom tool call's input, read back from its JSON arguments; the arguments as they are when they are
     * not that shape, so nothing the model wrote is lost.
     */
    public static String customInput(String arguments) {
        if (arguments == null) {
            return "";
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node = com.dvarahq.core.util.JsonMapper.instance().readTree(arguments);
            com.fasterxml.jackson.databind.JsonNode input = node == null ? null : node.get(CUSTOM_INPUT);
            return input != null && input.isTextual() ? input.textValue() : arguments;
        } catch (Exception e) {
            return arguments;
        }
    }
}
