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

import com.dvarahq.core.cost.CostRecord;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.core.metering.TokenUsageRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cached-input, cache-write and reasoning tokens are breakdowns of the existing counts, not
 * additions to them, and every existing way of building a usage block still works.
 */
class UsageBreakdownTest {

    @Test
    @DisplayName("the three-count constructor still works and leaves the breakdown at zero")
    void threeCountConstructorStillWorks() {
        ChatResponse.Usage usage = new ChatResponse.Usage(10, 5, 15);
        assertThat(usage.getPromptTokens()).isEqualTo(10);
        assertThat(usage.getCompletionTokens()).isEqualTo(5);
        assertThat(usage.getTotalTokens()).isEqualTo(15);
        assertThat(usage.getCachedInputTokens()).isZero();
        assertThat(usage.getCacheWriteTokens()).isZero();
        assertThat(usage.getReasoningTokens()).isZero();
    }

    @Test
    @DisplayName("a builder that sets only the three counts leaves the breakdown at zero")
    void builderDefaultsTheBreakdownToZero() {
        ChatResponse.Usage usage = ChatResponse.Usage.builder()
                .promptTokens(1).completionTokens(2).totalTokens(3).build();
        assertThat(usage.getCachedInputTokens()).isZero();
        assertThat(usage.getCacheWriteTokens()).isZero();
        assertThat(usage.getReasoningTokens()).isZero();
    }

    @Test
    @DisplayName("a stored usage block without the new fields still reads, with zeros")
    void oldJsonStillReads() throws Exception {
        ChatResponse.Usage usage = JsonMapper.instance().readValue(
                "{\"promptTokens\":7,\"completionTokens\":3,\"totalTokens\":10}", ChatResponse.Usage.class);
        assertThat(usage.getTotalTokens()).isEqualTo(10);
        assertThat(usage.getCachedInputTokens()).isZero();
    }

    @Test
    @DisplayName("the breakdown round-trips through JSON")
    void breakdownRoundTrips() throws Exception {
        ChatResponse.Usage usage = ChatResponse.Usage.builder()
                .promptTokens(100).completionTokens(40).totalTokens(140)
                .cachedInputTokens(80).cacheWriteTokens(10).reasoningTokens(30).build();
        ChatResponse.Usage back = JsonMapper.instance().readValue(
                JsonMapper.instance().writeValueAsString(usage), ChatResponse.Usage.class);
        assertThat(back).isEqualTo(usage);
    }

    @Test
    @DisplayName("usage and cost rows carry the breakdown, and their old constructors still work")
    void recordsCarryTheBreakdown() {
        TokenUsageRecord usage = TokenUsageRecord.builder()
                .inputTokens(100).outputTokens(40).totalTokens(140)
                .cachedInputTokens(80).cacheWriteTokens(10).reasoningTokens(30).build();
        assertThat(usage.getCachedInputTokens()).isEqualTo(80);
        assertThat(usage.getCacheWriteTokens()).isEqualTo(10);
        assertThat(usage.getReasoningTokens()).isEqualTo(30);

        CostRecord cost = CostRecord.builder()
                .inputTokens(100).outputTokens(40)
                .cachedInputTokens(80).cacheWriteTokens(10).reasoningTokens(30).build();
        assertThat(cost.getCachedInputTokens()).isEqualTo(80);
        assertThat(cost.getCacheWriteTokens()).isEqualTo(10);
        assertThat(cost.getReasoningTokens()).isEqualTo(30);

        TokenUsageRecord legacyUsage = new TokenUsageRecord("id", "ws", "key", "m", "p", 1, 2, 3,
                false, null, "MISS", null);
        assertThat(legacyUsage.getTotalTokens()).isEqualTo(3);
        assertThat(legacyUsage.getCachedInputTokens()).isZero();

        CostRecord legacyCost = new CostRecord("id", "ws", "key", "m", "p", 1, 2, null, null, null,
                "USD", null, null, null);
        assertThat(legacyCost.getOutputTokens()).isEqualTo(2);
        assertThat(legacyCost.getReasoningTokens()).isZero();
    }
}
