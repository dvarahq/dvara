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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Non-streaming response body for the Responses API — the internal {@code ChatResponse}
 * mapped to the Responses shape ({@code output[]} of message items, {@code usage} with
 * {@code input_tokens}/{@code output_tokens}).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseResult {

    private String id;
    private String object; // "response"

    @JsonProperty("created_at")
    private long createdAt;

    private String model;
    private String status; // "completed"
    private List<OutputItem> output;
    private Usage usage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OutputItem {
        private String type;   // "message"
        private String id;
        private String status; // "completed"
        private String role;   // "assistant"
        private List<ContentPart> content;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ContentPart {
        private String type; // "output_text"
        private String text;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {
        @JsonProperty("input_tokens")
        private int inputTokens;
        @JsonProperty("output_tokens")
        private int outputTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
        /** Present only when the upstream reported cached input; a part of {@code input_tokens}. */
        @JsonProperty("input_tokens_details")
        private InputTokensDetails inputTokensDetails;
        /** Present only when the upstream reported reasoning; a part of {@code output_tokens}. */
        @JsonProperty("output_tokens_details")
        private OutputTokensDetails outputTokensDetails;

        /** The Responses-shaped block for an internal one; null for null. */
        public static Usage from(com.dvarahq.core.model.ChatResponse.Usage u) {
            if (u == null) {
                return null;
            }
            return Usage.builder()
                    .inputTokens(u.getPromptTokens())
                    .outputTokens(u.getCompletionTokens())
                    .totalTokens(u.getTotalTokens())
                    .inputTokensDetails(u.getCachedInputTokens() > 0
                            ? new InputTokensDetails(u.getCachedInputTokens()) : null)
                    .outputTokensDetails(u.getReasoningTokens() > 0
                            ? new OutputTokensDetails(u.getReasoningTokens()) : null)
                    .build();
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InputTokensDetails {
        @JsonProperty("cached_tokens")
        private int cachedTokens;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OutputTokensDetails {
        @JsonProperty("reasoning_tokens")
        private int reasoningTokens;
    }
}