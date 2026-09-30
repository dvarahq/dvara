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
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.server.v1.dto.MessagesRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Translation between the Anthropic Messages API and the gateway's own request and response.
 *
 * <p>Only translation: the request that comes out runs the same governance line as every other
 * doorway. Everything that has a place in the gateway's model is carried across; everything that has
 * none is refused with {@code UNSUPPORTED_CAPABILITY}, because serving the request without it would
 * serve a different request than the one sent.</p>
 *
 * <ul>
 *   <li>{@code system} becomes a system message, never user content.</li>
 *   <li>A {@code tool_use} block becomes a tool call on the assistant message, its {@code input}
 *       serialized to the JSON text a tool call carries.</li>
 *   <li>A {@code tool_result} block becomes a {@code tool} message answering that call. The blocks
 *       of one user turn keep their order: each result, then any text and images in a user message.</li>
 *   <li>A tool definition's {@code input_schema} becomes its parameters.</li>
 * </ul>
 */
final class AnthropicMessages {

    /** The one API version this doorway speaks. */
    static final String VERSION = "2023-06-01";

    /** Blocks that may appear in a message's content. */
    private static final Set<String> BLOCK_TYPES = Set.of("text", "image", "tool_use", "tool_result");

    private AnthropicMessages() {
    }

    /**
     * Refuses a request with no {@code anthropic-version} header or one this doorway does not speak,
     * rather than serving it under an assumed version.
     */
    static void checkVersion(String version) {
        if (version == null || version.isBlank()) {
            throw new GatewayException("UNSUPPORTED_API_VERSION",
                    "The anthropic-version header is required; this gateway speaks " + VERSION + ".");
        }
        if (!VERSION.equals(version.trim())) {
            throw new GatewayException("UNSUPPORTED_API_VERSION",
                    "anthropic-version " + version.trim() + " is not supported; this gateway speaks " + VERSION + ".");
        }
    }

    // -------------------------------------------------------------------------
    // Request
    // -------------------------------------------------------------------------

    static ChatRequest toInternal(MessagesRequest r) {
        rejectUnsupported(r);
        List<MultimodalMessage> messages = new ArrayList<>();
        String system = systemText(r.getSystem());
        if (system != null) {
            messages.add(MultimodalMessage.builder().role("system")
                    .content(List.of(new ContentBlock.TextBlock(system))).build());
        }
        for (Map<String, Object> m : r.getMessages()) {
            messages.addAll(message(m));
        }
        return ChatRequest.builder()
                .model(r.getModel())
                .messages(messages)
                .stream(Boolean.TRUE.equals(r.getStream()))
                .maxTokens(r.getMaxTokens())
                .temperature(r.getTemperature())
                .topP(r.getTopP())
                .stop(r.getStopSequences())
                .tools(tools(r.getTools()))
                .toolChoice(toolChoice(r.getToolChoice()))
                .user(endUser(r.getMetadata()))
                .build();
    }

    private static void rejectUnsupported(MessagesRequest r) {
        if (!r.getUnsupported().isEmpty()) {
            throw unsupported("these fields are not supported on /v1/messages: "
                    + String.join(", ", r.getUnsupported().keySet()) + ".");
        }
        if (r.getTopK() != null) {
            throw unsupported("top_k is not supported on /v1/messages.");
        }
        if (r.getThinking() != null && !"disabled".equals(String.valueOf(r.getThinking().get("type")))) {
            throw unsupported("extended thinking is not supported on /v1/messages.");
        }
        if (r.getMetadata() != null) {
            for (String key : r.getMetadata().keySet()) {
                if (!"user_id".equals(key)) {
                    throw unsupported("metadata." + key + " is not supported on /v1/messages; only user_id is.");
                }
            }
        }
    }

    private static String systemText(Object system) {
        if (system == null) {
            return null;
        }
        if (system instanceof String s) {
            return s.isEmpty() ? null : s;
        }
        if (system instanceof List<?> blocks) {
            List<String> parts = new ArrayList<>();
            for (Object b : blocks) {
                Map<String, Object> block = asMap(b, "system blocks must be objects");
                if (!"text".equals(block.get("type"))) {
                    throw invalid("system blocks must be text blocks, not " + block.get("type"));
                }
                parts.add(string(block.get("text")));
            }
            return parts.isEmpty() ? null : String.join("\n\n", parts);
        }
        throw invalid("system must be a string or a list of text blocks");
    }

