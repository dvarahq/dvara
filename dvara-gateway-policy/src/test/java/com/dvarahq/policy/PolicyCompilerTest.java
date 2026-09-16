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
import com.dvarahq.core.policy.PolicyStatus;
import com.dvarahq.policy.dsl.PolicyDsl;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyCompilerTest {

    private final PolicyCompiler compiler = new PolicyCompiler();

    @Test
    void compile_modelAllowlist_createsRule() {
        String dsl = """
                version: "1"
                rules:
                  - id: allow-gpt4
                    priority: 10
                    conditions:
                      model:
                        allowlist:
                          - gpt-4o
                          - claude-sonnet-4-5
                    action: DENY
                    deny_message: "Model not allowed"
                """;
        Policy policy = buildPolicy(dsl);
        CompiledPolicy compiled = compiler.compile(policy);

        assertThat(compiled.rules()).hasSize(1);
        assertThat(compiled.rules().getFirst().ruleId()).isEqualTo("allow-gpt4");
        assertThat(compiled.rules().getFirst().priority()).isEqualTo(10);
        assertThat(compiled.rules().getFirst().message()).isEqualTo("Model not allowed");
    }

    @Test
    void compile_modelDenylist_createsRule() {
        String dsl = """
                version: "1"
                rules:
                  - id: deny-legacy
                    conditions:
                      model:
                        denylist:
                          - gpt-3.5-turbo
                    action: DENY
                """;
        CompiledPolicy compiled = compiler.compile(buildPolicy(dsl));
        assertThat(compiled.rules()).hasSize(1);
    }

    @Test
    void compile_maxTokens_createsRule() {
        String dsl = """
                version: "1"
                rules:
                  - id: limit-tokens
                    conditions:
                      max_tokens:
                        limit: 4096
                    action: DENY
                """;
        CompiledPolicy compiled = compiler.compile(buildPolicy(dsl));
        assertThat(compiled.rules()).hasSize(1);
    }

    @Test
    void compile_toolsDenylist_createsRule() {
        String dsl = """
                version: "1"
                rules:
                  - id: block-tools
                    conditions:
                      tools:
                        denylist:
                          - shell_exec
                          - code_execution
                    action: DENY
                """;
        CompiledPolicy compiled = compiler.compile(buildPolicy(dsl));
        assertThat(compiled.rules()).hasSize(1);
    }



    @Test
    void compile_conditionsThatSetNothing_areRefusedNamingTheRule() {
        // A condition that sets nothing would compile into a rule that never fires, so the policy
        // would look active and check nothing.
        for (String conditions : List.of(
                "{model: {allowlist: []}}",
                "{model: {denylist: []}}",
                "{model: {}}",
                "{max_tokens: {}}",
                "{max_tokens: {limit: 0}}",
                "{max_tokens: {limit: -5}}",
                "{tools: {}}",
                "{tools: {denylist: []}}")) {
            String dsl = """
                    version: "1"
                    rules:
                      - id: checks-nothing
                        conditions: %s
                        action: DENY
                    """.formatted(conditions);
            assertThatThrownBy(() -> compiler.compile(buildPolicy(dsl)))
                    .as(conditions)
                    .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                    .hasMessageContaining("checks-nothing")
                    .hasMessageContaining("could never fire");
        }
    }

    @Test
    void compile_anEmptyConditionBesideOneThatChecksSomething_stillCompiles() {
        String dsl = """
                version: "1"
                rules:
                  - id: allow-gpt4
                    conditions:
                      model:
                        allowlist: [gpt-4o]
                      tools: {}
                    action: DENY
                """;
        assertThat(compiler.compile(buildPolicy(dsl)).rules()).hasSize(1);
    }

    @Test
    void compile_multipleRules_allCompiled() {
        String dsl = """
                version: "1"
                rules:
                  - id: rule1
                    priority: 1
                    conditions:
                      model:
                        denylist: [gpt-3.5-turbo]
                    action: DENY
                  - id: rule2
                    priority: 2
                    conditions:
                      max_tokens:
                        limit: 4096
                    action: DENY
                """;
        CompiledPolicy compiled = compiler.compile(buildPolicy(dsl));
        assertThat(compiled.rules()).hasSize(2);
    }

    @Test
    void compile_noRules_returnsEmptyList() {
        String dsl = """
                version: "1"
                """;
        CompiledPolicy compiled = compiler.compile(buildPolicy(dsl));
        assertThat(compiled.rules()).isEmpty();
    }

    @Test
    void compile_invalidYaml_throwsException() {
        String dsl = "{ invalid yaml: [";
        assertThatThrownBy(() -> compiler.compile(buildPolicy(dsl)))
                .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                .hasMessageMatching("(?s).*(Failed to parse policy DSL|Unknown key).*");
    }

    @Test
    void compile_unknownTopLevelKey_throws() {
        // PolicyDsl rejects unknown properties, so a stray top-level key surfaces as a
        // PolicyCompilationException naming it.
        String dsl = """
                version: "1"
                custom_field: ignored
                rules:
                  - id: test
                    conditions:
                      model:
                        allowlist: [gpt-4o]
                    action: DENY
                """;
        assertThatThrownBy(() -> compiler.compile(buildPolicy(dsl)))
                .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                .hasMessageContaining("custom_field");
    }

    @Test
    void compile_unknownRuleLevelKey_throws() {
        String dsl = """
                version: "1"
                rules:
                  - id: test
                    unknown: value
                    conditions:
                      model:
                        allowlist: [gpt-4o]
                    action: DENY
                """;
        assertThatThrownBy(() -> compiler.compile(buildPolicy(dsl)))
                .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                .hasMessageContaining("unknown");
    }

    @Test
    void compile_tenantAsCondition_throwsWithHint() {
        // An author who writes `workspace:` as a per-rule condition is pointed at the entity-level
        // workspaceId.
        String dsl = """
                version: "1"
                rules:
                  - id: workspace-rule
                    conditions:
                      workspace: acme-corp
                      model:
                        allowlist: [gpt-4o]
                    action: DENY
                """;
        assertThatThrownBy(() -> compiler.compile(buildPolicy(dsl)))
                .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                .hasMessageContaining("workspace")
                .hasMessageContaining("entity level");
    }

    @Test
    void compile_singularConditionTypo_throwsWithHint() {
        // condition: (singular) — typo for conditions: (plural).
        String dsl = """
                version: "1"
                rules:
                  - id: typo
                    condition:
                      model:
                        allowlist: [gpt-4o]
                    action: DENY
                """;
        assertThatThrownBy(() -> compiler.compile(buildPolicy(dsl)))
                .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                .hasMessageContaining("condition")
                .hasMessageContaining("conditions:");
    }

    @Test
    void compile_unknownAction_throws() {
        // Anything not DENY or WARN_AGENT must be rejected at compile time rather than read as DENY.
        String dsl = """
                version: "1"
                rules:
                  - id: shadow-rule
                    conditions:
                      model:
                        allowlist: [gpt-4o]
                    action: SHADOW
                """;
        assertThatThrownBy(() -> compiler.compile(buildPolicy(dsl)))
                .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                .hasMessageContaining("shadow-rule")
                .hasMessageContaining("unknown action")
                .hasMessageContaining("SHADOW")
                // Set.of(...).toString() iteration order is unspecified —
                // assert both allowed actions are listed, not a fixed order.
                .hasMessageContaining("DENY")
                .hasMessageContaining("WARN_AGENT");
    }

    @Test
    void compile_lowercaseWarn_throws() {
        // `warn` does not match WARN_AGENT and must be refused rather than read as something else.
        String dsl = """
                version: "1"
                rules:
                  - id: warn-typo
                    conditions:
                      model:
                        allowlist: [gpt-4o]
                    action: warn
                """;
        assertThatThrownBy(() -> compiler.compile(buildPolicy(dsl)))
                .isInstanceOf(PolicyCompiler.PolicyCompilationException.class)
                .hasMessageContaining("warn-typo")
                .hasMessageContaining("'warn'");
    }

    @Test
    void compile_validActionsLowercase_succeeds() {
        // Action matching is case-insensitive — both `deny` and `DENY`
        // (and `WARN_AGENT` and `warn_agent`) should compile. The
        // CompiledRule's action is normalised to uppercase.
        String dsl = """
                version: "1"
                rules:
                  - id: lowercase-deny
                    conditions:
                      model:
                        allowlist: [gpt-4o]
                    action: deny
                  - id: lowercase-warn-agent
                    conditions:
                      model:
                        denylist: [gpt-3.5]
                    action: warn_agent
                """;
        CompiledPolicy compiled = compiler.compile(buildPolicy(dsl));
        assertThat(compiled.rules()).hasSize(2);
        assertThat(compiled.rules().get(0).action()).isEqualTo("DENY");
        assertThat(compiled.rules().get(1).action()).isEqualTo("WARN_AGENT");
    }

    @Test
    void compile_defaultPriority_is100() {
        String dsl = """
                version: "1"
                rules:
                  - id: test
                    conditions:
                      model:
                        denylist: [gpt-3.5-turbo]
                    action: DENY
                """;
        CompiledPolicy compiled = compiler.compile(buildPolicy(dsl));
        assertThat(compiled.rules().getFirst().priority()).isEqualTo(100);
    }

    @Test
    void compileDsl_returnsCompiledPolicyWithoutPolicyId() {
        String dsl = """
                version: "1"
                rules:
                  - id: test
                    conditions:
                      model:
                        denylist: [gpt-3.5-turbo]
                    action: DENY
                """;
        CompiledPolicy compiled = compiler.compileDsl(dsl);
        assertThat(compiled.policyId()).isNull();
        assertThat(compiled.rules()).hasSize(1);
    }

    @Test
    void parseDsl_validYaml_returnsModel() {
        String dsl = """
                version: "1"
                rules:
                  - id: test
                    conditions:
                      model:
                        allowlist: [gpt-4o]
                """;
        PolicyDsl parsed = compiler.parseDsl(dsl);
        assertThat(parsed.getVersion()).isEqualTo("1");
        assertThat(parsed.getRules()).hasSize(1);
        assertThat(parsed.getRules().getFirst().getConditions().getModel().getAllowlist())
                .containsExactly("gpt-4o");
    }

    private static Policy buildPolicy(String dsl) {
        return Policy.builder()
                .id("p-test")
                .workspaceId("workspace-1")
                .name("test-policy")
                .dsl(dsl)
                .status(PolicyStatus.ACTIVE)
                .version(1)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}