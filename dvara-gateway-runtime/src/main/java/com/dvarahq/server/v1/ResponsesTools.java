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
package com.dvarahq.server.v1;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Function calling on the Responses API, read into the gateway's own request.
 *
 * <ul>
 *   <li>A {@code function} tool ({@code {type, name, description, parameters}}) becomes a tool definition,
 *       which every provider sends in its own shape, as it does for chat.</li>
 *   <li>A {@code function_call} input item becomes a tool call on an assistant message. Calls that follow
 *       each other, and a call that follows the assistant's text, join one assistant message, because
 *       providers expect all the calls of one turn on one message.</li>
 *   <li>A {@code function_call_output} input item becomes a {@code tool} message answering that call.</li>
 *   <li>{@code tool_choice} is put in the form chat carries: {@code auto}, {@code none} and
 *       {@code required} as they are, {@code {type: function, name}} as a choice of that function.</li>
 * </ul>
 *
 * <p>A built-in tool type (web search, file search, code interpreter, computer use, image generation,
 * a remote MCP server, a shell) runs at the provider, where nothing it reads or returns passes the
 * gateway's checks, so it is refused with {@code UNSUPPORTED_CAPABILITY}.</p>
 */
final class ResponsesTools {

    private ResponsesTools() {
    }

    /** The request's function tools; null when it declares none. */
    static List<ToolDefinition> tools(List<Object> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<ToolDefinition> out = new ArrayList<>();
        for (Object t : tools) {
            if (!(t instanceof Map<?, ?> tool)) {
                throw invalid("each entry of tools must be an object");
            }
            String type = string(tool.get("type"));
            if (!"function".equals(type)) {
                throw unsupported(builtInToolMessage(type));
            }
            String name = string(tool.get("name"));
            if (name.isBlank()) {
                throw invalid("a function tool needs a name");
            }
            Object parameters = tool.get("parameters");
            if (parameters != null && !(parameters instanceof Map<?, ?>)) {
                throw invalid("the parameters of tool " + name + " must be a JSON Schema object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) parameters;
            out.add(ToolDefinition.builder()
                    .name(name)
                    .description(tool.get("description") == null ? null : string(tool.get("description")))
                    .parameters(schema)
                    .build());
        }
        return out;
    }

    private static String builtInToolMessage(String type) {
        if (type.isBlank()) {
            return "a tool on /v1/responses needs a type; only function tools are supported.";
        }
        return "the tool type " + type + " is not supported on /v1/responses: it runs at the provider, "
                + "outside the gateway's checks. Declare a function tool and run it in your own code.";
    }

    /**
     * The request's {@code tool_choice} in the form chat carries, or null. With no tools there is nothing to
     * choose, and providers refuse a choice without tools, so it is not sent.
     */
    static Object toolChoice(Object choice, boolean hasTools) {
        if (choice == null) {
            return null;
        }
        if (choice instanceof String s) {
            return switch (s) {
                case "auto", "none", "required" -> hasTools ? s : null;
                default -> throw invalid("tool_choice must be auto, none, required or a function choice");
            };
        }
        if (choice instanceof Map<?, ?> m) {
            String type = string(m.get("type"));
            if ("function".equals(type)) {
                String name = string(m.get("name"));
                if (name.isBlank()) {
                    throw invalid("tool_choice of type function needs a name");
                }
                if (!hasTools) {
                    throw invalid("tool_choice names the function " + name + ", but the request declares no tools");
                }
                return Map.of("type", "function", "function", Map.of("name", name));
            }
            throw unsupported("tool_choice of type " + (type.isBlank() ? "(none)" : type)
                    + " is not supported on /v1/responses; use auto, none, required or a function choice.");
        }
        throw invalid("tool_choice must be a string or an object");
    }

    /**
     * Adds a {@code function_call} input item to the conversation: to the assistant message just before it
     * when there is one, so the calls of one turn stay together; otherwise as a new assistant message.
     */
    static void addFunctionCall(List<MultimodalMessage> messages, Map<String, Object> item) {
        String callId = string(item.get("call_id"));
        String name = string(item.get("name"));
        if (callId.isBlank() || name.isBlank()) {
            throw invalid("a function_call input item needs call_id and name");
        }
        Object arguments = item.get("arguments");
        if (arguments != null && !(arguments instanceof String)) {
            throw invalid("the arguments of function_call " + callId + " must be a JSON string");
        }
        ToolCall call = ToolCall.builder()
                .id(callId).name(name).arguments(arguments == null ? "" : (String) arguments).build();

        MultimodalMessage last = messages.isEmpty() ? null : messages.get(messages.size() - 1);
        if (last != null && "assistant".equals(last.getRole())) {
            List<ToolCall> calls = last.getToolCalls() == null ? new ArrayList<>() : new ArrayList<>(last.getToolCalls());
            calls.add(call);
            last.setToolCalls(calls);
            return;
        }
        List<ToolCall> calls = new ArrayList<>();
        calls.add(call);
        messages.add(MultimodalMessage.builder().role("assistant").content(new ArrayList<>()).toolCalls(calls).build());
    }

    /**
     * A {@code function_call_output} input item as the {@code tool} message answering its call. The output is
     * a string, or a list of text parts. An image or file in a function's output has no place in a
     * {@code tool} message on most providers, so it is refused rather than left out.
     */
    static MultimodalMessage functionCallOutput(Map<String, Object> item) {
        String callId = string(item.get("call_id"));
        if (callId.isBlank()) {
            throw invalid("a function_call_output input item needs call_id");
        }
        Object output = item.get("output");
        List<ContentBlock> blocks = new ArrayList<>();
        if (output instanceof String s) {
            blocks.add(new ContentBlock.TextBlock(s));
        } else if (output instanceof List<?> parts) {
            for (Object p : parts) {
                if (!(p instanceof Map<?, ?> part)) {
                    throw invalid("the output parts of function_call_output " + callId + " must be objects");
                }
                String type = string(part.get("type"));
                if (!"input_text".equals(type) && !"output_text".equals(type)) {
                    throw unsupported("function_call_output parts of type " + type
                            + " are not supported on /v1/responses; send the output as text.");
                }
                blocks.add(new ContentBlock.TextBlock(string(part.get("text"))));
            }
        } else {
            throw invalid("function_call_output " + callId + " needs an output string");
        }
        return MultimodalMessage.builder().role("tool").toolCallId(callId).content(blocks).build();
    }

    private static String string(Object o) {
        return o == null ? "" : o.toString();
    }

    private static GatewayException unsupported(String message) {
        return new GatewayException("UNSUPPORTED_CAPABILITY", message);
    }

    private static GatewayException invalid(String message) {
        return new GatewayException("INVALID_REQUEST", message);
    }
}
