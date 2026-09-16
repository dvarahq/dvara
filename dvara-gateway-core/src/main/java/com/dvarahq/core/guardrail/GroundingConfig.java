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

/**
 * Configuration for grounding/hallucination detection.
 *
 * @param enabled         whether grounding detection is active
 * @param action          action to take when hallucinations are detected
 * @param maxSources      maximum number of source documents per request (0 = unlimited)
 * @param maxSourceLength maximum character length per source document (0 = unlimited)
 */
public record GroundingConfig(boolean enabled, GuardrailAction action, int maxSources, int maxSourceLength) {

    public static final GroundingConfig DISABLED = new GroundingConfig(false, GuardrailAction.LOG, 50, 10_000);

    /** Constructor with the default source limits. */
    public GroundingConfig(boolean enabled, GuardrailAction action) {
        this(enabled, action, 50, 10_000);
    }
}