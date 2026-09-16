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
import com.dvarahq.core.policy.PolicyContext;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyEngine;
import com.dvarahq.core.policy.Policy;
import com.dvarahq.core.policy.PolicyMutationEvent;
import com.dvarahq.core.policy.PolicyWarning;
import com.dvarahq.core.policy.PolicyRepository;
import com.dvarahq.core.policy.PolicyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DefaultPolicyEngine implements PolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultPolicyEngine.class);

    private final PolicyRepository policyRepository;
    private final AuditWriter auditWriter;
    private final PolicyCompiler compiler;
    private volatile PolicyIndex index = PolicyIndex.build(List.of());
    private volatile Map<String, String> compilationFailures = Map.of();

    public DefaultPolicyEngine(PolicyRepository policyRepository, AuditWriter auditWriter,
                                   PolicyCompiler compiler) {
        this.policyRepository = policyRepository;
        this.auditWriter = auditWriter;
        this.compiler = compiler;
    }

    @Override
    public PolicyDecision evaluate(PolicyContext context, ChatRequest request) {
        String workspaceId = context.workspaceId();
        List<PolicyIndex.PolicyScopedRule> scopedRules = index.scopedRulesFor(workspaceId);

        // Pass 1: Active-only evaluation
        PolicyDecision activeDecision = null;
        List<PolicyWarning> warnings = new ArrayList<>();

        for (PolicyIndex.PolicyScopedRule scoped : scopedRules) {
            CompiledPolicy policy = scoped.policy();
            CompiledRule rule = scoped.rule();

            if (policy.status() != PolicyStatus.ACTIVE) {
                continue;
            }
            if (!rule.matcher().matches(context, request)) {
                continue;
            }

            if ("DENY".equalsIgnoreCase(rule.action())) {
                String reason = rule.message() != null ? rule.message()
                        : "Request blocked by policy rule: " + rule.ruleId();
                activeDecision = PolicyDecision.deny(reason, policy.policyId(), rule.ruleId());
                break;
            }

            if ("WARN_AGENT".equalsIgnoreCase(rule.action())) {
                String message = rule.message() != null ? rule.message()
                        : "Warning from policy rule: " + rule.ruleId();
                warnings.add(new PolicyWarning("WARN_AGENT", message, policy.policyId(), rule.ruleId()));
            }
        }

        if (activeDecision == null) {
            activeDecision = warnings.isEmpty()
                    ? PolicyDecision.ALLOW
                    : PolicyDecision.allowWithWarnings(warnings);
        }


        return activeDecision;
    }

    @Override
    public void validateDsl(String dsl) {
        if (dsl == null || dsl.isBlank()) return;
        try {
            compiler.compileDsl(dsl);
        } catch (PolicyCompiler.PolicyCompilationException e) {
            throw new com.dvarahq.core.exception.GatewayException("INVALID_POLICY_DSL", e.getMessage());
        }
    }

    @Override
    public PolicyDecision evaluateDsl(String dsl, PolicyContext context, ChatRequest request) {
        CompiledPolicy compiled;
        try {
            compiled = compiler.compileDsl(dsl);
        } catch (PolicyCompiler.PolicyCompilationException e) {
            return PolicyDecision.deny("DSL compilation error: " + e.getMessage());
        }

        for (CompiledRule rule : compiled.rules()) {
            if (rule.matcher().matches(context, request)) {
                if ("DENY".equalsIgnoreCase(rule.action())) {
                    String reason = rule.message() != null ? rule.message()
                            : "Request blocked by policy rule: " + rule.ruleId();
                    return PolicyDecision.deny(reason, null, rule.ruleId());
                }
            }
        }

        return PolicyDecision.ALLOW;
    }

    @Override
    public void reload() {
        rebuildIndex();
    }

    /**
     * Rebuilds the index, compiling each policy on its own.
     *
     * <p>A policy that does not compile is skipped and named, and every other policy still governs.
     * Aborting the whole rebuild on the first {@link PolicyCompiler.PolicyCompilationException}
     * would leave the previous index in place (empty on a fresh boot), so one typo would disarm
     * enforcement for every workspace and fail open. The failures are retained
     * ({@link #getCompilationFailures()}) so the reload path can report them rather than claim
     * success.
     */
    public void rebuildIndex() {
        try {
            List<CompiledPolicy> compiled = new ArrayList<>();
            Map<String, String> failures = new LinkedHashMap<>();

            for (Policy policy : policyRepository.findAll()) {
                if (policy.getStatus() != PolicyStatus.ACTIVE) {
                    continue;
                }
                try {
                    compiled.add(compiler.compile(policy));
                } catch (RuntimeException e) {
                    // Name the policy and spell out the consequence: it is not being enforced at all.
                    failures.put(policy.getId(), e.getMessage());
                    log.error("Policy '{}' ({}) will NOT be enforced — its DSL does not compile: {} "
                                    + "Every other policy is unaffected. Fix or archive it.",
                            policy.getName(), policy.getId(), e.getMessage());
                }
            }

            this.index = PolicyIndex.build(compiled);
            this.compilationFailures = Map.copyOf(failures);

            if (failures.isEmpty()) {
                log.info("Policy index rebuilt with {} active policies", compiled.size());
            } else {
                log.error("Policy index rebuilt with {} active policies, but {} could not be "
                                + "compiled and are NOT being enforced: {}",
                        compiled.size(), failures.size(), failures.keySet());
            }

        } catch (Exception e) {
            // Reaching here means the repository read itself failed, not a bad policy. Keep the
            // previous index rather than disarming enforcement on a transient database error.
            log.error("Failed to rebuild policy index — keeping the previously loaded index", e);
        }
    }

    /** Policy id -> compiler message, for the policies skipped by the last {@link #rebuildIndex()}. */
    public Map<String, String> getCompilationFailures() {
        return compilationFailures;
    }

    @Override
    public int policyCompilationFailureCount() {
        return compilationFailures.size();
    }

    @EventListener
    public void onPolicyMutation(PolicyMutationEvent event) {
        log.debug("Policy mutation detected (policyId={}), rebuilding index", event.policyId());
        rebuildIndex();
    }

}