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
package com.dvarahq.server.v1.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request body for the OpenAI-compatible Responses API (<code>POST /v1/responses</code>).
 *
 * <p>DVARA translates this into the internal {@code ChatRequest} and runs the same governance
 * and metering pipeline as chat (via {@code ChatExecutionService}), so a Responses client gets a
 * governed answer from any provider. Only the text, image-input and structured-output core of the
 * Responses shape is honoured; function calling and the OpenAI-only features (server-side state,
 * hosted tools, reasoning, background mode) are rejected cleanly, never silently dropped.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseRequest {

    @NotBlank(message = "model is required")
    private String model;

    /** Plain string, or an array of input items ({@code {role, content}} / typed content parts). */
    private Object input;

    /** Prepended as a system message. */
    private String instructions;

    @JsonProperty("max_output_tokens")
    private Integer maxOutputTokens;

    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    private Boolean stream;

    /** {@code {"format": {"type": "text" | "json_object" | "json_schema", ...}}} */
    private Map<String, Object> text;

    private Map<String, Object> metadata;

    // -------- Detected and rejected (stateful / OpenAI-only / unsupported) --------
    // INVARIANT: every field below must have a matching check in
    // ResponsesController.rejectUnsupported(...). A field declared here without one is
    // accepted and silently dropped, the exact behaviour this endpoint promises to avoid.
    // Adding a field here means adding its check in the same change.
    @JsonProperty("previous_response_id")
    private String previousResponseId;
    private Boolean store;
    private Object reasoning;
    private Boolean background;
    private List<Object> tools;
    @JsonProperty("tool_choice")
    private Object toolChoice;
    private Object prompt;
    private List<String> include;
}