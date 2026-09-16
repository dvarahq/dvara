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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyDecisionTest {

    @Test
    void allow_hasEmptyWarnings() {
        assertThat(PolicyDecision.ALLOW.warnings()).isEmpty();
        assertThat(PolicyDecision.ALLOW.hasWarnings()).isFalse();
        assertThat(PolicyDecision.ALLOW.allowed()).isTrue();
    }

    @Test
    void denyWithReason_hasEmptyWarnings() {
        PolicyDecision decision = PolicyDecision.deny("blocked");
        assertThat(decision.warnings()).isEmpty();
        assertThat(decision.hasWarnings()).isFalse();
        assertThat(decision.allowed()).isFalse();
    }

    @Test
    void denyWithIds_hasEmptyWarnings() {
        PolicyDecision decision = PolicyDecision.deny("blocked", "p-1", "r-1");
        assertThat(decision.warnings()).isEmpty();
        assertThat(decision.hasWarnings()).isFalse();
        assertThat(decision.policyId()).isEqualTo("p-1");
        assertThat(decision.ruleId()).isEqualTo("r-1");
    }

    @Test
    void allowWithWarnings_containsWarnings() {
        List<PolicyWarning> warnings = List.of(
                new PolicyWarning("WARN_AGENT", "Budget at 80%", "p-1", "r-1"));

        PolicyDecision decision = PolicyDecision.allowWithWarnings(warnings);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.hasWarnings()).isTrue();
        assertThat(decision.warnings()).hasSize(1);
        assertThat(decision.warnings().getFirst().type()).isEqualTo("WARN_AGENT");
    }

    @Test
    void allowWithWarnings_nullWarnings_treatedAsEmpty() {
        PolicyDecision decision = PolicyDecision.allowWithWarnings(null);
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.hasWarnings()).isFalse();
        assertThat(decision.warnings()).isEmpty();
    }

    @Test
    void allowWithWarnings_emptyList_noWarnings() {
        PolicyDecision decision = PolicyDecision.allowWithWarnings(List.of());
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.hasWarnings()).isFalse();
    }

    @Test
    void nullWarnings_normaliseToAnEmptyList() {
        // ChatExecutionService.auditBudgetWarning iterates warnings() without a guard, so a decision
        // built directly rather than through the factories must still return an empty list.
        PolicyDecision decision = new PolicyDecision(true, null, null, null, null);

        assertThat(decision.warnings()).isEmpty();
        assertThat(decision.hasWarnings()).isFalse();
    }
}