    /** One Anthropic message as one or more of the gateway's messages. */
    private static List<MultimodalMessage> message(Map<String, Object> m) {
        String role = string(m.get("role"));
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw invalid("message role must be user or assistant, not " + role);
        }
        Object content = m.get("content");
        if (content instanceof String s) {
            return List.of(MultimodalMessage.builder().role(role)
                    .content(List.of(new ContentBlock.TextBlock(s))).build());
        }
        if (!(content instanceof List<?> blocks)) {
            throw invalid("message content must be a string or a list of blocks");
        }
        return "assistant".equals(role) ? List.of(assistant(blocks)) : user(blocks);
    }

    private static MultimodalMessage assistant(List<?> blocks) {
        List<ContentBlock> content = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        for (Object b : blocks) {
            Map<String, Object> block = block(b);
            switch (string(block.get("type"))) {
                case "text" -> content.add(new ContentBlock.TextBlock(string(block.get("text"))));
                case "tool_use" -> calls.add(ToolCall.builder()
                        .id(string(block.get("id")))
                        .name(string(block.get("name")))
                        .arguments(json(block.get("input") == null ? Map.of() : block.get("input")))
                        .build());
                default -> throw unsupported("an assistant message may carry text and tool_use blocks on "
                        + "/v1/messages, not " + block.get("type") + ".");
            }
        }
        return MultimodalMessage.builder().role("assistant").content(content)
                .toolCalls(calls.isEmpty() ? null : calls).build();
    }

    private static List<MultimodalMessage> user(List<?> blocks) {
        List<MultimodalMessage> out = new ArrayList<>();
        List<ContentBlock> content = new ArrayList<>();
        for (Object b : blocks) {
            Map<String, Object> block = block(b);
            switch (string(block.get("type"))) {
                case "text" -> content.add(new ContentBlock.TextBlock(string(block.get("text"))));
                case "image" -> content.add(image(block));
                case "tool_result" -> {
                    if (!content.isEmpty()) {
                        // A result after other content: keep the order the caller gave.
                        out.add(MultimodalMessage.builder().role("user").content(content).build());
                        content = new ArrayList<>();
                    }
                    out.add(toolResult(block));
                }
                default -> throw unsupported("a user message may carry text, image and tool_result blocks on "
                        + "/v1/messages, not " + block.get("type") + ".");
            }
        }
        if (!content.isEmpty() || out.isEmpty()) {
            out.add(MultimodalMessage.builder().role("user").content(content).build());
        }
        return out;
    }

    private static MultimodalMessage toolResult(Map<String, Object> block) {
        Object content = block.get("content");
        String text;
        if (content == null) {
            text = "";
        } else if (content instanceof String s) {
            text = s;
        } else if (content instanceof List<?> parts) {
            List<String> texts = new ArrayList<>();
            for (Object p : parts) {
                Map<String, Object> part = block(p);
                if (!"text".equals(part.get("type"))) {
                    throw unsupported("a tool_result may carry text on /v1/messages, not " + part.get("type") + ".");
                }
                texts.add(string(part.get("text")));
            }
            text = String.join("\n", texts);
        } else {
            throw invalid("tool_result content must be a string or a list of blocks");
        }
        MultimodalMessage result = MultimodalMessage.toolResult(string(block.get("tool_use_id")), text);
        if (Boolean.TRUE.equals(block.get("is_error"))) {
            result.setToolError(true);
        }
        return result;
    }

    private static ContentBlock image(Map<String, Object> block) {
        Map<String, Object> source = asMap(block.get("source"), "an image block needs a source");
        return switch (string(source.get("type"))) {
            case "base64" -> new ContentBlock.ImageBlock(string(source.get("media_type")), string(source.get("data")));
            case "url" -> new ContentBlock.ImageBlock(ContentBlock.ImageBlock.URL_MEDIA_TYPE, string(source.get("url")));
            default -> throw unsupported("image source " + source.get("type") + " is not supported on /v1/messages.");
        };
    }

    private static List<ToolDefinition> tools(List<Map<String, Object>> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }
        List<ToolDefinition> out = new ArrayList<>();
        for (Map<String, Object> t : tools) {
            Object type = t.get("type");
            if (type != null && !"custom".equals(type)) {
                // A server tool (web search, code execution, a computer) runs at the provider; it is
                // not a function the caller defines and executes.
                throw unsupported("the server tool " + type + " is not supported on /v1/messages.");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = t.get("input_schema") instanceof Map<?, ?> s
                    ? (Map<String, Object>) s : null;
            if (schema == null) {
                throw invalid("tool " + t.get("name") + " needs an input_schema object");
            }
            out.add(ToolDefinition.builder()
                    .name(string(t.get("name")))
                    .description(t.get("description") == null ? null : string(t.get("description")))
                    .parameters(schema)
                    .build());
        }
        return out;
    }

    /** Anthropic's {@code tool_choice} in the OpenAI form the gateway's request carries. */
    private static Object toolChoice(Map<String, Object> choice) {
        if (choice == null) {
            return null;
        }
        if (Boolean.TRUE.equals(choice.get("disable_parallel_tool_use"))) {
            throw unsupported("disable_parallel_tool_use is not supported on /v1/messages.");
        }
        return switch (string(choice.get("type"))) {
            case "auto" -> "auto";
            case "any" -> "required";
            case "none" -> "none";
            case "tool" -> Map.of("type", "function", "function", Map.of("name", string(choice.get("name"))));
            default -> throw invalid("tool_choice type must be auto, any, tool or none");
        };
    }

    private static String endUser(Map<String, Object> metadata) {
        if (metadata == null || metadata.get("user_id") == null) {
            return null;
        }
        String id = string(metadata.get("user_id"));
        return id.isBlank() ? null : id;
    }

    // -------------------------------------------------------------------------
    // Response
    // -------------------------------------------------------------------------

    /** A whole response as an Anthropic {@code message} object. */
    static Map<String, Object> toMessage(ChatResponse resp, String requestedModel) {
        List<Map<String, Object>> content = new ArrayList<>();
        String finishReason = null;
        if (resp.getChoices() != null && !resp.getChoices().isEmpty()) {
            ChatResponse.Choice choice = resp.getChoices().get(0);
            finishReason = choice.getFinishReason();
            MultimodalMessage msg = choice.getMessage();
            if (msg != null && msg.getContent() != null) {
                for (ContentBlock b : msg.getContent()) {
                    if (b instanceof ContentBlock.TextBlock t && t.text() != null && !t.text().isEmpty()) {
                        content.add(map("type", "text", "text", t.text()));
                    }
                }
            }
            if (msg != null && msg.getToolCalls() != null) {
                for (ToolCall call : msg.getToolCalls()) {
                    content.add(map("type", "tool_use", "id", call.getId(), "name", call.getName(),
                            "input", toolInput(call.getArguments())));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", messageId());
        out.put("type", "message");
        out.put("role", "assistant");
        out.put("model", resp.getModel() != null ? resp.getModel() : requestedModel);
        out.put("content", content);
        out.put("stop_reason", stopReason(finishReason));
        out.put("stop_sequence", null);
        out.put("usage", usage(resp.getUsage()));
        return out;
    }

    /**
     * The gateway's finish reason as an Anthropic {@code stop_reason}. A reason the Anthropic
     * provider passes through unchanged ({@code max_tokens}, {@code stop_sequence}) is already one.
     */
    static String stopReason(String finishReason) {
        if (finishReason == null) {
            return "end_turn";
        }
        return switch (finishReason) {
            case "stop", "end_turn" -> "end_turn";
            case "length", "max_tokens" -> "max_tokens";
            case "tool_calls", "function_call", "tool_use" -> "tool_use";
            case "content_filter", "refusal" -> "refusal";
            case "stop_sequence" -> "stop_sequence";
            case "pause_turn" -> "pause_turn";
            default -> "end_turn";
        };
    }

    /**
     * Usage in Anthropic's terms, where {@code input_tokens} excludes what was read from or written
     * to the prompt cache; the gateway's prompt count includes both.
     */
    static Map<String, Object> usage(ChatResponse.Usage u) {
        if (u == null) {
            return map("input_tokens", 0, "output_tokens", 0);
        }
        int fresh = Math.max(0, u.getPromptTokens() - u.getCachedInputTokens() - u.getCacheWriteTokens());
        Map<String, Object> out = map("input_tokens", fresh, "output_tokens", u.getCompletionTokens());
        out.put("cache_read_input_tokens", u.getCachedInputTokens());
        out.put("cache_creation_input_tokens", u.getCacheWriteTokens());
        return out;
    }

    /** A tool call's JSON arguments as the object Anthropic's {@code input} is. */
    static Object toolInput(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return JsonMapper.instance().readValue(arguments, Object.class);
        } catch (Exception e) {
            throw new GatewayException("PROVIDER_ERROR",
                    "The model returned tool call arguments that are not valid JSON");
        }
    }

    static String messageId() {
        return "msg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Map<String, Object> block(Object b) {
        Map<String, Object> block = asMap(b, "content blocks must be objects");
        if (!BLOCK_TYPES.contains(string(block.get("type")))) {
            throw unsupported("the content block type " + block.get("type") + " is not supported on /v1/messages.");
        }
        return block;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o, String message) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        throw invalid(message);
    }

    private static String string(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String json(Object value) {
        try {
            return JsonMapper.instance().writeValueAsString(value);
        } catch (Exception e) {
            throw invalid("a tool_use input could not be read as JSON");
        }
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static GatewayException unsupported(String message) {
        return new GatewayException("UNSUPPORTED_CAPABILITY", message);
    }

    private static GatewayException invalid(String message) {
        return new GatewayException("INVALID_REQUEST", message);
    }
}
