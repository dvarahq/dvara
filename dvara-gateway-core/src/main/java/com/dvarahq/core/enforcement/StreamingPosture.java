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
package com.dvarahq.core.enforcement;

import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.pii.PiiAction;

import java.util.List;
import java.util.Map;

/**
 * The resolved enforcement posture for one response, decided once before the first chunk.
 *
 * <p>Immutable for the life of the stream. A workspace edit mid-response does not change it — a
 * response is judged under the posture it started with, or the same input could produce two answers.</p>
 *
 * <p>Resolution (typed row, then workspace metadata where the typed field is absent, then the
 * install-wide default, with global enablement as an AND) happens in one shared resolver before this
 * record is built. The engine never reads configuration.</p>
 */
public record StreamingPosture(
        boolean piiEnabled, PiiAction piiAction, Map<String, String> customPiiPatterns,
        boolean guardrailEnabled, GuardrailAction guardrailAction, double guardrailRiskThreshold,
        boolean groundingEnabled, GuardrailAction groundingAction, List<String> groundingSources) {

    public StreamingPosture {
        customPiiPatterns = customPiiPatterns == null ? Map.of() : Map.copyOf(customPiiPatterns);
        groundingSources = groundingSources == null ? List.of() : List.copyOf(groundingSources);
    }

    /**
     * Whether any enabled control can remove content or refuse the response.
     *
     * <p>The union, not a per-control decision: one buffered control means nothing can be released
     * early anyway. {@code LOG} records and {@code FLAG} labels — neither keeps anything from the
     * caller, so neither defers delivery.</p>
     */
    public boolean withholds() {
        boolean pii = piiEnabled && piiAction != PiiAction.LOG;
        boolean guardrail = guardrailEnabled && guardrailAction == GuardrailAction.BLOCK;
        boolean grounding = groundingActive() && groundingAction == GuardrailAction.BLOCK;
        return pii || guardrail || grounding;
    }

    /** Grounding can only be judged with something to judge against. */
    public boolean groundingActive() {
        return groundingEnabled && !groundingSources.isEmpty();
    }

    /** A control that can only be judged on a whole answer, so a fragment cannot satisfy it. */
    public boolean hasWholeResponseControl() {
        return groundingActive() && groundingAction == GuardrailAction.BLOCK;
    }

    /** The A2A plane shares PII enforcement but has no guardrail or grounding controls. */
    public StreamingPosture piiOnly() {
        return new StreamingPosture(piiEnabled, piiAction, customPiiPatterns,
                false, GuardrailAction.LOG, guardrailRiskThreshold,
                false, GuardrailAction.LOG, List.of());
    }
}
