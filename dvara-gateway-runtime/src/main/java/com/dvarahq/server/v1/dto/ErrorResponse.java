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

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard error envelope returned by all endpoints on failure")
public class ErrorResponse {

    @Schema(description = "Error details")
    private ErrorDetail error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Error detail object")
    public static class ErrorDetail {
        @Schema(description = "Human-readable error message", example = "Model not found")
        private String message;
        @Schema(description = "Error type category", example = "invalid_request_error")
        private String type;
        @Schema(description = "Machine-readable error code", example = "NO_PROVIDER")
        private String code;
        @Schema(description = "Parameter that caused the error")
        private String param;
        @JsonProperty("trace_id")
        @Schema(description = "Request trace ID for debugging", example = "a1b2c3d4e5f6789012345678abcdef01")
        private String traceId;

        // ---- structured details ----
        // Extra fields an error carries beside the fixed ones, written into the object under their
        // own keys. What they are is decided by whatever raised the error, not here. Absent from
        // the JSON when empty, so every error carrying none is unchanged.
        @JsonIgnore
        private Map<String, Object> details;

        @JsonAnyGetter
        public Map<String, Object> anyDetails() {
            return details == null ? Map.of() : details;
        }

        @JsonAnySetter
        public void putDetail(String key, Object value) {
            if (details == null) {
                details = new LinkedHashMap<>();
            }
            details.put(key, value);
        }

        // ---- PII_REDACT_UNAVAILABLE structured action ----
        // The error code names REDACT for client compatibility, but only TOKENIZE can produce it:
        // REDACT stores nothing and needs no key, so it cannot be unavailable. This field names the
        // action that actually failed, so a client need not parse the message. NON_NULL keeps it
        // off every other error.
        @JsonProperty("attempted_action")
        @Schema(description = "The PII action that could not be honoured; only present on "
                + "PII_REDACT_UNAVAILABLE, where the error code itself names the pre-1.8 action",
                example = "TOKENIZE")
        private String attemptedAction;
    }

    public static ErrorResponse of(String message, String type, String code, String traceId) {
        return ErrorResponse.builder()
                .error(ErrorDetail.builder()
                        .message(message)
                        .type(type)
                        .code(code)
                        .traceId(traceId)
                        .build())
                .build();
    }
}