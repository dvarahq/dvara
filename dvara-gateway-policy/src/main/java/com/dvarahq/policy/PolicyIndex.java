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

import com.dvarahq.core.policy.PolicyStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class PolicyIndex {

    private final Map<String, List<CompiledPolicy>> byWorkspace;
    private final List<CompiledPolicy> globalPolicies;

    private PolicyIndex(Map<String, List<CompiledPolicy>> byWorkspace, List<CompiledPolicy> globalPolicies) {
        this.byWorkspace = byWorkspace;
        this.globalPolicies = globalPolicies;
    }

    public static PolicyIndex build(List<CompiledPolicy> policies) {
        List<CompiledPolicy> active = policies.stream()
                .filter(p -> p.status() == PolicyStatus.ACTIVE)
                .toList();

        List<CompiledPolicy> global = active.stream()
                .filter(p -> p.workspaceId() == null || p.workspaceId().isBlank())
                .toList();

        Map<String, List<CompiledPolicy>> byWorkspace = active.stream()
                .filter(p -> p.workspaceId() != null && !p.workspaceId().isBlank())
                .collect(Collectors.groupingBy(CompiledPolicy::workspaceId));

        return new PolicyIndex(byWorkspace, global);
    }

    public List<CompiledPolicy> policiesFor(String workspaceId) {
        List<CompiledPolicy> result = new ArrayList<>(globalPolicies);
        if (workspaceId != null && byWorkspace.containsKey(workspaceId)) {
            result.addAll(byWorkspace.get(workspaceId));
        }
        return result;
    }

    public List<CompiledRule> rulesFor(String workspaceId) {
        return policiesFor(workspaceId).stream()
                .flatMap(p -> p.rules().stream()
                        .map(r -> new WorkspaceScopedRule(p, r)))
                .sorted(Comparator.comparingInt(tsr -> tsr.rule.priority()))
                .map(tsr -> tsr.rule)
                .toList();
    }

    public List<PolicyScopedRule> scopedRulesFor(String workspaceId) {
        return policiesFor(workspaceId).stream()
                .flatMap(p -> p.rules().stream()
                        .map(r -> new PolicyScopedRule(p, r)))
                .sorted(Comparator.comparingInt(psr -> psr.rule().priority()))
                .toList();
    }

    public record PolicyScopedRule(CompiledPolicy policy, CompiledRule rule) {}

    private record WorkspaceScopedRule(CompiledPolicy policy, CompiledRule rule) {}
}