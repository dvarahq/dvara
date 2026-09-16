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
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionRequest {

    @NotBlank(message = "model is required")
    private String model;

    @NotEmpty(message = "messages must not be empty")
    @Valid
    private List<Message> messages;

    private Double temperature;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("frequency_penalty")
    private Double frequencyPenalty;

    @JsonProperty("presence_penalty")
    private Double presencePenalty;

    private Boolean stream;

    private List<Object> tools;

    @JsonProperty("tool_choice")
    private Object toolChoice;

    @JsonProperty("response_format")
    private Map<String, Object> responseFormat;

    /** Only {@code 1} (or absent) is accepted; the gateway returns one choice. */
    private Integer n;

    /** Accepted and ignored: a caller-side tracking id. The gateway attributes by API key. */
    private String user;

    /** A string or an array of strings; relayed to the provider. */
    private Object stop;

    /** Relayed to providers that accept a seed; refused by those that do not. */
    private Long seed;

    /** Refused when true: the response carries no log probabilities. */
    private Boolean logprobs;

    /** Refused when set above zero, for the same reason as {@code logprobs}. */
    @JsonProperty("top_logprobs")
    private Integer topLogprobs;

    /** Refused when false: the gateway cannot ask a provider to keep tool calls sequential. */
    @JsonProperty("parallel_tool_calls")
    private Boolean parallelToolCalls;

    /** {@code include_usage} is honoured on a stream: a final chunk carries the usage block. */
    @JsonProperty("stream_options")
    private Map<String, Object> streamOptions;

    private Map<String, Object> metadata;
}