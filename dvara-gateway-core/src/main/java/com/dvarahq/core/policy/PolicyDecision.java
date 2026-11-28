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

/**
 * What a policy decided for a request.
 *
 * <p>{@code approvalRequired} marks a request a rule holds for a person ({@code REQUIRE_APPROVAL}). Such a decision is
 * not {@code allowed}: code that looks only at {@code allowed()} refuses the request rather than letting it through,
 * and only an approval gate that knows about holds can let it run once a person approves.
 */
public record PolicyDecision(boolean allowed, String reason, String policyId, String ruleId,
                              List<PolicyWarning> warnings, boolean approvalRequired) {

    /** A decision that holds nothing for approval. */
    public PolicyDecision(boolean allowed, String reason, String policyId, String ruleId, List<PolicyWarning> warnings) {
        this(allowed, reason, policyId, ruleId, warnings, false);
    }

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

    /**
     * The request needs a person's approval before it runs. It is not allowed until then: {@code allowed()} is false.
     */
    public static PolicyDecision approvalRequired(String reason, String policyId, String ruleId,
                                                  List<PolicyWarning> warnings) {
        return new PolicyDecision(false, reason, policyId, ruleId, warnings != null ? warnings : List.of(), true);
    }

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}