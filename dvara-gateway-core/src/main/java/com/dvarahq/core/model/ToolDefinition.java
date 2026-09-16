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
 * A function-calling tool definition the client asks the model to consider.
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
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {

    /** The function name the model calls. */
    private String name;

    /** Human-readable description that helps the model decide when to call it. */
    private String description;

    /** JSON-Schema object describing the function's arguments. */
    private Map<String, Object> parameters;
}