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
 * Estimates token counts for chat requests and text.
 *
 * <p>The policy module provides a BPE tokenizer; the auto-configuration's character-based
 * approximation is the stand-in for a build that leaves the policy module out.</p>
 */
public interface TokenEstimator {

    int estimateTokens(ChatRequest request);

    int estimateTokens(String text);

    /**
     * A number never smaller than {@link #estimateTokens(ChatRequest)}, for a caller that only needs to know that a
     * request is well inside a limit. Counting is the expensive part of a large request, so an estimator that can
     * bound its count without counting overrides this; the default is the count itself.
     */
    default int upperBound(ChatRequest request) {
        return estimateTokens(request);
    }

    /**
     * Where the request's tokens go, part by part, adding up to {@link #estimateTokens(ChatRequest)}. Empty from an
     * estimator that only counts a total, such as the character-based stand-in.
     */
    default java.util.Optional<TokenBreakdown> breakdown(ChatRequest request) {
        return java.util.Optional.empty();
    }
}