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
package com.dvarahq.core.prompt;

import com.dvarahq.core.util.Pages;
import java.util.List;
import java.util.Optional;

public interface PromptTemplateRepository {

    Optional<PromptTemplate> findById(String id);

    List<PromptTemplate> findAll();

    List<PromptTemplate> findByWorkspaceId(String workspaceId);

    /**
     * One page of prompt templates (newest first), optionally scoped to a workspace
     * ({@code workspaceId == null/blank} = all workspaces). Default slices in memory;
     * the JDBC repo overrides with SQL {@code LIMIT/OFFSET}. Pair with {@link #countMatching}.
     */
    default List<PromptTemplate> findPage(String workspaceId, int limit, int offset) {
        List<PromptTemplate> matched = workspaceId != null && !workspaceId.isBlank()
                ? findByWorkspaceId(workspaceId) : findAll();
        return Pages.newestFirst(matched, PromptTemplate::getCreatedAt, limit, offset);
    }

    /** Total prompt templates matching the same workspace scope as {@link #findPage} (for the page count). */
    default long countMatching(String workspaceId) {
        return workspaceId != null && !workspaceId.isBlank() ? findByWorkspaceId(workspaceId).size() : findAll().size();
    }

    PromptTemplate save(PromptTemplate template);

    boolean deleteById(String id);

    List<PromptTemplateVersion> getVersionHistory(String templateId);

    Optional<PromptTemplateVersion> getVersion(String templateId, int version);
}