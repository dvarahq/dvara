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
package com.dvarahq.core.policy;

import com.dvarahq.core.util.Pages;
import java.util.List;
import java.util.Optional;

public interface PolicyRepository {

    Optional<Policy> findById(String id);

    List<Policy> findAll();

    List<Policy> findByWorkspaceId(String workspaceId);

    /**
     * One page of policies, newest first, optionally scoped to a workspace.
     *
     * <p>The default sorts before slicing; the JDBC repository orders in SQL instead. Ordering is
     * not cosmetic for a page: paging an unordered source can skip a row or serve it twice if the
     * underlying order shifts between two requests. A null {@code createdAt} sorts last rather than
     * throwing.
     *
     * <p>Returns a copy, so a store that returns its own list cannot change the caller's page
     * under it.
     */
    default List<Policy> findPage(String workspaceId, int limit, int offset) {
        List<Policy> matched = workspaceId != null && !workspaceId.isBlank()
                ? findByWorkspaceId(workspaceId) : findAll();
        return Pages.newestFirst(matched, Policy::getCreatedAt, limit, offset);
    }

    /** Total policies matching the same workspace scope as {@link #findPage}. */
    default long countMatching(String workspaceId) {
        return workspaceId != null && !workspaceId.isBlank() ? findByWorkspaceId(workspaceId).size() : findAll().size();
    }

    Policy save(Policy policy);

    boolean deleteById(String id);

    List<PolicyVersion> getVersionHistory(String policyId);

    Optional<PolicyVersion> getVersion(String policyId, int version);
}