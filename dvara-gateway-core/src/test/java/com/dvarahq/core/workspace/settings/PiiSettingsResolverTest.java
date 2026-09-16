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
package com.dvarahq.core.workspace.settings;

import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The PII half of the precedence rule. Once a workspace has a typed row, editing the metadata key
 * has no effect and raises no error: detection still runs and the action stays where the typed row
 * put it.
 *
 * <p>The other half is the class's own promise: never throws, never returns null, on the request path
 * of every scanned request. <b>Absent is not empty</b>: a repository with no row, and a repository
 * that is not wired at all, both fall through to the map, and neither may be read as "this workspace
 * turned everything off".</p>
 */
class PiiSettingsResolverTest {

    private static final class FakeSettings implements PiiSettingsRepository {
        private PiiSettings row;
        int reads;

        FakeSettings holding(PiiSettings settings) { this.row = settings; return this; }

        @Override public Optional<PiiSettings> findByWorkspaceId(String workspaceId) {
            reads++;
            return Optional.ofNullable(row);
        }
        @Override public List<PiiSettings> findAll() { return row == null ? List.of() : List.of(row); }
        @Override public PiiSettings save(PiiSettings settings) { row = settings; return settings; }
        @Override public List<String> workspaceIdsByAction(PiiAction action) {
            return row != null && action == row.action() ? List.of(row.workspaceId()) : List.of();
        }
    }

    private static final class FakeWorkspaces implements WorkspaceRepository {
        private final List<Workspace> rows = new ArrayList<>();
        int reads;

        FakeWorkspaces holding(Workspace workspace) { rows.add(workspace); return this; }

        @Override public Optional<Workspace> findById(String id) {
            reads++;
            return rows.stream().filter(w -> id.equals(w.getId())).findFirst();
        }
        @Override public List<Workspace> findAll() { return List.copyOf(rows); }
        @Override public Workspace save(Workspace workspace) { rows.add(workspace); return workspace; }
        @Override public boolean deleteById(String id) { return rows.removeIf(w -> id.equals(w.getId())); }
        @Override public boolean existsById(String id) { return findById(id).isPresent(); }
    }

    private final FakeSettings typed = new FakeSettings();
    private final FakeWorkspaces workspaces = new FakeWorkspaces();

    private static final Map<String, Object> METADATA =
            Map.of("pii.enabled", true, "pii.action", "LOG");

    private static Workspace workspace(String id, Map<String, Object> metadata) {
        return Workspace.builder().id(id).name(id).metadata(metadata).build();
    }

    private static PiiSettings stored(String id, PiiAction action) {
        return new PiiSettings(id, true, action, null, null, null, Map.of());
    }

    // --- precedence --------------------------------------------------------------------------------

    /**
     * The typed row wins, and the map is not even read, which is why editing it afterwards does
     * nothing and reports nothing.
     */
    @Test
    void theTypedStoreWinsAndTheMapIsNeverRead() {
        PiiSettings resolved = new PiiSettingsResolver(typed.holding(stored("t1", PiiAction.BLOCK)),
                workspaces.holding(workspace("t1", METADATA))).resolve("t1");

        assertThat(resolved.action()).isEqualTo(PiiAction.BLOCK);
        assertThat(workspaces.reads).as("the losing store is not consulted at all").isZero();
    }

    @Test
    void theMetadataMapIsUsedWhenThereIsNoTypedRow() {
        PiiSettings resolved = new PiiSettingsResolver(typed, workspaces.holding(workspace("t1", METADATA)))
                .resolve("t1");

        assertThat(resolved.enabled()).isTrue();
        assertThat(resolved.action()).isEqualTo(PiiAction.LOG);
    }

    // --- absent is not empty ------------------------------------------------------------------------

    @Test
    void withNoTypedStoreWiredItStillReadsTheMap() {
        PiiSettings resolved = new PiiSettingsResolver(null, workspaces.holding(workspace("t1", METADATA)))
                .resolve("t1");

        assertThat(resolved.action())
                .as("a pod without the typed store must not read as 'everything off'")
                .isEqualTo(PiiAction.LOG);
    }

    @Test
    void withNoStoresAtAllItIsUnsetRatherThanAnException() {
        assertThat(new PiiSettingsResolver(null, null).resolve("t1").isUnset()).isTrue();
    }

    @Test
    void withNoWorkspaceRepositoryAndNoTypedRowItIsUnset() {
        assertThat(new PiiSettingsResolver(typed, null).resolve("t1").isUnset()).isTrue();
    }

    @Test
    void anUnknownWorkspaceIsUnsetRatherThanNull() {
        PiiSettings resolved = new PiiSettingsResolver(typed, workspaces).resolve("t1");

        assertThat(resolved).isNotNull();
        assertThat(resolved.isUnset()).isTrue();
        assertThat(resolved.workspaceId()).isEqualTo("t1");
    }

    @Test
    void aWorkspaceWithNoMetadataIsUnset() {
        assertThat(new PiiSettingsResolver(typed, workspaces.holding(workspace("t1", null)))
                .resolve("t1").isUnset()).isTrue();
    }

    @Test
    void aNullOrBlankWorkspaceIdIsUnsetAndTouchesNoStore() {
        PiiSettingsResolver resolver = new PiiSettingsResolver(typed, workspaces);

        assertThat(resolver.resolve(null).isUnset()).isTrue();
        assertThat(resolver.resolve("").isUnset()).isTrue();
        assertThat(resolver.resolve("   ").isUnset()).isTrue();

        assertThat(typed.reads).isZero();
        assertThat(workspaces.reads).isZero();
    }

    /** Same precedence, same order, same fallbacks as its sibling — worth asserting side by side. */
    @Test
    void itsPrecedenceMatchesTheRateLimitResolver() {
        FakeSettings piiStore = new FakeSettings().holding(stored("t1", PiiAction.BLOCK));
        FakeWorkspaces shared = new FakeWorkspaces().holding(workspace("t1", METADATA));

        assertThat(new PiiSettingsResolver(piiStore, shared).resolve("t1").action())
                .isEqualTo(PiiAction.BLOCK);
        assertThat(shared.reads).isZero();
    }
}