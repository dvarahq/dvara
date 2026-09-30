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
package com.dvarahq.providers.support;

import com.dvarahq.core.model.ChatResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * The {@code usage} block every OpenAI-compatible upstream sends, with the parts some of them add.
 *
 * <p>{@code prompt_tokens_details.cached_tokens} and {@code completion_tokens_details.reasoning_tokens}
 * are OpenAI's; xAI, Qwen and Groq send the same. DeepSeek reports its cache hits as
 * {@code prompt_cache_hit_tokens} and Moonshot as a top-level {@code cached_tokens}. Every one of
 * them counts the cached tokens inside {@code prompt_tokens} and the reasoning inside
 * {@code completion_tokens}, so they map straight onto the breakdown and the totals stay as sent.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiUsage {
    @JsonProperty("prompt_tokens")           private int promptTokens;
    @JsonProperty("completion_tokens")       private int completionTokens;
    @JsonProperty("total_tokens")            private int totalTokens;
    @JsonProperty("prompt_tokens_details")   private PromptDetails promptTokensDetails;
    @JsonProperty("completion_tokens_details") private CompletionDetails completionTokensDetails;
    @JsonProperty("prompt_cache_hit_tokens") private int promptCacheHitTokens;
    @JsonProperty("cached_tokens")           private int cachedTokens;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptDetails {
        @JsonProperty("cached_tokens") private int cachedTokens;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CompletionDetails {
        @JsonProperty("reasoning_tokens") private int reasoningTokens;
    }

    /** The gateway's usage block: the three counts as sent, and the breakdown found in any of the shapes. */
    public ChatResponse.Usage toUsage() {
        int cached = Math.max(promptTokensDetails == null ? 0 : promptTokensDetails.getCachedTokens(),
                Math.max(promptCacheHitTokens, cachedTokens));
        int reasoning = completionTokensDetails == null ? 0 : completionTokensDetails.getReasoningTokens();
        return ChatResponse.Usage.builder()
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .cachedInputTokens(cached)
                .reasoningTokens(reasoning)
                .build();
    }
}
