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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A workspace save that leaves the stored metadata alone, and one that refuses when the workspace
 * changed since it was read, on a store that implements only the original methods.
 */
class WorkspaceSaveWithoutMetadataTest {

    private static final Instant READ_AT = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    void aSaveWithoutMetadataKeepsAMetadataKeyWrittenSinceTheRead() {
        MapStore store = new MapStore();
        store.save(workspace("ws", "Old name", Map.of("a2a.delegation.mode", "observe"), READ_AT));
        Workspace read = store.findById("ws").orElseThrow();

        // Another writer changes one key between this caller's read and its write.
        store.save(workspace("ws", "Old name", Map.of("a2a.delegation.mode", "enforce"), READ_AT.plusSeconds(1)));

        read.setName("New name");
        store.saveWithoutMetadata(read);

        Workspace stored = store.findById("ws").orElseThrow();
        assertThat(stored.getName()).isEqualTo("New name");
        assertThat(stored.getMetadata()).containsEntry("a2a.delegation.mode", "enforce");
    }

    @Test
    void aSaveWithoutMetadataDoesNotChangeTheCallersObject() {
        MapStore store = new MapStore();
        store.save(workspace("ws", "Name", Map.of("k", "stored"), READ_AT));
        Workspace mine = workspace("ws", "Name", Map.of("k", "mine"), READ_AT);

        store.saveWithoutMetadata(mine);

        assertThat(mine.getMetadata()).containsEntry("k", "mine");
    }

    @Test
    void aNewWorkspaceIsSavedWithTheMetadataItCarries() {
        MapStore store = new MapStore();

        store.saveWithoutMetadata(workspace("new", "Fresh", Map.of("pii.action", "BLOCK"), READ_AT));

        assertThat(store.findById("new").orElseThrow().getMetadata()).containsEntry("pii.action", "BLOCK");
    }

    @Test
    void aSaveIfUnchangedRefusesWhenTheWorkspaceChangedSinceTheRead() {
        MapStore store = new MapStore();
        store.save(workspace("ws", "Name", Map.of("k", "v1"), READ_AT));
        Workspace read = store.findById("ws").orElseThrow();
        store.save(workspace("ws", "Name", Map.of("k", "v2"), READ_AT.plusSeconds(5)));

        read.setMetadata(Map.of("k", "v3"));

        assertThatThrownBy(() -> store.saveIfUnchanged(read, READ_AT))
                .isInstanceOf(ConcurrentWorkspaceChangeException.class)
                .hasMessageContaining("ws");
        assertThat(store.findById("ws").orElseThrow().getMetadata()).containsEntry("k", "v2");
    }

    @Test
    void aSaveIfUnchangedSavesWhenNothingChanged() {
        MapStore store = new MapStore();
        store.save(workspace("ws", "Name", Map.of("k", "v1"), READ_AT));
        Workspace read = store.findById("ws").orElseThrow();
        read.setMetadata(Map.of("k", "v3"));

        store.saveIfUnchanged(read, READ_AT);

        assertThat(store.findById("ws").orElseThrow().getMetadata()).containsEntry("k", "v3");
    }

    @Test
    void aSaveIfUnchangedRefusesAWorkspaceThatIsGone() {
        MapStore store = new MapStore();

        assertThatThrownBy(() -> store.saveIfUnchanged(workspace("gone", "x", Map.of(), READ_AT), READ_AT))
                .isInstanceOf(ConcurrentWorkspaceChangeException.class);
    }

    private static Workspace workspace(String id, String name, Map<String, Object> metadata, Instant updatedAt) {
        return Workspace.builder().id(id).name(name).status(WorkspaceStatus.ACTIVE)
                .metadata(new LinkedHashMap<>(metadata)).createdAt(READ_AT).updatedAt(updatedAt).build();
    }

    /** A store with only the original methods, so the interface's defaults are what is tested. */
    private static final class MapStore implements WorkspaceRepository {
        private final Map<String, Workspace> rows = new LinkedHashMap<>();

        @Override public Optional<Workspace> findById(String id) {
            Workspace w = rows.get(id);
            return w == null ? Optional.empty() : Optional.of(copy(w));
        }
        @Override public List<Workspace> findAll() { return new ArrayList<>(rows.values()); }
        @Override public Workspace save(Workspace workspace) { rows.put(workspace.getId(), copy(workspace)); return workspace; }
        @Override public boolean deleteById(String id) { return rows.remove(id) != null; }
        @Override public boolean existsById(String id) { return rows.containsKey(id); }

        private static Workspace copy(Workspace w) {
            return Workspace.builder().id(w.getId()).name(w.getName()).teamId(w.getTeamId()).status(w.getStatus())
                    .region(w.getRegion()).metadata(w.getMetadata() == null ? null : new LinkedHashMap<>(w.getMetadata()))
                    .settings(w.getSettings()).createdAt(w.getCreatedAt()).updatedAt(w.getUpdatedAt()).build();
        }
    }
}
