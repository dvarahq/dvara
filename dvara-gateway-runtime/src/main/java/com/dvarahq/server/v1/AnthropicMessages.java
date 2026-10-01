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
import com.dvarahq.core.model.AnthropicMessagesBody;
import com.dvarahq.core.model.AnthropicPassthrough;
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
 * The Anthropic Messages API on the gateway's side: the request read for governance, and the response in
 * Anthropic's shape when it has to be rebuilt.
 *
 * <p>The request body itself is kept as it came ({@link AnthropicMessagesBody}). Its text is read out as the
 * gateway's messages, so the same policy, PII and guardrail checks as every other doorway apply, and an
 * Anthropic provider sends the body on with only the governed edits. Any other provider is sent those
 * messages:</p>
 *
 * <ul>
 *   <li>{@code system} becomes a system message, never user content; a {@code system} message inside
 *       {@code messages} stays a system message at its place.</li>
 *   <li>A {@code tool_use} block becomes a tool call on the assistant message, its {@code input}
 *       serialized to the JSON text a tool call carries.</li>
 *   <li>A {@code tool_result} block becomes a {@code tool} message answering that call. The blocks
 *       of one user turn keep their order: each result, then any text and images in a user message.</li>
 *   <li>A tool definition's {@code input_schema} becomes its parameters.</li>
 * </ul>
 *
 * <p>Extended thinking is refused on a provider that is not Anthropic, naming the provider. What only tunes
 * how Anthropic serves the call — a top-level field the gateway does not model, a block type it does not
 * read, {@code cache_control} — is left out for another provider. A field the gateway knows it cannot govern,
 * because the provider would act on it outside the gateway, is refused on every provider with
 * {@code UNSUPPORTED_CAPABILITY}.</p>
 */
final class AnthropicMessages {

    /** The one API version this doorway speaks. */
    static final String VERSION = "2023-06-01";

    /**
     * Top-level fields the gateway cannot govern: with them the provider itself would call MCP servers or run
     * code in a container, and nothing it sent or got back there would pass the gateway's checks.
     */
    private static final Set<String> UNGOVERNABLE = Set.of("mcp_servers", "container");

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

    static ChatRequest toInternal(MessagesRequest r, String beta, AnthropicMessagesBody body) {
        rejectUnsupported(r);
        return ChatRequest.builder()
                .model(r.getModel())
                .messages(body.messages())
                .stream(Boolean.TRUE.equals(r.getStream()))
                .maxTokens(r.getMaxTokens())
                .temperature(r.getTemperature())
                .topP(r.getTopP())
                .stop(r.getStopSequences())
                .tools(tools(r.getTools()))
                .toolChoice(toolChoice(r.getToolChoice()))
                .user(endUser(r.getMetadata()))
                .anthropic(new AnthropicPassthrough(r.getThinking(), r.getOther(), beta, body))
                .build();
    }

    private static void rejectUnsupported(MessagesRequest r) {
        List<String> ungovernable = r.getOther().keySet().stream().filter(UNGOVERNABLE::contains).toList();
        if (!ungovernable.isEmpty()) {
            throw unsupported("these fields are not supported on /v1/messages, because the provider would act on "
                    + "them outside the gateway: " + String.join(", ", ungovernable) + ".");
        }
        if (r.getMetadata() != null) {
            for (String key : r.getMetadata().keySet()) {
                if (!"user_id".equals(key)) {
                    throw unsupported("metadata." + key + " is not supported on /v1/messages; only user_id is.");
                }
            }
        }
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
        // disable_parallel_tool_use goes to Anthropic with the body; another provider is not given it.
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
                    if (b instanceof ContentBlock.ThinkingBlock t) {
                        Map<String, Object> thinking = map("type", "thinking", "thinking", t.thinking() == null ? "" : t.thinking());
                        if (t.signature() != null) {
                            thinking.put("signature", t.signature());
                        }
                        content.add(thinking);
                    } else if (b instanceof ContentBlock.RedactedThinkingBlock r) {
                        content.add(map("type", "redacted_thinking", "data", r.data()));
                    } else if (b instanceof ContentBlock.TextBlock t && t.text() != null && !t.text().isEmpty()) {
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

    private static String string(Object o) {
        return o == null ? "" : o.toString();
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
