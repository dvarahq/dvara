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

import java.util.List;

/**
 * Result of checking whether an LLM response is grounded in source documents.
 *
 * @param grounded          true if all claims are supported by source documents
 * @param confidence        overall confidence score (0.0 to 1.0)
 * @param ungroundedClaims  list of response claims not supported by sources
 * @param overallSimilarity average max similarity across all claims
 */
public record GroundingResult(
        boolean grounded,
        double confidence,
        List<String> ungroundedClaims,
        double overallSimilarity
) {
    public static final GroundingResult GROUNDED = new GroundingResult(true, 1.0, List.of(), 1.0);
}