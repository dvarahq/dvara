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
 * The caller's own tools on the Responses API, read into the gateway's own request.
 *
 * <ul>
 *   <li>A {@code function} tool ({@code {type, name, description, parameters, strict}}) becomes a tool
 *       definition, which every provider sends in its own shape, as it does for chat.</li>
 *   <li>A {@code custom} tool takes free text instead of JSON arguments, for example a patch. OpenAI takes it
 *       as it is; another provider is given a function with one string argument, {@code input}. Its calls
 *       travel as {@code {"input": "..."}} and come back as {@code custom_tool_call} items.</li>
 *   <li>A {@code namespace} groups tools, for example one MCP server's. Each inner tool becomes a tool of its
 *       own under a name joining the two ({@link Names}), and a call to it comes back under its own name with
 *       its {@code namespace}.</li>
 *   <li>A {@code function_call} or {@code custom_tool_call} input item becomes a tool call on an assistant
 *       message. Calls that follow each other, and a call that follows the assistant's text, join one
 *       assistant message, because providers expect all the calls of one turn on one message.</li>
 *   <li>A {@code function_call_output} or {@code custom_tool_call_output} input item becomes a {@code tool}
 *       message answering that call, with its text and images.</li>
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

    /**
     * The names the request's tools travel under, and what each one is: which are custom, and which came from
     * a namespace. A namespace's tool travels as {@code namespace__name}, cut to the 64 characters providers
     * allow with a hash of the whole to keep it unique, so its calls can be given back under their own name.
     */
    static final class Names {
        static final Names NONE = new Names();
        private final Map<String, String[]> namespaced = new java.util.HashMap<>();
        private final java.util.Set<String> custom = new java.util.HashSet<>();

        /** The name a namespace's tool travels under. The same for the tool and for every call to it. */
        static String joined(String namespace, String name) {
            String joined = namespace.endsWith("_") ? namespace + name : namespace + "__" + name;
            joined = joined.replaceAll("[^A-Za-z0-9_-]", "_");
            if (joined.length() <= 64) {
                return joined;
            }
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(joined.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                return joined.substring(0, 55) + "_" + java.util.HexFormat.of().formatHex(digest, 0, 4);
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        private void add(String travelling, String namespace, String name, boolean isCustom) {
            if (namespace != null) {
                namespaced.put(travelling, new String[] {namespace, name});
            }
            if (isCustom) {
                custom.add(travelling);
            }
        }

        boolean isCustom(String travelling) {
            return travelling != null && custom.contains(travelling);
        }

        /** The namespace a travelling name came from, or null. */
        String namespace(String travelling) {
            String[] n = travelling == null ? null : namespaced.get(travelling);
            return n == null ? null : n[0];
        }

        /** The tool's own name for a travelling name. */
        String name(String travelling) {
            String[] n = travelling == null ? null : namespaced.get(travelling);
            return n == null ? travelling : n[1];
        }
    }

    /** The request's tools, and the names they travel under. Tools is null when it declares none. */
    record Read(List<ToolDefinition> tools, Names names) {
    }

    static Read read(List<Object> tools) {
        if (tools == null || tools.isEmpty()) {
            return new Read(null, Names.NONE);
        }
        Names names = new Names();
        List<ToolDefinition> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object t : tools) {
            if (!(t instanceof Map<?, ?> tool)) {
                throw invalid("each entry of tools must be an object");
            }
            String type = string(tool.get("type"));
            if ("namespace".equals(type)) {
                String namespace = string(tool.get("name"));
                if (namespace.isBlank() || !(tool.get("tools") instanceof List<?> inner)) {
                    throw invalid("a namespace tool needs a name and a tools list");
                }
                for (Object i : inner) {
                    if (!(i instanceof Map<?, ?> innerTool)) {
                        throw invalid("each tool of namespace " + namespace + " must be an object");
                    }
                    ToolDefinition d = one(innerTool, namespace, names);
                    if (!seen.add(d.getName())) {
                        throw invalid("two tools would both be named " + d.getName());
                    }
                    out.add(d);
                }
                continue;
            }
            ToolDefinition d = one(tool, null, names);
            if (!seen.add(d.getName())) {
                throw invalid("two tools are both named " + d.getName());
            }
            out.add(d);
        }
        return new Read(out, names);
    }

    /** One function or custom tool, under the name it travels by. */
    private static ToolDefinition one(Map<?, ?> tool, String namespace, Names names) {
        String type = string(tool.get("type"));
        if (!"function".equals(type) && !"custom".equals(type)) {
            throw unsupported(builtInToolMessage(type));
        }
        String name = string(tool.get("name"));
        if (name.isBlank()) {
            throw invalid("a " + type + " tool needs a name");
        }
        String travelling = namespace == null ? name : Names.joined(namespace, name);
        names.add(travelling, namespace, name, "custom".equals(type));
        String description = tool.get("description") == null ? null : string(tool.get("description"));
        if ("custom".equals(type)) {
            Object format = tool.get("format");
            if (format != null && !(format instanceof Map<?, ?>)) {
                throw invalid("the format of custom tool " + name + " must be an object");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> f = (Map<String, Object>) format;
            if (f != null) {
                String formatType = string(f.get("type"));
                if ("grammar".equals(formatType)) {
                    if (string(f.get("definition")).isBlank() || string(f.get("syntax")).isBlank()) {
                        throw invalid("the grammar of custom tool " + name + " needs syntax and definition");
                    }
                } else if (!"text".equals(formatType)) {
                    throw invalid("the format of custom tool " + name + " must be text or grammar");
                }
            }
            return ToolDefinition.builder().name(travelling).description(description).custom(true).format(f).build();
        }
        Object parameters = tool.get("parameters");
        if (parameters != null && !(parameters instanceof Map<?, ?>)) {
            throw invalid("the parameters of tool " + name + " must be a JSON Schema object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) parameters;
        Object strict = tool.get("strict");
        return ToolDefinition.builder()
                .name(travelling)
                .description(description)
                .parameters(schema)
                .strict(strict instanceof Boolean b ? b : null)
                .build();
    }

    private static String builtInToolMessage(String type) {
        if (type.isBlank()) {
            return "a tool on /v1/responses needs a type: function, custom or namespace.";
        }
        if (type.startsWith("web_search")) {
            return "the tool type " + type + " is not supported on /v1/responses: it runs at the provider, "
                    + "outside the gateway's checks. In Codex CLI, set web_search = \"disabled\" in config.toml.";
        }
        if ("tool_search".equals(type)) {
            return "the tool type tool_search is not supported on /v1/responses; declare the tools themselves.";
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
     * Adds a {@code function_call} or {@code custom_tool_call} input item to the conversation: to the
     * assistant message just before it when there is one, so the calls of one turn stay together; otherwise as
     * a new assistant message. A call made in a namespace travels under the joined name its tool has.
     */
    static void addFunctionCall(List<MultimodalMessage> messages, Map<String, Object> item) {
        boolean custom = "custom_tool_call".equals(string(item.get("type")));
        String callId = string(item.get("call_id"));
        String name = string(item.get("name"));
        if (callId.isBlank() || name.isBlank()) {
            throw invalid("a " + (custom ? "custom_tool_call" : "function_call") + " input item needs call_id and name");
        }
        String namespace = string(item.get("namespace"));
        if (!namespace.isBlank()) {
            name = Names.joined(namespace, name);
        }
        String arguments;
        if (custom) {
            Object input = item.get("input");
            if (input != null && !(input instanceof String)) {
                throw invalid("the input of custom_tool_call " + callId + " must be a string");
            }
            arguments = ToolDefinition.customArguments((String) input);
        } else {
            Object args = item.get("arguments");
            if (args != null && !(args instanceof String)) {
                throw invalid("the arguments of function_call " + callId + " must be a JSON string");
            }
            arguments = args == null ? "" : (String) args;
        }
        ToolCall call = ToolCall.builder().id(callId).name(name).arguments(arguments).build();

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
     * A {@code function_call_output} or {@code custom_tool_call_output} input item as the {@code tool} message
     * answering its call. The output is a string, or a list of text and image parts. A provider that cannot take
     * an image in a tool result is given it in a user message after the results. A file part is refused: the
     * gateway carries no files.
     */
    static MultimodalMessage functionCallOutput(Map<String, Object> item) {
        String callId = string(item.get("call_id"));
        String type = string(item.get("type"));
        if (callId.isBlank()) {
            throw invalid("a " + type + " input item needs call_id");
        }
        Object output = item.get("output");
        List<ContentBlock> blocks = new ArrayList<>();
        if (output instanceof String s) {
            blocks.add(new ContentBlock.TextBlock(s));
        } else if (output instanceof List<?> parts) {
            for (Object p : parts) {
                if (!(p instanceof Map<?, ?> part)) {
                    throw invalid("the output parts of " + type + " " + callId + " must be objects");
                }
                String partType = string(part.get("type"));
                switch (partType) {
                    case "input_text", "output_text" -> blocks.add(new ContentBlock.TextBlock(string(part.get("text"))));
                    case "input_image" -> blocks.add(ChatInputs.image(part, "input_image"));
                    default -> throw unsupported(type + " parts of type " + partType
                            + " are not supported on /v1/responses; send text and images.");
                }
            }
        } else {
            throw invalid(type + " " + callId + " needs an output string or a list of parts");
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
