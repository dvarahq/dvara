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
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmbeddingRequest {

    @NotBlank(message = "model is required")
    private String model;

    /** String, array of strings, or array of token arrays. */
    @NotNull(message = "input is required")
    private Object input;

    private String user;

    /** How many dimensions to truncate the vectors to; relayed upstream. Null when not asked for. */
    private Integer dimensions;

    /**
     * {@code float} (the default) or {@code base64}. Declared so the value is SEEN rather than
     * discarded as an unknown property — {@code base64} is refused, because this gateway's response
     * shape is an array of numbers and could not carry a string.
     */
    @JsonProperty("encoding_format")
    private String encodingFormat;
}