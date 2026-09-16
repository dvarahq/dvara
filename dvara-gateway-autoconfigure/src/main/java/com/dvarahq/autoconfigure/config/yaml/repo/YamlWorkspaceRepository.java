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

import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;

import java.util.List;
import java.util.Optional;

/**
 * Workspaces, served from {@code gateway.yaml}.
 *
 * <p>The one that carries the most weight, because {@code Workspace.metadata} is where per-workspace
 * enforcement is configured — PII action, guardrail strictness, rate-limit overrides, priority tier.
 * The eight typed settings repositories are {@code ObjectProvider}s that fall back to that
 * map when absent, and in this build they are absent, so this is the whole per-workspace governance
 * surface and none of them need a file-backed implementation.
 */
public class YamlWorkspaceRepository implements WorkspaceRepository {

    private final YamlConfigStore store;

    public YamlWorkspaceRepository(YamlConfigStore store) {
        this.store = store;
    }

    @Override
    public Optional<Workspace> findById(String id) {
        return Optional.ofNullable(store.workspacesById().get(id));
    }

    @Override
    public List<Workspace> findAll() {
        return store.workspaces();
    }

    @Override
    public boolean existsById(String id) {
        return store.workspacesById().containsKey(id);
    }

    @Override
    public Workspace save(Workspace workspace) {
        throw YamlReadOnly.on("WorkspaceRepository.save");
    }

    @Override
    public boolean deleteById(String id) {
        throw YamlReadOnly.on("WorkspaceRepository.deleteById");
    }
}