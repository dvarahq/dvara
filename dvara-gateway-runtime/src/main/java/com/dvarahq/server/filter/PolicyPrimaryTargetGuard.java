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
package com.dvarahq.server.filter;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterOrder;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.policy.PolicyContext;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyEngine;
import com.dvarahq.core.resilience.PrimaryTargetGuard;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * The model a route actually sends passes the same policy the request passed. The policy filter checks the model
 * the caller named; a route can send another one, such as a pinned version or a model tier. A model allowlist that
 * let {@code gpt-4o} through is asked again about {@code gpt-4o-2024-08-06}, in the context the policy filter used
 * for this request.
 *
 * <p>A refusal is the error the policy filter answers with. When a route fallback the policy allows serves the
 * request, nothing is recorded. When none does, {@link #refused} writes the one {@code POLICY_DENIED} event, as the
 * filter does. A model the filter already denied never reaches the dispatcher, so it is not recorded twice.
 *
 * <p>Asked in the order of the request filters: after a guard for a check that runs before the policy filter.
 */
@Component
@Order(FilterOrder.POLICY_ENFORCEMENT)
public class PolicyPrimaryTargetGuard implements PrimaryTargetGuard {

    private final PolicyEngine policyEngine;
    private final AuditWriter auditWriter;

    public PolicyPrimaryTargetGuard(PolicyEngine policyEngine, AuditWriter auditWriter) {
        this.policyEngine = policyEngine;
        this.auditWriter = auditWriter;
    }

    @Override
    public GatewayException refuse(ChatRequest request, String provider) {
        PolicyDecision denial = denial(request);
        return denial == null ? null : new GatewayException("POLICY_DENIED", denial.reason());
    }

    @Override
    public void refused(ChatRequest request, String provider, GatewayException error) {
        PolicyContext context = PolicyEnforcementFilter.rememberedContext();
        PolicyDecision denial = denial(request);
        if (context != null && denial != null) {
            PolicyEnforcementFilter.auditPolicyDenial(auditWriter, request, context, denial);
        }
    }

    /**
     * The policy's denial of {@code request} as it will be sent, or null. Null when no policy was evaluated for the
     * request, or when the model sent is the one the filter already allowed.
     */
    private PolicyDecision denial(ChatRequest request) {
        PolicyContext context = PolicyEnforcementFilter.rememberedContext();
        if (context == null || Objects.equals(request.getModel(), PolicyEnforcementFilter.rememberedModel())) {
            return null;
        }
        PolicyDecision decision = policyEngine.evaluate(context, request);
        return decision.allowed() ? null : decision;
    }
}
