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
 * Per-model usage breakdown for a time window. Splits tokens three ways so a usage report can say
 * what was measured:
 *
 * <ul>
 *   <li><b>exact</b>: provider-reported tokens from non-streaming, non-cache requests.</li>
 *   <li><b>estimated</b>: streaming requests where the provider returned no usage block, so tokens
 *       come from {@code TokenEstimator} ({@code estimated=true}).</li>
 *   <li><b>cacheHit</b>: requests served from the semantic cache ({@code cache_status='HIT'}),
 *       which cost nothing upstream.</li>
 * </ul>
 *
 * The three buckets partition every row: a row is exactly one of cache-hit, estimated, or exact.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageBreakdown {

    private String model;
    private long exactInputTokens;
    private long exactOutputTokens;
    private long estimatedInputTokens;
    private long estimatedOutputTokens;
    private long cacheHitTokens;
    private long requestCount;
    private long cacheHitCount;
}