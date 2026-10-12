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
import com.dvarahq.core.model.OpenAiResponsesBody;
import com.dvarahq.server.v1.dto.ChatCompletionRequest;
import com.dvarahq.server.v1.dto.ResponseRequest;
import com.dvarahq.server.web.AccessLogFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * The base URL for Cursor: {@code https://<gateway>/v1/cursor}, set as Cursor's "Override OpenAI Base URL".
 *
 * <p>Cursor sends {@code POST {base}/chat/completions} and always reads a Chat Completions answer back. In chat
 * mode the body is a Chat Completions body. In agent mode it is a Responses API body ({@code input},
 * {@code instructions}, flat tool definitions, {@code reasoning}) sent to the same path, sometimes with an empty
 * or null {@code messages} beside it. Either way the request runs the same checks, metering and audit as on
 * {@code /v1/chat/completions}:</p>
 *
 * <ul>
 *   <li>A body with a non-empty {@code messages} array is a Chat Completions body. Its tools are put in the Chat
 *       Completions shape first, since Cursor mixes the two APIs' tool shapes level by level: a flat custom or
 *       function tool is nested, and a flat grammar is wrapped as {@code grammar: {syntax, definition}}.</li>
 *   <li>Any other body is a Responses API body. Its tools are put in the Responses shape, the empty
 *       {@code messages} and the Chat Completions-only {@code stream_options} are taken out of what is sent on,
 *       and it is read as {@code /v1/responses} reads a body: OpenAI and Azure OpenAI are sent it as it came,
 *       any other provider the translation. The answer is in the Chat Completions shape;
 *       {@code stream_options.include_usage} still asks for the usage chunk.</li>
 * </ul>
 *
 * <p>{@code GET {base}/models} is {@code /v1/models}, which Cursor calls to check a key. What this doorway
 * cannot take is refused with a JSON error, as on the other doorways; nothing is left out without a word.
 * The request shapes follow public descriptions of Cursor's traffic.</p>
 */
@RestController
@RequestMapping("/v1/cursor")
@Tag(name = "Cursor", description = "Base URL for Cursor's OpenAI override")
public class CursorController {

    private final ChatCompletionController chat;
    private final ModelsController models;

    public CursorController(ChatCompletionController chat, ModelsController models) {
        this.chat = chat;
        this.models = models;
    }

    @PostMapping("/chat/completions")
    @Operation(summary = "Chat completion for Cursor",
            description = "Takes a Chat Completions body or a Responses API body and answers in the Chat Completions shape.")
    public Object chatCompletions(@RequestBody(required = false) byte[] rawBody,
                                  HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        ObjectNode raw = OpenAiResponsesBody.parse(rawBody);
        modelVariant(raw);
        if (isChatBody(raw)) {
            ChatCompletionRequest request = OpenAiResponsesBody.bind(toolsAs(raw, true), ChatCompletionRequest.class);
            if (request.getModel() == null || request.getModel().isBlank()) {
                throw new GatewayException("INVALID_REQUEST", "model is required");
            }
            return chat.chatCompletions(request, httpRequest, httpResponse);
        }
        return responsesShaped(raw, httpRequest, httpResponse);
    }

    private Object responsesShaped(ObjectNode body, HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        ObjectNode raw = toolsAs(body, false);
        JsonNode streamOptions = raw.remove("stream_options");
        raw.remove("messages");   // the stub Cursor sends beside its input; no part of a Responses API body
        boolean includeUsage = streamOptions != null && streamOptions.path("include_usage").asBoolean(false);
        ResponseRequest request = OpenAiResponsesBody.bind(raw, ResponseRequest.class);
        if (request.getModel() == null || request.getModel().isBlank()) {
            throw new GatewayException("INVALID_REQUEST", "model is required");
        }
        boolean stream = Boolean.TRUE.equals(request.getStream());
        httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, request.getModel());
        httpRequest.setAttribute(AccessLogFilter.ATTR_STREAM, String.valueOf(stream));
        ChatRequest internal = ResponsesController.governable(raw, request);
        return chat.serve(internal, stream, includeUsage, httpRequest, httpResponse);
    }

    /** The request as the doorway reads it, for counting its tokens before it is admitted. */
    static ChatRequest governanceView(byte[] body) {
        ObjectNode raw = OpenAiResponsesBody.parse(body);
        modelVariant(raw);
        if (isChatBody(raw)) {
            return ChatCompletionController.toInternal(OpenAiResponsesBody.bind(toolsAs(raw, true), ChatCompletionRequest.class));
        }
        ObjectNode responses = toolsAs(raw, false);
        responses.remove("stream_options");
        responses.remove("messages");
        return ResponsesController.toInternal(OpenAiResponsesBody.bind(responses, ResponseRequest.class));
    }

    @GetMapping("/models")
    @Operation(summary = "List models for Cursor", description = "The same list as /v1/models.")
    public ResponseEntity<?> models(HttpServletRequest httpRequest) {
        return models.models(httpRequest);
    }

    private static final java.util.regex.Pattern THINKING =
            java.util.regex.Pattern.compile("^(.+)-thinking-(minimal|low|medium|high|xhigh)$");

    /**
     * Cursor's model-picker variants, read off the model name: a trailing {@code -fast} is taken off, and a
     * trailing {@code -thinking-<effort>} becomes the request's reasoning effort ({@code reasoning_effort} on a
     * Chat Completions body, {@code reasoning.effort} on a Responses API body) and is taken off too. The variant
     * is what the developer picked, so it wins over an effort in the body. A bare {@code -thinking} stays part of
     * the name, for a route of that name. Only this doorway reads model names this way.
     */
    static void modelVariant(ObjectNode body) {
        if (!(body.get("model") instanceof com.fasterxml.jackson.databind.node.TextNode t)) {
            return;
        }
        String model = t.asText();
        if (model.endsWith("-fast") && model.length() > "-fast".length()) {
            model = model.substring(0, model.length() - "-fast".length());
        }
        java.util.regex.Matcher m = THINKING.matcher(model);
        if (m.matches()) {
            model = m.group(1);
            String effort = m.group(2);
            if (isChatBody(body)) {
                body.put("reasoning_effort", effort);
            } else {
                ObjectNode reasoning = body.get("reasoning") instanceof ObjectNode r ? r : body.putObject("reasoning");
                reasoning.put("effort", effort);
            }
        }
        body.put("model", model);
    }

    /** A Chat Completions body has messages; Cursor can send an empty or null stub beside a Responses API input. */
    static boolean isChatBody(ObjectNode body) {
        JsonNode messages = body.get("messages");
        if (messages instanceof ArrayNode a && !a.isEmpty()) {
            return true;
        }
        return !body.has("input") && !body.has("instructions") && body.has("messages");
    }

    private static final Set<String> FUNCTION_FIELDS = Set.of("name", "description", "parameters", "strict");
    private static final Set<String> CUSTOM_FIELDS = Set.of("name", "description", "format");

    /**
     * The body with its tools and {@code tool_choice} in one API's shape: nested ({@code {type, function: {...}}},
     * {@code {type, custom: {...}}}) for Chat Completions, flat ({@code {type, name, ...}}) for the Responses API,
     * with a custom tool's grammar in the same API's shape. An entry of any other type is left as it is, for the
     * doorway to accept or refuse.
     */
    static ObjectNode toolsAs(ObjectNode body, boolean chat) {
        ObjectNode out = body.deepCopy();
        if (out.get("tools") instanceof ArrayNode tools) {
            for (int i = 0; i < tools.size(); i++) {
                tools.set(i, envelope(tools.get(i), chat));
            }
        }
        if (out.has("tool_choice")) {
            out.set("tool_choice", envelope(out.get("tool_choice"), chat));
        }
        return out;
    }

    private static JsonNode envelope(JsonNode node, boolean chat) {
        if (!(node instanceof ObjectNode tool)) {
            return node;
        }
        String type = tool.path("type").asText("");
        Set<String> fields = "function".equals(type) ? FUNCTION_FIELDS : "custom".equals(type) ? CUSTOM_FIELDS : null;
        if (fields == null) {
            return node;
        }
        JsonNode nested = tool.get(type);
        ObjectNode payload = tool.objectNode();
        for (String field : List.of("name", "description", "parameters", "strict", "format")) {
            if (!fields.contains(field)) continue;
            JsonNode value = nested instanceof ObjectNode n && n.has(field) ? n.get(field) : tool.get(field);
            if (value != null) {
                payload.set(field, "format".equals(field) ? grammar(value, chat) : value);
            }
        }
        if (!payload.has("name")) {
            return node;
        }
        ObjectNode out = tool.objectNode().put("type", type);
        if (chat) {
            out.set(type, payload);
        } else {
            out.setAll(payload);
        }
        return out;
    }

    /** A grammar format as {@code {type: grammar, grammar: {syntax, definition}}} for chat, flat for the Responses API. */
    private static JsonNode grammar(JsonNode format, boolean chat) {
        if (!(format instanceof ObjectNode f) || !"grammar".equals(f.path("type").asText())) {
            return format;
        }
        JsonNode inner = f.get("grammar") instanceof ObjectNode g ? g : f;
        ObjectNode spec = f.objectNode();
        if (inner.has("syntax")) spec.set("syntax", inner.get("syntax"));
        if (inner.has("definition")) spec.set("definition", inner.get("definition"));
        ObjectNode out = f.objectNode().put("type", "grammar");
        if (chat) {
            out.set("grammar", spec);
        } else {
            out.setAll(spec);
        }
        return out;
    }
}
