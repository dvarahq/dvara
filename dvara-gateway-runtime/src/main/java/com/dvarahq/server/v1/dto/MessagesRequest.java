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

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The body of an Anthropic Messages API request ({@code POST /v1/messages}).
 *
 * <p>Content arrives as the loosely typed maps the Messages API allows — a string or a list of typed
 * blocks — and is translated by {@code AnthropicMessages}. A top-level field this class does not name is
 * collected in {@link #getOther()}: an Anthropic provider is sent it unchanged, and any other provider
 * leaves it out. {@code max_tokens} is required here and checked by the controller, because a token count
 * ({@code /v1/messages/count_tokens}) takes the same body without it.</p>
 */
@Data
@NoArgsConstructor
public class MessagesRequest {

    @NotBlank(message = "model is required")
    private String model;

    @NotEmpty(message = "messages is required")
    private List<Map<String, Object>> messages;

    /** A string, or a list of text blocks. */
    private Object system;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    private Boolean stream;

    private Double temperature;

    @JsonProperty("top_p")
    private Double topP;

    @JsonProperty("stop_sequences")
    private List<String> stopSequences;

    private List<Map<String, Object>> tools;

    @JsonProperty("tool_choice")
    private Map<String, Object> toolChoice;

    private Map<String, Object> metadata;

    /** Extended thinking: sent on to an Anthropic provider, refused on any other. */
    private Map<String, Object> thinking;

    /** Every other top-level field, by name, in the order it came. */
    @JsonIgnore
    private final Map<String, Object> other = new LinkedHashMap<>();

    @JsonAnySetter
    public void other(String name, Object value) {
        other.put(name, value);
    }
}
