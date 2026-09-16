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

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder(toBuilder = true)   // every rewrite copies through toBuilder(), so a new field survives them all
public class ChatRequest {

    private String model;
    private List<MultimodalMessage> messages;
    private boolean stream;
    private Integer maxTokens;
    private Double temperature;
    private Double topP;

    /**
     * OpenAI's {@code frequency_penalty} and {@code presence_penalty}, relayed to providers
     * that speak that wire shape and ignored by the rest, the same way {@code topP} is. Null
     * when the client did not set them.
     */
    private Double frequencyPenalty;
    private Double presencePenalty;

    /**
     * Sequences at which the model stops generating, relayed under each provider's own name
     * ({@code stop}, {@code stop_sequences}, {@code stopSequences}). Null when the client set none.
     */
    private List<String> stop;

    /**
     * A sampling seed for repeatable output, relayed to providers that accept one. A provider with no
     * seed parameter refuses the request rather than ignoring it. Null when the client set none.
     */
    private Long seed;
    private ResponseFormat responseFormat;
    private Map<String, Object> metadata;

    /**
     * Function-calling tool definitions relayed to the provider. The
     * gateway is a faithful pipe — it relays these to the model and relays the
     * model's {@code toolCalls} back to the client; it runs no agent loop and
     * executes no tool. Null/empty when the request carries no tools.
     */
    private List<ToolDefinition> tools;

    /**
     * The client's {@code tool_choice} directive, relayed verbatim. May be a
     * string ({@code "auto"}/{@code "none"}/{@code "required"}) or a
     * provider-native object selecting a specific tool. Kept as {@code Object}
     * so the gateway passes it through without imposing a shape.
     */
    private Object toolChoice;
}