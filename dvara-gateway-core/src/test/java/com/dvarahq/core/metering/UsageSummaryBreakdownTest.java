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
package com.dvarahq.core.metering;

import com.dvarahq.core.util.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The usage summaries carry cached-input, cache-write and reasoning sums, and every existing way of
 * building or reading one still works with those sums at zero.
 */
class UsageSummaryBreakdownTest {

    @Test
    void theSummaryCarriesTheThreeSums() {
        TokenUsageSummary summary = TokenUsageSummary.builder()
                .totalInputTokens(100).totalCachedInputTokens(80).totalCacheWriteTokens(10)
                .totalOutputTokens(40).totalReasoningTokens(30).build();

        assertThat(summary.getTotalCachedInputTokens()).isEqualTo(80);
        assertThat(summary.getTotalCacheWriteTokens()).isEqualTo(10);
        assertThat(summary.getTotalReasoningTokens()).isEqualTo(30);
    }

    @Test
    void theOldSummaryConstructorStillWorksWithZeroSums() {
        TokenUsageSummary summary = new TokenUsageSummary("ws", "gpt-4o", 10, 5, 15, 2);

        assertThat(summary.getTotalTokens()).isEqualTo(15);
        assertThat(summary.getTotalCachedInputTokens()).isZero();
        assertThat(summary.getTotalCacheWriteTokens()).isZero();
        assertThat(summary.getTotalReasoningTokens()).isZero();
    }

    @Test
    void theBreakdownCarriesTheThreeSums() {
        UsageBreakdown breakdown = UsageBreakdown.builder().model("gpt-4o")
                .exactInputTokens(100).cachedInputTokens(80).cacheWriteTokens(10)
                .exactOutputTokens(40).reasoningTokens(30).build();

        assertThat(breakdown.getCachedInputTokens()).isEqualTo(80);
        assertThat(breakdown.getCacheWriteTokens()).isEqualTo(10);
        assertThat(breakdown.getReasoningTokens()).isEqualTo(30);
    }

    @Test
    void theOldBreakdownConstructorStillWorksWithZeroSums() {
        UsageBreakdown breakdown = new UsageBreakdown("gpt-4o", 1, 2, 3, 4, 5, 6, 7);

        assertThat(breakdown.getCacheHitCount()).isEqualTo(7);
        assertThat(breakdown.getCachedInputTokens()).isZero();
        assertThat(breakdown.getCacheWriteTokens()).isZero();
        assertThat(breakdown.getReasoningTokens()).isZero();
    }

    @Test
    void aSummaryWrittenWithoutTheSumsStillReads() throws Exception {
        TokenUsageSummary summary = JsonMapper.instance().readValue(
                "{\"workspaceId\":\"ws\",\"totalInputTokens\":7,\"totalTokens\":10,\"requestCount\":1}",
                TokenUsageSummary.class);

        assertThat(summary.getTotalInputTokens()).isEqualTo(7);
        assertThat(summary.getTotalReasoningTokens()).isZero();
    }
}
