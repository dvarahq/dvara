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

import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.OpenAiResponsesBody;
import com.dvarahq.core.model.OpenAiResponsesBody.Kind;
import com.dvarahq.core.model.OpenAiResponsesBody.Slot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each piece of text in the gateway's view of a Responses API request came from in the body, read the same
 * way {@link ResponsesController#toInternal} builds the view: instructions, then each input item in order, with
 * reasoning items left out and the calls of one turn joined on one assistant message.
 */
final class ResponsesSlots {

    private ResponsesSlots() {
    }

    static List<Slot> read(ObjectNode raw, List<MultimodalMessage> view) {
        List<Slot> slots = new ArrayList<>();
        Map<Integer, Integer> calls = new HashMap<>();
        int message = -1;
        if (raw.path("instructions").isTextual() && !raw.path("instructions").asText().isBlank()) {
            message++;
            slots.add(new Slot(message, Kind.TEXT, 0, "/instructions"));
        }
        JsonNode input = raw.path("input");
        if (input.isTextual()) {
            slots.add(new Slot(++message, Kind.TEXT, 0, "/input"));
            return slots;
        }
        for (int i = 0; i < input.size(); i++) {
            JsonNode item = input.get(i);
            String at = "/input/" + i;
            String type = item.path("type").asText("message");
            switch (type) {
                case "message" -> {
                    message++;
                    JsonNode content = item.path("content");
                    if (content.isTextual()) {
                        slots.add(new Slot(message, Kind.TEXT, 0, at + "/content"));
                    } else if (content.isArray()) {
                        parts(slots, message, content, at + "/content");
                    }
                }
                case "function_call", "custom_tool_call", "tool_search_call" -> {
                    boolean joins = message >= 0 && "assistant".equals(view.get(message).getRole());
                    if (!joins) {
                        message++;
                    }
                    int call = calls.merge(message, 1, Integer::sum) - 1;
                    boolean custom = "custom_tool_call".equals(type);
                    String field = custom ? "input" : "arguments";
                    if (item.path(field).isTextual()) {
                        slots.add(new Slot(message, custom ? Kind.CUSTOM_INPUT : Kind.ARGUMENTS, call, at + "/" + field));
                    }
                }
                case "tool_search_output" -> message++;   // its text is the tools it found, written by the gateway
                case "function_call_output", "custom_tool_call_output" -> {
                    message++;
                    JsonNode output = item.path("output");
                    if (output.isTextual()) {
                        slots.add(new Slot(message, Kind.TEXT, 0, at + "/output"));
                    } else if (output.isArray()) {
                        parts(slots, message, output, at + "/output");
                    }
                }
                default -> { }   // reasoning, additional_tools: not turns in the view, sent on as they came
            }
        }
        return slots;
    }

    /** Text parts are slots; an image part is a block of its own with no text to write back. */
    private static void parts(List<Slot> slots, int message, JsonNode parts, String at) {
        int block = 0;
        for (int j = 0; j < parts.size(); j++) {
            String type = parts.get(j).path("type").asText("input_text");
            if ("input_text".equals(type) || "text".equals(type) || "output_text".equals(type)) {
                if (parts.get(j).path("text").isTextual()) {
                    slots.add(new Slot(message, Kind.TEXT, block, at + "/" + j + "/text"));
                }
            }
            block++;
        }
    }

    /** The body to send on to an OpenAI route, or null when the view and the body do not line up. */
    static OpenAiResponsesBody body(ObjectNode raw, List<MultimodalMessage> view) {
        try {
            return new OpenAiResponsesBody(raw, view, read(raw, view));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
