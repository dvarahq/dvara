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
import com.dvarahq.core.model.GeminiGenerateContentBody;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.util.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Gemini's {@code generateContent} API on the gateway's side: the request read for governance, and the
 * response in Gemini's shape when it has to be rebuilt.
 *
 * <p>The request body itself is kept as it came ({@link GeminiGenerateContentBody}). Its text is read out as
 * the gateway's messages, so the same policy, PII and guardrail checks as every other doorway apply, and a
 * Gemini provider sends the body on with only the governed edits. Any other provider is sent those messages,
 * with:</p>
 *
 * <ul>
 *   <li>{@code systemInstruction} as a system message, never user content;</li>
 *   <li>each {@code functionDeclarations} entry as a tool, its {@code parametersJsonSchema} (or
 *       {@code parameters}, with Gemini's upper-case type names lowered) as the tool's parameters;</li>
 *   <li>{@code toolConfig.functionCallingConfig} as the tool choice: {@code AUTO} auto, {@code ANY} required
 *       (or the one function {@code allowedFunctionNames} names), {@code NONE} none;</li>
 *   <li>{@code generationConfig}'s {@code maxOutputTokens}, {@code temperature}, {@code topP},
 *       {@code stopSequences}, {@code seed} and JSON response settings as the gateway's own.</li>
 * </ul>
 *
 * <p>What only tunes how Gemini serves the call ({@code thinkingConfig}, {@code safetySettings}, thought parts
 * in the history) is left out for another provider. What the gateway cannot govern is refused here on every
 * provider, Gemini included, with {@code UNSUPPORTED_CAPABILITY}:</p>
 *
 * <ul>
 *   <li>a built-in tool Google runs itself ({@code googleSearch}, {@code urlContext}, {@code codeExecution},
 *       {@code googleSearchRetrieval} and the like): what it fetches or runs never passes the gateway's
 *       checks;</li>
 *   <li>{@code cachedContent}, which is content stored at Google that the gateway never sees;</li>
 *   <li>more than one candidate, since only the first is read.</li>
 * </ul>
 */
public final class GeminiGenerateContent {

    /** The doorway's base: a Google GenAI client's base URL is the gateway's address followed by {@code /v1/gemini}. */
    static final String BASE = "/v1/gemini";
    /** Where the methods of a model are, under the API version the Google GenAI SDKs use. */
    static final String MODELS = BASE + "/v1beta/models/";

    private GeminiGenerateContent() {
    }

    /** Whether {@code uri} asks a model to generate: {@code :generateContent} or {@code :streamGenerateContent}. */
    public static boolean generates(String uri) {
        return uri != null && uri.startsWith(MODELS)
                && (uri.endsWith(":generateContent") || uri.endsWith(":streamGenerateContent"));
    }

    /** The model named in a method path: {@code gemini-2.5-pro} in {@code .../models/gemini-2.5-pro:generateContent}. */
    static String modelOf(String uri) {
        String target = uri.substring(MODELS.length());
        int colon = target.lastIndexOf(':');
        return colon < 0 ? target : target.substring(0, colon);
    }

    // -------------------------------------------------------------------------
    // Request
    // -------------------------------------------------------------------------

    static ChatRequest toInternal(String model, GeminiGenerateContentBody body, boolean stream) {
        if (model == null || model.isBlank()) {
            throw new GatewayException("INVALID_REQUEST", "The model is named in the path: models/{model}:generateContent");
        }
        ObjectNode raw = body.raw();
        rejectUngovernable(body, raw);
        JsonNode config = raw.path("generationConfig");
        return ChatRequest.builder()
                .model(model)
                .messages(body.messages())
                .stream(stream)
                .maxTokens(config.hasNonNull("maxOutputTokens") ? config.get("maxOutputTokens").asInt() : null)
                .temperature(config.hasNonNull("temperature") ? config.get("temperature").asDouble() : null)
                .topP(config.hasNonNull("topP") ? config.get("topP").asDouble() : null)
                .stop(strings(config.get("stopSequences")))
                .seed(config.hasNonNull("seed") ? config.get("seed").asLong() : null)
                .responseFormat(responseFormat(config))
                .tools(tools(raw.path("tools")))
                .toolChoice(toolChoice(raw.path("toolConfig").path("functionCallingConfig")))
                .gemini(body)
                .build();
    }

    /** Gemini CLI's own tools that ask Google for a built-in tool, and the setting that turns them off. */
    static final String CLI_EXCLUDE = "In Gemini CLI, deny its web search and web fetch tools with a policy rule "
            + "in ~/.gemini/policies/<name>.toml: [[rule]] toolName = [\"google_web_search\", \"web_fetch\"] "
            + "decision = \"deny\" (older versions: \"tools\": {\"exclude\": [\"google_web_search\", "
            + "\"web_fetch\"]} in settings.json).";

    private static void rejectUngovernable(GeminiGenerateContentBody body, ObjectNode raw) {
        if (!body.googleTools().isEmpty()) {
            throw unsupported("Built-in tools that Google runs itself are not supported on this gateway, because "
                    + "what they fetch or run is never checked: " + String.join(", ", body.googleTools())
                    + ". Send function declarations only. " + CLI_EXCLUDE);
        }
        if (raw.hasNonNull("cachedContent")) {
            throw unsupported("cachedContent is not supported on this gateway: it names content stored at Google, "
                    + "which the gateway never sees and so cannot check.");
        }
        JsonNode candidates = raw.path("generationConfig").path("candidateCount");
        if (candidates.isNumber() && candidates.asInt() > 1) {
            throw unsupported("generationConfig.candidateCount above 1 is not supported on this gateway; "
                    + "ask for one candidate.");
        }
    }

    private static List<String> strings(JsonNode list) {
        if (list == null || !list.isArray() || list.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        list.forEach(n -> out.add(n.asText()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static ResponseFormat responseFormat(JsonNode config) {
        if (!"application/json".equals(config.path("responseMimeType").asText(null))) {
            return null;
        }
        JsonNode schema = config.hasNonNull("responseJsonSchema") ? config.get("responseJsonSchema")
                : config.hasNonNull("responseSchema") ? lowerTypes(config.get("responseSchema").deepCopy()) : null;
        if (schema == null || !schema.isObject()) {
            return new ResponseFormat.JsonObject();
        }
        return new ResponseFormat.JsonSchema("response", JsonMapper.instance().convertValue(schema, Map.class), false);
    }

    @SuppressWarnings("unchecked")
    private static List<ToolDefinition> tools(JsonNode tools) {
        List<ToolDefinition> out = new ArrayList<>();
        for (JsonNode tool : tools) {
            for (JsonNode fd : tool.path("functionDeclarations")) {
                String name = fd.path("name").asText("");
                if (name.isEmpty()) {
                    throw new GatewayException("INVALID_REQUEST", "every function declaration needs a name");
                }
                JsonNode schema = fd.hasNonNull("parametersJsonSchema") ? fd.get("parametersJsonSchema")
                        : fd.hasNonNull("parameters") ? lowerTypes(fd.get("parameters").deepCopy())
                        : JsonMapper.instance().createObjectNode().put("type", "object");
                out.add(ToolDefinition.builder()
                        .name(name)
                        .description(fd.hasNonNull("description") ? fd.get("description").asText() : null)
                        .parameters(JsonMapper.instance().convertValue(schema, Map.class))
                        .build());
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** Gemini's schema names its types in upper case ({@code OBJECT}); JSON Schema in lower case. */
    private static JsonNode lowerTypes(JsonNode node) {
        if (node instanceof ObjectNode object) {
            JsonNode type = object.get("type");
            if (type != null && type.isTextual()) {
                object.put("type", type.asText().toLowerCase(Locale.ROOT));
            }
            Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
            while (fields.hasNext()) {
                lowerTypes(fields.next().getValue());
            }
        } else if (node != null && node.isArray()) {
            node.forEach(GeminiGenerateContent::lowerTypes);
        }
        return node;
    }

    /** Gemini's {@code functionCallingConfig} in the OpenAI form the gateway's request carries. */
    private static Object toolChoice(JsonNode config) {
        if (config == null || config.isMissingNode() || !config.hasNonNull("mode")) {
            return null;
        }
        return switch (config.get("mode").asText().toUpperCase(Locale.ROOT)) {
            case "ANY" -> {
                JsonNode allowed = config.path("allowedFunctionNames");
                yield allowed.isArray() && allowed.size() == 1
                        ? Map.of("type", "function", "function", Map.of("name", allowed.get(0).asText()))
                        : "required";
            }
            case "NONE" -> "none";
            default -> "auto";
        };
    }

    // -------------------------------------------------------------------------
    // Response
    // -------------------------------------------------------------------------

    /** A whole response in Gemini's {@code GenerateContentResponse} shape. */
    static Map<String, Object> toResponse(ChatResponse resp, String requestedModel) {
        return toResponse(resp, requestedModel, null);
    }

    /**
     * As {@link #toResponse(ChatResponse, String)}, keeping the parts of Gemini's own response that the gateway
     * does not read ({@code unread}, may be null) after the governed content.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> toResponse(ChatResponse resp, String requestedModel, JsonNode unread) {
        List<Map<String, Object>> parts = new ArrayList<>();
        String finishReason = null;
        if (resp.getChoices() != null && !resp.getChoices().isEmpty()) {
            ChatResponse.Choice choice = resp.getChoices().get(0);
            finishReason = choice.getFinishReason();
            MultimodalMessage msg = choice.getMessage();
            if (msg != null && msg.getContent() != null) {
                for (ContentBlock b : msg.getContent()) {
                    if (b instanceof ContentBlock.ThinkingBlock t && t.thinking() != null && !t.thinking().isEmpty()) {
                        parts.add(thought(t.thinking(), t.signature()));
                    } else if (b instanceof ContentBlock.TextBlock t && t.text() != null && !t.text().isEmpty()) {
                        parts.add(map("text", t.text()));
                    }
                }
            }
            if (msg != null && msg.getToolCalls() != null) {
                for (ToolCall call : msg.getToolCalls()) {
                    parts.add(functionCall(call.getId(), call.getName(), call.getArguments()));
                }
            }
        }
        if (unread != null) {
            unread.forEach(part -> parts.add(JsonMapper.instance().convertValue(part, Map.class)));
        }
        String model = resp.getModel() != null ? resp.getModel() : requestedModel;
        Map<String, Object> out = candidate(parts, finishReason(finishReason), resp.getUsage(), model);
        if (resp.getId() != null) {
            out.put("responseId", resp.getId());
        }
        return out;
    }

    /** One response, or one streamed chunk: a single candidate with these parts, and usage when known. */
    static Map<String, Object> candidate(List<Map<String, Object>> parts, String finishReason,
                                         ChatResponse.Usage usage, String model) {
        Map<String, Object> candidate = map("content", map("role", "model", "parts", parts), "index", 0);
        if (finishReason != null) {
            candidate.put("finishReason", finishReason);
        }
        Map<String, Object> out = map("candidates", List.of(candidate));
        if (usage != null) {
            out.put("usageMetadata", usageMetadata(usage));
        }
        out.put("modelVersion", model);
        return out;
    }

    static Map<String, Object> thought(String text, String signature) {
        Map<String, Object> part = map("text", text, "thought", true);
        if (signature != null && !signature.isEmpty()) {
            part.put("thoughtSignature", signature);
        }
        return part;
    }

    /** A function call part, its JSON arguments as the object Gemini's {@code args} is. */
    static Map<String, Object> functionCall(String id, String name, String arguments) {
        Map<String, Object> call = map("name", name, "args", args(arguments));
        if (id != null && !id.isEmpty()) {
            call.put("id", id);
        }
        return map("functionCall", call);
    }

    private static Object args(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        try {
            return JsonMapper.instance().readValue(arguments, Object.class);
        } catch (Exception e) {
            throw new GatewayException("PROVIDER_ERROR",
                    "The model returned function call arguments that are not valid JSON");
        }
    }

    /**
     * The gateway's finish reason as Gemini's. A call to a function ends with {@code STOP}, as Gemini says it;
     * a reason a Gemini provider passed through (lower-cased) goes back in Gemini's case.
     */
    static String finishReason(String finishReason) {
        if (finishReason == null) {
            return "STOP";
        }
        return switch (finishReason) {
            case "stop", "end_turn", "tool_calls", "tool_use", "function_call", "stop_sequence" -> "STOP";
            case "length", "max_tokens" -> "MAX_TOKENS";
            case "content_filter", "refusal" -> "SAFETY";
            default -> finishReason.toUpperCase(Locale.ROOT);
        };
    }

    /**
     * Usage in Gemini's terms: {@code promptTokenCount} includes cached content, and thinking tokens are
     * reported apart from {@code candidatesTokenCount}, which the gateway's completion count includes.
     */
    static Map<String, Object> usageMetadata(ChatResponse.Usage u) {
        int thoughts = u.getReasoningTokens();
        Map<String, Object> out = map("promptTokenCount", u.getPromptTokens(),
                "candidatesTokenCount", Math.max(0, u.getCompletionTokens() - thoughts),
                "totalTokenCount", u.getTotalTokens() > 0 ? u.getTotalTokens() : u.getPromptTokens() + u.getCompletionTokens());
        if (u.getCachedInputTokens() > 0) {
            out.put("cachedContentTokenCount", u.getCachedInputTokens());
        }
        if (thoughts > 0) {
            out.put("thoughtsTokenCount", thoughts);
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
}
