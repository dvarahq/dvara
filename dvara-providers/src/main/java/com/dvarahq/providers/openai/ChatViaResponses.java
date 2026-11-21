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
package com.dvarahq.providers.openai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.OpenAiResponsesBody;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.util.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A chat request sent to OpenAI's Responses API instead of Chat Completions.
 *
 * <p>OpenAI's Chat Completions refuses function tools together with a reasoning effort on its newest models
 * ("use /v1/responses or set reasoning_effort to 'none'"). A chat request with both, on a route to a provider that
 * has the Responses API, is written as a Responses API body here and sent there; the answer comes back as the
 * gateway's own response, which the chat doorway renders in the Chat Completions shape, whole or streamed.</p>
 *
 * <p>Fields the Responses API has no place for ({@code stop}, {@code seed}, the penalties) are refused rather than
 * dropped. Encrypted reasoning is not asked for: the chat shape has no way to hand it back to the client, so each
 * chat turn starts without the previous turn's reasoning.</p>
 */
final class ChatViaResponses {

    private ChatViaResponses() {
    }

    /** Whether this chat request goes to the Responses API: it has tools and asks for a reasoning effort. */
    static boolean needed(ChatRequest request) {
        return request.getOpenAiResponses() == null
                && request.getTools() != null && !request.getTools().isEmpty()
                && request.getReasoningEffort() != null && !"none".equals(request.getReasoningEffort());
    }

    /** The request as a Responses API body, kept for the provider to send as the gateway's own. */
    static OpenAiResponsesBody body(ChatRequest request) {
        refuseWhatHasNoPlace(request);
        ObjectNode body = JsonMapper.instance().createObjectNode();
        body.put("model", request.getModel());
        Set<String> custom = request.getTools().stream().filter(ToolDefinition::isCustom)
                .map(ToolDefinition::getName).collect(Collectors.toSet());
        ArrayNode input = body.putArray("input");
        for (MultimodalMessage m : request.getMessages()) {
            items(m, custom, input);
        }
        ArrayNode tools = body.putArray("tools");
        for (ToolDefinition t : request.getTools()) {
            tools.add(tool(t));
        }
        if (request.getToolChoice() != null) {
            body.set("tool_choice", toolChoice(request.getToolChoice()));
        }
        body.putObject("reasoning").put("effort", request.getReasoningEffort());
        if (request.getMaxTokens() != null) body.put("max_output_tokens", request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP() != null) body.put("top_p", request.getTopP());
        if (request.getParallelToolCalls() != null) body.put("parallel_tool_calls", request.getParallelToolCalls());
        if (request.getPromptCacheKey() != null) body.put("prompt_cache_key", request.getPromptCacheKey());
        if (request.getUser() != null) body.put("safety_identifier", request.getUser());
        ObjectNode text = textControls(request);
        if (text != null) body.set("text", text);
        body.put("store", false);
        return new OpenAiResponsesBody(body, request.getMessages(), List.of());
    }

    private static void refuseWhatHasNoPlace(ChatRequest request) {
        List<String> fields = new ArrayList<>();
        if (request.getStop() != null && !request.getStop().isEmpty()) fields.add("stop");
        if (request.getSeed() != null) fields.add("seed");
        if (request.getFrequencyPenalty() != null) fields.add("frequency_penalty");
        if (request.getPresencePenalty() != null) fields.add("presence_penalty");
        if (!fields.isEmpty()) {
            throw new GatewayException("UNSUPPORTED_CAPABILITY", "With tools and a reasoning effort this request "
                    + "goes to OpenAI's Responses API, which has no " + String.join(", ", fields)
                    + "; send the request without " + (fields.size() == 1 ? "it." : "them."));
        }
    }

