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
package com.dvarahq.core.guardrail;

import com.dvarahq.core.model.ChatRequest;

/**
 * Result of context window governance evaluation.
 *
 * @param estimatedTokens          estimated token count for the request
 * @param maxTokens                maximum context window tokens for the provider
 * @param utilizationPct           percentage of context window utilized
 * @param warningThresholdBreached whether the warning threshold was breached
 * @param hardThresholdBreached    whether the hard threshold was breached
 * @param prunedRequest            the pruned request if pruning was applied, null otherwise
 */
public record ContextWindowResult(
        int estimatedTokens,
        int maxTokens,
        int utilizationPct,
        boolean warningThresholdBreached,
        boolean hardThresholdBreached,
        ChatRequest prunedRequest) {

    public static ContextWindowResult withinLimits(int estimatedTokens, int maxTokens) {
        int pct = maxTokens > 0 ? (int) ((long) estimatedTokens * 100 / maxTokens) : 0;
        return new ContextWindowResult(estimatedTokens, maxTokens, pct, false, false, null);
    }
}