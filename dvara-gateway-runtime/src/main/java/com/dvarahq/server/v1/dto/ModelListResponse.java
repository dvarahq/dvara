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

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelListResponse {

    private String object;
    private List<ModelData> data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelData {
        private String id;
        private String object;
        private long created;
        @JsonProperty("owned_by")
        private String ownedBy;
        private CapabilitiesDto capabilities;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CapabilitiesDto {
        @JsonProperty("supports_streaming")
        private boolean supportsStreaming;
        @JsonProperty("supports_vision")
        private boolean supportsVision;
        @JsonProperty("supports_tool_calls")
        private boolean supportsToolCalls;
        @JsonProperty("supports_structured_outputs")
        private boolean supportsStructuredOutputs;
        @JsonProperty("supports_json_mode")
        private boolean supportsJsonMode;
        @JsonProperty("max_context_tokens")
        private int maxContextTokens;
    }
}