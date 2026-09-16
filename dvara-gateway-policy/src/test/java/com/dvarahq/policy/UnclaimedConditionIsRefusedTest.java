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
package com.dvarahq.policy;

import com.dvarahq.core.policy.Policy;
import com.dvarahq.policy.dsl.ConditionDsl;
import com.dvarahq.policy.dsl.RuleDsl;
import com.dvarahq.policy.rule.PolicyRule;
import com.dvarahq.core.policy.PolicyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A condition no compiler claims must be REFUSED, not compiled into a matcher that never fires.
 *
 * <p>This is the whole safety argument for making condition kinds pluggable. The tempting
 * implementation, ignoring what you do not understand, produces a policy that saves, activates,
 * appears in the list, and enforces nothing.</p>
 *
 * <p>Refusing at compile time puts the error where an author sees it: the Console calls
 * {@code validateDsl} on create, update, and any transition into ACTIVE, so a policy using a
 * condition this build cannot compile can never be saved active. On load, that one policy is skipped
 * and named rather than silently applied with no effect.</p>
 */
class UnclaimedConditionIsRefusedTest {

    /** A build with only the built-in kinds — model, max_tokens, tools. */
    private final PolicyCompiler simpleOnly = new PolicyCompiler(List.of());

    /**
     * A build where something claims the condition. A stub rather than a real contributor, because
     * what is under test is that a claimed condition compiles, not what any particular contributor
     * compiles it to.
     */
    private final PolicyCompiler withContributor = new PolicyCompiler(List.of(new ConditionCompiler() {
        @Override
        public Set<String> supportedConditions() {
            return Set.of("data_residency");
        }

        @Override
        public List<PolicyRule> compile(ConditionDsl conditions, RuleDsl rule) {
            return conditions.getDataResidency() == null ? List.of() : List.of((ctx, req) -> true);
        }
    }));

    private static final String DATA_RESIDENCY = """
            rules:
              - id: r1
                conditions:
                  data_residency:
                    allowed_regions:
                      - eu-west-1
                action: DENY
                deny_message: "outside the region"
            """;

    private static final String MODEL_DENYLIST = """
            rules:
              - id: r1
                conditions:
                  model:
                    denylist:
                      - gpt-4o
                action: DENY
                deny_message: "not allowed"
            """;

    @Test
    @DisplayName("an unclaimed condition throws, naming it and what the build does support")
    void unclaimedConditionIsRefused() {
        assertThatThrownBy(() -> simpleOnly.compile(policy(DATA_RESIDENCY)))
                .hasMessageContaining("data_residency")
                .hasMessageContaining("not available in this build")
                // an author has to be told what they CAN use, or the error is a dead end
                .hasMessageContaining("max_tokens");
    }

    @Test
    @DisplayName("the same policy compiles where a contributor claims the condition")
    void claimedConditionCompiles() {
        assertThat(withContributor.compile(policy(DATA_RESIDENCY))).isNotNull();
    }

    @Test
    @DisplayName("the built-in kinds never depend on a contributor")
    void builtInsCompileWithNoContributors() {
        assertThat(simpleOnly.compile(policy(MODEL_DENYLIST)))
                .describedAs("model / max_tokens / tools are built in; they must not become "
                        + "unavailable when no contributor is present")
                .isNotNull();
    }

    private static Policy policy(String dsl) {
        return Policy.builder()
                .id("p-test").workspaceId("workspace-1").name("test-policy")
                .dsl(dsl).status(PolicyStatus.ACTIVE).version(1)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }
}
