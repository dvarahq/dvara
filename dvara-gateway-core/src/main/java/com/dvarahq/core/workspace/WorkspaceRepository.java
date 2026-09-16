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
package com.dvarahq.core.workspace;

import com.dvarahq.core.util.Pages;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkspaceRepository {

    Optional<Workspace> findById(String id);

    List<Workspace> findAll();

    /**
     * Workspaces belonging to one team — the set a per-account meter sums over.
     *
     * <p>Default filters {@link #findAll()}; the JDBC repository overrides it with an indexed query,
     * because this sits behind a quota check on the request path.
     */
    default List<Workspace> findByTeamId(String teamId) {
        if (teamId == null || teamId.isBlank()) {
            return List.of();
        }
        return findAll().stream().filter(w -> teamId.equals(w.getTeamId())).toList();
    }

    Workspace save(Workspace workspace);

    boolean deleteById(String id);

    boolean existsById(String id);

    /**
     * One page of workspaces (newest first). Default slices {@link #findAll()} in
     * memory; the JDBC repo overrides with SQL {@code LIMIT/OFFSET}. Pair with
     * {@link #count()} for the total.
     */
    default List<Workspace> findPage(int limit, int offset) {
        return Pages.newestFirst(findAll(), Workspace::getCreatedAt, limit, offset);
    }

    /** Total workspace count (for the page count). JDBC repo overrides with {@code SELECT COUNT(*)}. */
    default long count() {
        return findAll().size();
    }

    /**
     * Workspaces whose workspace-admin-requested self-deletion is due:
     * {@code status = SUSPENDED}, {@code metadata.suspendedReason =
     * 'self_delete'}, and {@code metadata.selfDeletePurgeAt < now} (the
     * recoverable grace window has elapsed). The self-delete reaper
     * walks these and hard-purges them. Bounded query — never a full
     * workspace scan.
     *
     * <p>Default is a safe no-op so non-persistence / test
     * implementations don't have to implement it; the JDBC repository
     * (and the caching decorator that fronts it) override with the real
     * bounded query.
     */
    default List<Workspace> findSelfDeleteDue(Instant now, int batchSize) {
        return List.of();
    }
}