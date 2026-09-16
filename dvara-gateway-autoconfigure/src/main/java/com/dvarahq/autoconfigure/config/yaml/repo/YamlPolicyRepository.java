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
package com.dvarahq.autoconfigure.config.yaml.repo;

import com.dvarahq.core.policy.Policy;
import com.dvarahq.core.policy.PolicyRepository;
import com.dvarahq.core.policy.PolicyVersion;

import java.util.List;
import java.util.Optional;

/**
 * Policies, served from {@code gateway.yaml}.
 *
 * <p>Read exactly once, by {@code DefaultPolicyEngine.rebuildIndex()} at bean construction; the
 * request path evaluates the compiled in-memory index and never comes back here. A policy that will
 * not compile is skipped and named rather than taking the others with it.
 *
 * <p>{@link #findByWorkspaceId} returns the workspace's own policies <b>and</b> the platform-global
 * ones — a policy with no {@code workspace:} applies to everyone, and returning only the scoped ones
 * would silently drop every operator-imposed rule.
 */
public class YamlPolicyRepository implements PolicyRepository {

    private final YamlConfigStore store;

    public YamlPolicyRepository(YamlConfigStore store) {
        this.store = store;
    }

    @Override
    public Optional<Policy> findById(String id) {
        return Optional.ofNullable(store.policiesById().get(id));
    }

    @Override
    public List<Policy> findAll() {
        return store.policies();
    }

    @Override
    public List<Policy> findByWorkspaceId(String workspaceId) {
        return store.policies().stream()
                .filter(p -> p.getWorkspaceId() == null || p.getWorkspaceId().equals(workspaceId))
                .toList();
    }

    @Override
    public Policy save(Policy policy) {
        throw YamlReadOnly.on("PolicyRepository.save");
    }

    @Override
    public boolean deleteById(String id) {
        throw YamlReadOnly.on("PolicyRepository.deleteById");
    }

    @Override
    public List<PolicyVersion> getVersionHistory(String policyId) {
        throw YamlReadOnly.on("PolicyRepository.getVersionHistory");
    }

    @Override
    public Optional<PolicyVersion> getVersion(String policyId, int version) {
        throw YamlReadOnly.on("PolicyRepository.getVersion");
    }
}