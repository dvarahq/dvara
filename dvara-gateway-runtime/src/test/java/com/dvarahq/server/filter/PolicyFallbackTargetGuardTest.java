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
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.policy.PolicyContext;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #7 AC-CPF-05: a fallback to another model passes the policy the request passed, in the same context. */
class PolicyFallbackTargetGuardTest {

    /** The workspace's allowlist: gpt-4o and claude-approved, and nothing else. */
    private static final PolicyEngine ALLOWLIST = (ctx, request) ->
            List.of("gpt-4o", "claude-approved").contains(request.getModel())
                    ? PolicyDecision.ALLOW : PolicyDecision.deny("model " + request.getModel() + " is not approved");

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static ChatRequest as(String model) {
        return ChatRequest.builder().model(model).messages(List.of(MultimodalMessage.user("hi"))).build();
    }

    @Test
    void aBackupModelThePolicyRefuses_isRefused_andAnApprovedOneIsNot() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute(PolicyEnforcementFilter.POLICY_CONTEXT_ATTRIBUTE,
                new PolicyContext("ws-1", null, "key-1", Map.of()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        PolicyFallbackTargetGuard guard = new PolicyFallbackTargetGuard(ALLOWLIST);

        assertThat(guard.refuse(as("claude-unapproved"), "anthropic")).contains("not approved");
        assertThat(guard.refuse(as("claude-approved"), "anthropic")).isNull();
    }

    @Test
    void withNoPolicyEvaluatedForTheRequest_nothingIsRefused() {
        assertThat(new PolicyFallbackTargetGuard(ALLOWLIST).refuse(as("claude-unapproved"), "anthropic")).isNull();
    }
}
