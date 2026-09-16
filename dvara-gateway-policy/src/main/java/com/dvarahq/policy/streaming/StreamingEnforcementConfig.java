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
package com.dvarahq.policy.streaming;

import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.pii.PiiAction;

/**
 * Merged configuration for streaming PII, guardrail, and grounding enforcement,
 * resolved from global properties + per-workspace metadata overrides.
 *
 * <p>Invariants: {@code scanWindowSize >= 32}, {@code overlapMargin >= 16},
 * {@code overlapMargin < scanWindowSize} (ensures forward progress in buffer).</p>
 */
public record StreamingEnforcementConfig(
        boolean piiEnabled,
        PiiAction piiAction,
        boolean guardrailEnabled,
        GuardrailAction guardrailAction,
        double guardrailRiskScoreThreshold,
        int scanWindowSize,
        int overlapMargin,
        boolean groundingEnabled,
        GuardrailAction groundingAction,
        java.util.Map<String, String> customPiiPatterns,
        int maxHeldCharacters
) {
    /** Constructor without grounding fields. */
    public StreamingEnforcementConfig(boolean piiEnabled, PiiAction piiAction,
                                       boolean guardrailEnabled, GuardrailAction guardrailAction,
                                       double guardrailRiskScoreThreshold,
                                       int scanWindowSize, int overlapMargin) {
        this(piiEnabled, piiAction, guardrailEnabled, guardrailAction,
                guardrailRiskScoreThreshold, scanWindowSize, overlapMargin, false, GuardrailAction.LOG);
    }

    /** Constructor without custom PII patterns or a held-characters bound. */
    public StreamingEnforcementConfig(boolean piiEnabled, PiiAction piiAction,
                                       boolean guardrailEnabled, GuardrailAction guardrailAction,
                                       double guardrailRiskScoreThreshold,
                                       int scanWindowSize, int overlapMargin,
                                       boolean groundingEnabled, GuardrailAction groundingAction) {
        this(piiEnabled, piiAction, guardrailEnabled, guardrailAction, guardrailRiskScoreThreshold,
                scanWindowSize, overlapMargin, groundingEnabled, groundingAction, java.util.Map.of(),
                DEFAULT_MAX_HELD_CHARACTERS);
    }

    /**
     * Mirrors {@code PiiProperties.streamingMaxHeldCharacters} for constructors without it. The
     * guard holds the whole response when anything can withhold, and this is the memory bound on
     * that hold.
     */
    public static final int DEFAULT_MAX_HELD_CHARACTERS = 1_000_000;

    /** Constructor without a held-characters bound. */
    public StreamingEnforcementConfig(boolean piiEnabled, PiiAction piiAction,
                                       boolean guardrailEnabled, GuardrailAction guardrailAction,
                                       double guardrailRiskScoreThreshold,
                                       int scanWindowSize, int overlapMargin,
                                       boolean groundingEnabled, GuardrailAction groundingAction,
                                       java.util.Map<String, String> customPiiPatterns) {
        this(piiEnabled, piiAction, guardrailEnabled, guardrailAction, guardrailRiskScoreThreshold,
                scanWindowSize, overlapMargin, groundingEnabled, groundingAction, customPiiPatterns,
                DEFAULT_MAX_HELD_CHARACTERS);
    }

    /**
     * The posture the engine works from.
     *
     * <p>Resolved once, before the first chunk, and immutable for the life of the stream: a workspace
     * edit mid-response must not change the answer, or the same input could produce two.</p>
     */
    public com.dvarahq.core.enforcement.StreamingPosture toPosture(java.util.List<String> groundingSources) {
        return new com.dvarahq.core.enforcement.StreamingPosture(
                piiEnabled, piiAction, customPiiPatterns,
                guardrailEnabled, guardrailAction, guardrailRiskScoreThreshold,
                groundingEnabled, groundingAction, groundingSources);
    }

    public StreamingEnforcementConfig {
        // A workspace's own PII patterns apply to the streaming scan as they do to non-streaming
        // requests and responses; null means none.
        customPiiPatterns = customPiiPatterns == null
                ? java.util.Map.of() : java.util.Map.copyOf(customPiiPatterns);
        if (maxHeldCharacters < 1) {
            maxHeldCharacters = DEFAULT_MAX_HELD_CHARACTERS;
        }
        if (scanWindowSize < 32) {
            throw new IllegalArgumentException("scanWindowSize must be >= 32, got " + scanWindowSize);
        }
        if (overlapMargin < 0) {
            throw new IllegalArgumentException("overlapMargin must be >= 0, got " + overlapMargin);
        }
        if (overlapMargin >= scanWindowSize) {
            throw new IllegalArgumentException("overlapMargin (" + overlapMargin +
                    ") must be < scanWindowSize (" + scanWindowSize + ")");
        }
    }
}