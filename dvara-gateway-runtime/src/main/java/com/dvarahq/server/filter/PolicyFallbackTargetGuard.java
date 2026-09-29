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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.policy.PolicyContext;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyEngine;
import com.dvarahq.core.resilience.FallbackTargetGuard;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * A route fallback to another model passes the same policy the request passed (#7 REQ-CPF-05, AC-CPF-05):
 * a model allowlist that let {@code gpt-4o} through is asked again about {@code claude-…}. Evaluated in the
 * context the policy filter used for this request, and only evaluated: the denial is not audited again,
 * since nothing was denied to the caller; the target is just not used.
 */
@Component
public class PolicyFallbackTargetGuard implements FallbackTargetGuard {

    private final PolicyEngine policyEngine;

    public PolicyFallbackTargetGuard(PolicyEngine policyEngine) {
        this.policyEngine = policyEngine;
    }

    @Override
    public String refuse(ChatRequest request, String provider) {
        PolicyContext context = currentContext();
        if (context == null) {
            return null;   // no policy was evaluated for this request, so none applies to its fallback
        }
        PolicyDecision decision = policyEngine.evaluate(context, request);
        return decision.allowed() ? null : "policy: " + decision.reason();
    }

    private static PolicyContext currentContext() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        try {
            return (PolicyContext) attrs.getAttribute(PolicyEnforcementFilter.POLICY_CONTEXT_ATTRIBUTE,
                    RequestAttributes.SCOPE_REQUEST);
        } catch (IllegalStateException requestIsGone) {
            return null;
        }
    }
}