    /** One chat message as the Responses API's input items. */
    private static void items(MultimodalMessage m, Set<String> custom, ArrayNode input) {
        String role = m.getRole();
        List<ContentBlock> content = m.getContent() == null ? List.of() : m.getContent();
        if ("tool".equals(role)) {
            ObjectNode out = input.addObject();
            out.put("type", "function_call_output");
            out.put("call_id", m.getToolCallId());
            boolean images = content.stream().anyMatch(b -> b instanceof ContentBlock.ImageBlock);
            if (images) {
                parts(content, out.putArray("output"), "input_text");
            } else {
                out.put("output", text(content));
            }
            return;
        }
        boolean assistant = "assistant".equals(role);
        if (!content.isEmpty() && content.stream().anyMatch(b -> !(b instanceof ContentBlock.TextBlock t) || !t.text().isEmpty())) {
            ObjectNode message = input.addObject();
            message.put("role", role);
            parts(content, message.putArray("content"), assistant ? "output_text" : "input_text");
        }
        if (m.getToolCalls() != null) {
            for (ToolCall call : m.getToolCalls()) {
                ObjectNode item = input.addObject();
                if (custom.contains(call.getName())) {
                    item.put("type", "custom_tool_call");
                    item.put("call_id", call.getId());
                    item.put("name", call.getName());
                    item.put("input", ToolDefinition.customInput(call.getArguments()));
                } else {
                    item.put("type", "function_call");
                    item.put("call_id", call.getId());
                    item.put("name", call.getName());
                    item.put("arguments", call.getArguments() == null ? "" : call.getArguments());
                }
            }
        }
    }

    private static void parts(List<ContentBlock> content, ArrayNode parts, String textType) {
        for (ContentBlock b : content) {
            if (b instanceof ContentBlock.TextBlock t) {
                parts.addObject().put("type", textType).put("text", t.text());
            } else if (b instanceof ContentBlock.ImageBlock image) {
                ObjectNode part = parts.addObject().put("type", "input_image")
                        .put("image_url", image.isUrl() ? image.data() : "data:" + image.mediaType() + ";base64," + image.data());
                if (image.detail() != null) part.put("detail", image.detail());
            }
            // Thinking blocks are Anthropic's own; a request carrying them is served by Anthropic only.
        }
    }

    private static String text(List<ContentBlock> content) {
        return content.stream().filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text()).collect(Collectors.joining("\n"));
    }

    private static ObjectNode tool(ToolDefinition t) {
        ObjectNode out = JsonMapper.instance().createObjectNode();
        out.put("type", t.isCustom() ? "custom" : "function");
        out.put("name", t.getName());
        if (t.getDescription() != null) out.put("description", t.getDescription());
        if (t.isCustom()) {
            if (t.getFormat() != null) out.set("format", JsonMapper.instance().valueToTree(t.getFormat()));
        } else {
            if (t.getParameters() != null) out.set("parameters", JsonMapper.instance().valueToTree(t.getParameters()));
            if (t.getStrict() != null) out.put("strict", t.getStrict());
        }
        return out;
    }

    /** {@code tool_choice} in the Responses shape: a named choice is flat, {@code {type, name}}. */
    private static com.fasterxml.jackson.databind.JsonNode toolChoice(Object choice) {
        if (choice instanceof Map<?, ?> m && m.get("type") != null) {
            String type = m.get("type").toString();
            if (m.get(type) instanceof Map<?, ?> named && named.get("name") != null) {
                return JsonMapper.instance().createObjectNode().put("type", type).put("name", named.get("name").toString());
            }
        }
        return JsonMapper.instance().valueToTree(choice);
    }

    private static ObjectNode textControls(ChatRequest request) {
        ObjectNode text = JsonMapper.instance().createObjectNode();
        if (request.getVerbosity() != null) text.put("verbosity", request.getVerbosity());
        ResponseFormat format = request.getResponseFormat();
        if (format instanceof ResponseFormat.JsonObject) {
            text.putObject("format").put("type", "json_object");
        } else if (format instanceof ResponseFormat.JsonSchema js) {
            ObjectNode f = text.putObject("format").put("type", "json_schema").put("name", js.name()).put("strict", js.strict());
            f.set("schema", JsonMapper.instance().valueToTree(js.schema()));
        }
        return text.isEmpty() ? null : text;
    }
}
