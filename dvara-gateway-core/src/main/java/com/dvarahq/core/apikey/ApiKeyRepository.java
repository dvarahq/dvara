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
package com.dvarahq.core.apikey;

import com.dvarahq.core.util.Pages;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository {

    Optional<ApiKey> findById(String id);

    Optional<ApiKey> findByKeyHash(String keyHash);

    List<ApiKey> findByWorkspaceId(String workspaceId);

    /**
     * One page of API keys for a workspace (newest first). The default slices
     * {@link #findByWorkspaceId} in memory; a database-backed implementation can override it with
     * SQL {@code LIMIT/OFFSET}. Pair with {@link #countMatching}.
     */
    default List<ApiKey> findPage(String workspaceId, int limit, int offset) {
        return Pages.newestFirst(findByWorkspaceId(workspaceId), ApiKey::getCreatedAt, limit, offset);
    }

    /** Total API keys for a workspace (for the page count). */
    default long countMatching(String workspaceId) {
        return findByWorkspaceId(workspaceId).size();
    }

    List<ApiKey> findAll();

    ApiKey save(ApiKey apiKey);

    boolean revokeById(String id);

    boolean deleteById(String id);
}