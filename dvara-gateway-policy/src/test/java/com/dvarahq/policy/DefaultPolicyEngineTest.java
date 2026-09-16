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

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.policy.Policy;
import com.dvarahq.core.policy.PolicyContext;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyRepository;
import com.dvarahq.core.policy.PolicyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DefaultPolicyEngineTest {

    private PolicyRepository policyRepository;
    private AuditWriter auditWriter;
    private PolicyCompiler compiler;
    private DefaultPolicyEngine engine;

    @BeforeEach
    void setUp() {
        policyRepository = mock(PolicyRepository.class);
        auditWriter = mock(AuditWriter.class);
        compiler = new PolicyCompiler();
        engine = new DefaultPolicyEngine(policyRepository, auditWriter, compiler);
    }

    @Test
    void evaluate_noPolicies_returnsAllow() {
        when(policyRepository.findAll()).thenReturn(List.of());
        engine.rebuildIndex();

        PolicyContext ctx = new PolicyContext("workspace-1", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_activeDenyPolicy_returnsDeny() {
        Policy policy = buildPolicy("p-1", "workspace-1", PolicyStatus.ACTIVE, """
                version: "1"
                rules:
                  - id: block-legacy
                    conditions:
                      model:
                        denylist: [gpt-3.5-turbo]
                    action: DENY
                    deny_message: "Legacy model not allowed"
                """);
        when(policyRepository.findAll()).thenReturn(List.of(policy));
        engine.rebuildIndex();

        PolicyContext ctx = new PolicyContext("workspace-1", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("gpt-3.5-turbo").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("Legacy model not allowed");
        assertThat(decision.policyId()).isEqualTo("p-1");
        assertThat(decision.ruleId()).isEqualTo("block-legacy");
    }

    @Test
    void evaluate_activePolicyAllowed_returnsAllow() {
        Policy policy = buildPolicy("p-1", "workspace-1", PolicyStatus.ACTIVE, """
                version: "1"
                rules:
                  - id: block-legacy
                    conditions:
                      model:
                        denylist: [gpt-3.5-turbo]
                    action: DENY
                """);
        when(policyRepository.findAll()).thenReturn(List.of(policy));
        engine.rebuildIndex();

        PolicyContext ctx = new PolicyContext("workspace-1", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isTrue();
    }


    @Test
    void evaluate_draftPolicyIgnored() {
        Policy policy = buildPolicy("p-draft", "workspace-1", PolicyStatus.DRAFT, """
                version: "1"
                rules:
                  - id: draft-rule
                    conditions:
                      model:
                        denylist: [gpt-4o]
                    action: DENY
                """);
        when(policyRepository.findAll()).thenReturn(List.of(policy));
        engine.rebuildIndex();

        PolicyContext ctx = new PolicyContext("workspace-1", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_archivedPolicyIgnored() {
        Policy policy = buildPolicy("p-archived", "workspace-1", PolicyStatus.ARCHIVED, """
                version: "1"
                rules:
                  - id: archived-rule
                    conditions:
                      model:
                        denylist: [gpt-4o]
                    action: DENY
                """);
        when(policyRepository.findAll()).thenReturn(List.of(policy));
        engine.rebuildIndex();

        PolicyContext ctx = new PolicyContext("workspace-1", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_tenantScoping_onlyAppliesMatchingWorkspace() {
        Policy tenant1Policy = buildPolicy("p-t1", "workspace-1", PolicyStatus.ACTIVE, """
                version: "1"
                rules:
                  - id: t1-deny
                    conditions:
                      model:
                        denylist: [gpt-4o]
                    action: DENY
                """);
        when(policyRepository.findAll()).thenReturn(List.of(tenant1Policy));
        engine.rebuildIndex();

        // Workspace-2 should not be affected
        PolicyContext ctx = new PolicyContext("workspace-2", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void evaluate_globalPolicy_appliesAllWorkspaces() {
        Policy globalPolicy = buildPolicy("p-global", null, PolicyStatus.ACTIVE, """
                version: "1"
                rules:
                  - id: global-deny
                    conditions:
                      model:
                        denylist: [gpt-3.5-turbo]
                    action: DENY
                    deny_message: "Globally blocked"
                """);
        when(policyRepository.findAll()).thenReturn(List.of(globalPolicy));
        engine.rebuildIndex();

        PolicyContext ctx = new PolicyContext("any-workspace", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("gpt-3.5-turbo").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("Globally blocked");
    }

    @Test
    void evaluate_priorityOrdering_highestPriorityFirst() {
        Policy lowPriority = buildPolicy("p-low", "workspace-1", PolicyStatus.ACTIVE, """
                version: "1"
                rules:
                  - id: low-priority
                    priority: 100
                    conditions:
                      model:
                        denylist: [gpt-4o]
                    action: DENY
                    deny_message: "Low priority deny"
                """);
        Policy highPriority = buildPolicy("p-high", "workspace-1", PolicyStatus.ACTIVE, """
                version: "1"
                rules:
                  - id: high-priority
                    priority: 1
                    conditions:
                      model:
                        denylist: [gpt-4o]
                    action: DENY
                    deny_message: "High priority deny"
                """);
        when(policyRepository.findAll()).thenReturn(List.of(lowPriority, highPriority));
        engine.rebuildIndex();

        PolicyContext ctx = new PolicyContext("workspace-1", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("High priority deny");
        assertThat(decision.ruleId()).isEqualTo("high-priority");
    }

    @Test
    void evaluateDsl_validDsl_returnsDeny() {
        String dsl = """
                version: "1"
                rules:
                  - id: test-rule
                    conditions:
                      model:
                        denylist: [gpt-3.5-turbo]
                    action: DENY
                    deny_message: "Blocked by dry-run"
                """;
        PolicyContext ctx = PolicyContext.empty();
        ChatRequest request = ChatRequest.builder().model("gpt-3.5-turbo").build();

        PolicyDecision decision = engine.evaluateDsl(dsl, ctx, request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("Blocked by dry-run");
    }

    @Test
    void evaluateDsl_invalidDsl_returnsDenyWithError() {
        String dsl = "{ invalid yaml: [";
        PolicyContext ctx = PolicyContext.empty();
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        PolicyDecision decision = engine.evaluateDsl(dsl, ctx, request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).startsWith("DSL compilation error:");
    }

    @Test
    void evaluateDsl_allowed_returnsAllow() {
        String dsl = """
                version: "1"
                rules:
                  - id: test-rule
                    conditions:
                      model:
                        denylist: [gpt-3.5-turbo]
                    action: DENY
                """;
        PolicyContext ctx = PolicyContext.empty();
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        PolicyDecision decision = engine.evaluateDsl(dsl, ctx, request);
        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void rebuildIndex_calledOnPolicyMutationEvent() {
        when(policyRepository.findAll()).thenReturn(List.of());
        engine.rebuildIndex();
        verify(policyRepository).findAll();

        // Simulate mutation event
        engine.onPolicyMutation(new com.dvarahq.core.policy.PolicyMutationEvent("p-1"));
        verify(policyRepository, times(2)).findAll();
    }

    // -------------------------------------------------------------------------
    // WARN_AGENT action
    // -------------------------------------------------------------------------


    @Test
    void warnAgent_withNoMessage_isReportedAsAWarningFromTheRule() {
        String warnDsl = """
                version: "1"
                rules:
                  - id: no-message-warn
                    conditions:
                      model:
                        denylist: [gpt-4o]
                    action: WARN_AGENT
                """;
        when(policyRepository.findAll()).thenReturn(List.of(buildPolicy("p-warn", null, PolicyStatus.ACTIVE, warnDsl)));
        engine.rebuildIndex();

        PolicyDecision decision = engine.evaluate(new PolicyContext("t-1", null, null, java.util.Map.of()),
                ChatRequest.builder().model("gpt-4o").build());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.warnings()).singleElement()
                .satisfies(w -> assertThat(w.message()).isEqualTo("Warning from policy rule: no-message-warn"));
    }

    @Test
    void evaluate_shadowPolicy_isNotEvaluatedByThisBuild() {
        // A SHADOW policy is left out of the index like a DRAFT, so it neither denies nor warns.
        Policy policy = buildPolicy("p-shadow", "workspace-1", PolicyStatus.SHADOW, """
                version: "1"
                rules:
                  - id: shadow-rule
                    conditions:
                      model:
                        denylist: [gpt-4o]
                    action: DENY
                """);
        when(policyRepository.findAll()).thenReturn(List.of(policy));
        engine.rebuildIndex();

        PolicyDecision decision = engine.evaluate(new PolicyContext("workspace-1", null, null, java.util.Map.of()),
                ChatRequest.builder().model("gpt-4o").build());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.warnings()).isEmpty();
    }

    @Test
    void deny_takesPrecedence_overWarnAgent() {
        String warnDsl = """
                version: "1"
                rules:
                  - id: budget-warn
                    priority: 200
                    conditions:
                      budget_utilization:
                        threshold_pct: 50
                    action: WARN_AGENT
                    warn_message: "Budget warning"
                """;
        String denyDsl = """
                version: "1"
                rules:
                  - id: deny-rule
                    priority: 50
                    conditions:
                      model:
                        denylist: [gpt-4o]
                    action: DENY
                    deny_message: "Model denied"
                """;
        Policy warnPolicy = buildPolicy("p-warn", null, PolicyStatus.ACTIVE, warnDsl);
        Policy denyPolicy = buildPolicy("p-deny", null, PolicyStatus.ACTIVE, denyDsl);
        when(policyRepository.findAll()).thenReturn(List.of(warnPolicy, denyPolicy));
        engine.rebuildIndex();

        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("budget.utilization_pct", 80);
        PolicyContext ctx = new PolicyContext("t-1", null, null, attrs);
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("Model denied");
    }


    // ---- CEL `expression:` rules through the engine ------------------



    @Test
    void evaluate_celRuleAllowedThrough_returnsAllow() {
        // CEL rule that doesn't match → policy is non-blocking → ALLOW.
        // Verifies the non-match path through the engine for CEL rules.
        Policy policy = buildPolicy("p-cel", "workspace-1", PolicyStatus.ACTIVE, """
                version: "1"
                rules:
                  - id: cel-block
                    expression: 'request.model == "gpt-4o"'
                    action: DENY
                """);
        when(policyRepository.findAll()).thenReturn(List.of(policy));
        engine.rebuildIndex();

        PolicyContext ctx = new PolicyContext("workspace-1", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("claude-sonnet-4-5").build();

        PolicyDecision decision = engine.evaluate(ctx, request);
        assertThat(decision.allowed()).isTrue();
    }

    // --- one uncompilable policy must not disarm the others -----------------------------
    //
    // If the first compile failure threw out of rebuildIndex, the whole index would be discarded and
    // the engine left with whatever it held before (an empty index on a fresh boot). Enforcement
    // would then fail OPEN for every workspace on the pod while the next log line claimed the reload
    // had succeeded.

    private static final String VALID_DENY_DSL = """
            rules:
              - id: deny-the-model
                conditions:
                  model:
                    denylist: ["gpt-4o"]
                action: DENY
            """;

    /** Unknown rule key — exactly what a `denyMessage`-instead-of-`deny_message` typo produces. */
    private static final String UNCOMPILABLE_DSL = """
            rules:
              - id: nonsense
                thisKeyDoesNotExist: true
                action: DENY
            """;

    @Test
    void rebuildIndex_oneBrokenPolicy_stillEnforcesEveryOtherPolicy() {
        when(policyRepository.findAll()).thenReturn(List.of(
                buildPolicy("broken", "workspace-1", PolicyStatus.ACTIVE, UNCOMPILABLE_DSL),
                buildPolicy("good", "workspace-1", PolicyStatus.ACTIVE, VALID_DENY_DSL)));

        engine.rebuildIndex();

        PolicyContext ctx = new PolicyContext("workspace-1", null, null, Map.of());
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        assertThat(engine.evaluate(ctx, request).allowed())
                .as("a broken policy must not switch off a valid one")
                .isFalse();
    }

    @Test
    void rebuildIndex_brokenPolicyIsReportedRatherThanSwallowed() {
        when(policyRepository.findAll()).thenReturn(List.of(
                buildPolicy("broken", "workspace-1", PolicyStatus.ACTIVE, UNCOMPILABLE_DSL),
                buildPolicy("good", "workspace-1", PolicyStatus.ACTIVE, VALID_DENY_DSL)));

        engine.rebuildIndex();

        // The count is what a configuration refresher reads to decide whether "reloaded" is honest.
        assertThat(engine.policyCompilationFailureCount()).isEqualTo(1);
        assertThat(engine.getCompilationFailures()).containsKey("broken");
        assertThat(engine.getCompilationFailures().get("broken")).contains("thisKeyDoesNotExist");
    }

    @Test
    void rebuildIndex_allPoliciesValid_reportsNoFailures() {
        when(policyRepository.findAll()).thenReturn(List.of(
                buildPolicy("good", "workspace-1", PolicyStatus.ACTIVE, VALID_DENY_DSL)));

        engine.rebuildIndex();

        assertThat(engine.policyCompilationFailureCount()).isZero();
        assertThat(engine.getCompilationFailures()).isEmpty();
    }

    @Test
    void rebuildIndex_brokenPolicyIsClearedOnceFixed() {
        when(policyRepository.findAll()).thenReturn(List.of(
                buildPolicy("broken", "workspace-1", PolicyStatus.ACTIVE, UNCOMPILABLE_DSL)));
        engine.rebuildIndex();
        assertThat(engine.policyCompilationFailureCount()).isEqualTo(1);

        // The operator fixes the DSL; the next rebuild must forget the failure, or the reload path
        // would report a degraded posture forever.
        when(policyRepository.findAll()).thenReturn(List.of(
                buildPolicy("broken", "workspace-1", PolicyStatus.ACTIVE, VALID_DENY_DSL)));
        engine.rebuildIndex();

        assertThat(engine.policyCompilationFailureCount()).isZero();
    }

    @Test
    void validateDsl_rejectsWhatTheCompilerRejects() {
        // The seam the Console calls on save. It is the same compiler the engine uses, which is what
        // makes "it saved" and "it will be enforced" the same question.
        assertThatThrownBy(() -> engine.validateDsl(UNCOMPILABLE_DSL))
                .hasMessageContaining("thisKeyDoesNotExist");

        assertThatCode(() -> engine.validateDsl(VALID_DENY_DSL)).doesNotThrowAnyException();
    }

    private static Policy buildPolicy(String id, String workspaceId, PolicyStatus status, String dsl) {
        return Policy.builder()
                .id(id)
                .workspaceId(workspaceId)
                .name("test")
                .dsl(dsl)
                .status(status)
                .version(1)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

}
