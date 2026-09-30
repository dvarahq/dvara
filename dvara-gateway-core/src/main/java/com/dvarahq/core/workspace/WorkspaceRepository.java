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
import java.util.Objects;
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

    /**
     * Writes the whole workspace, {@link Workspace#getMetadata() metadata} included. A caller that
     * read the workspace, changed one field and saves it puts back every metadata key as it read
     * it, over any key written in between; {@link #saveWithoutMetadata} and {@link #saveIfUnchanged}
     * are the saves that cannot do that.
     */
    Workspace save(Workspace workspace);

    /**
     * Writes every field of the workspace except its metadata, which stays as stored. For an editor
     * that changes a workspace's own fields while metadata keys are written elsewhere one at a time:
     * a key written between this caller's read and its save is kept, not reverted.
     *
     * <p>A workspace that is not stored yet is created with the metadata it carries, since there is
     * nothing stored to keep. The caller's object is not changed.</p>
     *
     * <p>The default reads the stored metadata and then saves, so a key written between those two
     * steps can still be lost. A store that can write the other fields in one statement overrides it.</p>
     *
     * @return the workspace as saved, with the stored metadata
     */
    default Workspace saveWithoutMetadata(Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        Optional<Workspace> stored = workspace.getId() == null ? Optional.empty() : findById(workspace.getId());
        if (stored.isEmpty()) {
            return save(workspace);
        }
        return save(copyWithMetadata(workspace, stored.get().getMetadata()));
    }

    /**
     * Writes the whole workspace only if it has not changed since the caller read it: its stored
     * {@link Workspace#getUpdatedAt() updatedAt} must still equal {@code readUpdatedAt}. Otherwise
     * nothing is written and {@link ConcurrentWorkspaceChangeException} names the workspace, so two
     * saves of the same key at once give one success and one refusal, never a silent revert.
     *
     * <p>The default compares and then saves, so a write between those two steps is not detected. A
     * store that can compare and write in one statement overrides it.</p>
     *
     * @throws ConcurrentWorkspaceChangeException if the workspace changed or is gone
     */
    default Workspace saveIfUnchanged(Workspace workspace, Instant readUpdatedAt) {
        Objects.requireNonNull(workspace, "workspace");
        Workspace stored = workspace.getId() == null ? null : findById(workspace.getId()).orElse(null);
        if (stored == null || !Objects.equals(stored.getUpdatedAt(), readUpdatedAt)) {
            throw new ConcurrentWorkspaceChangeException(workspace.getId());
        }
        return save(workspace);
    }

    private static Workspace copyWithMetadata(Workspace w, java.util.Map<String, Object> metadata) {
        return Workspace.builder()
                .id(w.getId())
                .name(w.getName())
                .teamId(w.getTeamId())
                .status(w.getStatus())
                .region(w.getRegion())
                .metadata(metadata)
                .settings(w.getSettings())
                .createdAt(w.getCreatedAt())
                .updatedAt(w.getUpdatedAt())
                .build();
    }

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