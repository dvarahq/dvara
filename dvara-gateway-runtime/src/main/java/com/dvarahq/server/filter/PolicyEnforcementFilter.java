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

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.ChatFilter;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.FilterOrder;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.policy.PolicyContext;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyEngine;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.security.SecurityContext;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Component
public class PolicyEnforcementFilter implements ChatFilter {

    private final PolicyEngine policyEngine;
    private final AuditWriter auditWriter;
    private final SecurityContext securityContext;
    private final RegionContext regionContext;
    private final Optional<WorkspaceRepository> workspaceRepository;

    public PolicyEnforcementFilter(PolicyEngine policyEngine, AuditWriter auditWriter,
                                    SecurityContext securityContext, RegionContext regionContext,
                                    Optional<WorkspaceRepository> workspaceRepository) {
        this.policyEngine = policyEngine;
        this.auditWriter = auditWriter;
        this.securityContext = securityContext;
        this.regionContext = regionContext;
        this.workspaceRepository = workspaceRepository;
    }

    @Override public int order() { return FilterOrder.POLICY_ENFORCEMENT; }

    @Override
    public ChatRequest preDispatch(ChatRequest request, FilterContext ctx) {
        var attributes = new HashMap<String, Object>();
        // Whatever ran earlier in the pipeline and wants to be visible to a rule leaves it on the
        // context; a filter at a lower order, registered by another module, can contribute an
        // attribute such as `budget.utilization_pct` this way. Nothing here needs to know which
        // attributes exist.
        ctx.getAttributes().forEach((key, value) -> {
            if (!key.startsWith(FilterContext.RESPONSE_HEADER_PREFIX) && value != null) {
                attributes.put(key, value);
            }
        });
        regionContext.currentRegion().ifPresent(r -> attributes.put("region", r));
        resolveWorkspaceRegion(ctx.getWorkspaceId())
                .ifPresent(r -> attributes.put("workspace_region", r));

        String userId = securityContext.currentUserId().orElse(null);
        PolicyContext policyCtx = new PolicyContext(
                ctx.getWorkspaceId(),
                userId,
                ctx.getApiKey(),
                attributes);

        PolicyDecision decision = policyEngine.evaluate(policyCtx, request);
        ctx.setPolicyDecision(decision);

        if (!decision.allowed()) {
            auditPolicyDenial(ctx, policyCtx, decision);
            throw new GatewayException("POLICY_DENIED", decision.reason());
        }

        return request;
    }

    /**
     * The workspace's own home region, for {@code context.workspace_region} in the CEL policy DSL.
     *
     * <p>Resolved from the repository rather than from {@code ChatRequest.metadata}, which is the
     * caller's own map from the request body: a rule such as {@code context.workspace_region ==
     * "eu"} must not be satisfiable by typing the value into the body.
     *
     * <p>Empty rather than a sentinel when there is no workspace, no repository, or no region on
     * the row, so an {@code == "eu"} rule fails closed and a {@code != "eu"} rule fires.
     */
    private Optional<String> resolveWorkspaceRegion(String workspaceId) {
        if (workspaceId == null || workspaceRepository.isEmpty()) {
            return Optional.empty();
        }
        return workspaceRepository.get().findById(workspaceId)
                .map(Workspace::getRegion)
                .filter(r -> !r.isBlank());
    }

    private void auditPolicyDenial(FilterContext ctx, PolicyContext policyCtx, PolicyDecision decision) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("policy_id", decision.policyId());
        payload.put("rule_id", decision.ruleId());
        payload.put("reason", decision.reason());
        payload.put("workspace_id", policyCtx.workspaceId());
        payload.put("api_key", Objects.requireNonNull(policyCtx.apiKey(),
                "a policy denial with no API key id behind it: the request was not authenticated"));   // the key id, not a secret
        payload.put("user_id", policyCtx.userId());

        auditWriter.write(new AuditEvent(
                Ids.newId(), Instant.now(),
                policyCtx.workspaceId(), "POLICY_DENIED", payload));
    }

}