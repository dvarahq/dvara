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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatCompletionResponse {

    private String id;
    private String object;
    private long created;
    private String model;
    private List<Choice> choices;
    private Usage usage;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Choice {
        private int index;
        private Message message;
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    /**
     * OpenAI's usage block. The details objects appear only when the upstream reported a cached,
     * cache-write or reasoning count, so a response without them is byte-for-byte what it was. Each
     * detail is a part of the count it sits under, as in OpenAI's own responses.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Usage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("completion_tokens")
        private int completionTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
        @JsonProperty("prompt_tokens_details")
        private PromptTokensDetails promptTokensDetails;
        @JsonProperty("completion_tokens_details")
        private CompletionTokensDetails completionTokensDetails;

        /** The external block for an internal one; null for null. */
        public static Usage from(com.dvarahq.core.model.ChatResponse.Usage u) {
            if (u == null) {
                return null;
            }
            return Usage.builder()
                    .promptTokens(u.getPromptTokens())
                    .completionTokens(u.getCompletionTokens())
                    .totalTokens(u.getTotalTokens())
                    .promptTokensDetails(u.getCachedInputTokens() > 0 || u.getCacheWriteTokens() > 0
                            ? new PromptTokensDetails(positive(u.getCachedInputTokens()), positive(u.getCacheWriteTokens()))
                            : null)
                    .completionTokensDetails(u.getReasoningTokens() > 0
                            ? new CompletionTokensDetails(u.getReasoningTokens()) : null)
                    .build();
        }

        private static Integer positive(int n) {
            return n > 0 ? n : null;
        }
    }

    /**
     * {@code cached_tokens} is OpenAI's name. {@code cache_write_tokens} has no OpenAI equivalent:
     * it is what Anthropic and Bedrock report as written to their prompt cache.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PromptTokensDetails {
        @JsonProperty("cached_tokens")
        private Integer cachedTokens;
        @JsonProperty("cache_write_tokens")
        private Integer cacheWriteTokens;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CompletionTokensDetails {
        @JsonProperty("reasoning_tokens")
        private Integer reasoningTokens;
    }
}