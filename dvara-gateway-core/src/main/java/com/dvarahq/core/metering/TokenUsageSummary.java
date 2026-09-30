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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Aggregated token usage summary for a given filter criteria.
 *
 * <p>The cached-input, cache-write and reasoning sums are parts of the input and output totals,
 * never additions to them. A row stored before those counts were recorded adds zero to each, so for
 * a window that starts earlier the parts cover only the calls recorded since.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageSummary {

    private String workspaceId;
    private String model;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalTokens;
    private long requestCount;
    /** The part of {@code totalInputTokens} read from a provider's prompt cache. */
    private long totalCachedInputTokens;
    /** The part of {@code totalInputTokens} written to a provider's prompt cache. */
    private long totalCacheWriteTokens;
    /** The part of {@code totalOutputTokens} the model spent reasoning. */
    private long totalReasoningTokens;

    /** A summary with no cache or reasoning breakdown. */
    public TokenUsageSummary(String workspaceId, String model, long totalInputTokens, long totalOutputTokens,
                             long totalTokens, long requestCount) {
        this(workspaceId, model, totalInputTokens, totalOutputTokens, totalTokens, requestCount, 0, 0, 0);
    }
}