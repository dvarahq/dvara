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
package com.dvarahq.core.policy;

import java.util.List;

public record PolicyDecision(boolean allowed, String reason, String policyId, String ruleId,
                              List<PolicyWarning> warnings) {

    /**
     * Null warnings become an empty list, so a decision built directly rather than through the
     * factories can be iterated without a guard.
     */
    public PolicyDecision {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static final PolicyDecision ALLOW = new PolicyDecision(true, null, null, null, List.of());

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(false, reason, null, null, List.of());
    }

    public static PolicyDecision deny(String reason, String policyId, String ruleId) {
        return new PolicyDecision(false, reason, policyId, ruleId, List.of());
    }

    public static PolicyDecision allowWithWarnings(List<PolicyWarning> warnings) {
        return new PolicyDecision(true, null, null, null, warnings != null ? warnings : List.of());
    }

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}