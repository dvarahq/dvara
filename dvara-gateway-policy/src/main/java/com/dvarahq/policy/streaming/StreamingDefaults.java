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
 * The install-wide settings a workspace is resolved against.
 *
 * <p>A record rather than the properties classes themselves, so the resolver can live in this module
 * and both planes can share one implementation. Each plane maps its own properties into this.</p>
 *
 * <p>The four {@code enabled} / {@code scanStreaming} flags are <b>kill switches</b>: a workspace can
 * never turn one back on. The rest are defaults a workspace may override.</p>
 *
 * <p>{@code groundingMaxSources} and {@code groundingMaxSourceLength} bound what one request may ask
 * the grounding detector to do, since the caller supplies the documents. Non-positive means
 * unlimited, as on the non-streaming path.</p>
 */
public record StreamingDefaults(
        boolean piiEnabled, boolean piiScanStreaming, PiiAction piiAction,
        boolean guardrailEnabled, boolean guardrailScanStreaming, GuardrailAction guardrailAction,
        double guardrailRiskThreshold,
        boolean groundingEnabled, GuardrailAction groundingAction,
        int groundingMaxSources, int groundingMaxSourceLength) {

    public StreamingDefaults {
        piiAction = piiAction == null ? PiiAction.LOG : piiAction;
        guardrailAction = guardrailAction == null ? GuardrailAction.LOG : guardrailAction;
        groundingAction = groundingAction == null ? GuardrailAction.LOG : groundingAction;
    }
}
