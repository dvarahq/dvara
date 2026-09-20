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
package com.dvarahq.providers.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Carrying the workspace with a call, and putting things back the way they were afterwards. */
class WorkspaceScopeTest {

    @AfterEach
    void clear() {
        // Nothing should leak, but if a test failed part-way the next one must not inherit it.
        WorkspaceScope.runWith(null, () -> { });
    }

    @Test
    void nothingIsInScopeUntilSomethingPutsItThere() {
        assertThat(WorkspaceScope.current()).isNull();
    }

    @Test
    void theWorkspaceIsVisibleInsideAndGoneAfter() {
        WorkspaceScope.runWith("ws-a", () ->
                assertThat(WorkspaceScope.current()).isEqualTo("ws-a"));

        assertThat(WorkspaceScope.current())
                .describedAs("a scope that outlived its call would put the next call on the wrong key")
                .isNull();
    }

    @Test
    void theseNestWithoutTheInnerOneStrippingTheOuter() {
        // They really do nest: opening a stream sets the workspace, and the resilience wrapper sets
        // it again on the thread it hands the call to. If the inner scope cleared on the way out,
        // the rest of the outer call would run with no workspace at all.
        WorkspaceScope.runWith("outer", () -> {
            WorkspaceScope.runWith("inner", () ->
                    assertThat(WorkspaceScope.current()).isEqualTo("inner"));
            assertThat(WorkspaceScope.current())
                    .describedAs("the outer scope survives the inner one ending")
                    .isEqualTo("outer");
        });
    }

    @Test
    void itIsRestoredEvenWhenTheCallFails() {
        assertThatThrownBy(() -> WorkspaceScope.runWith("ws-a", () -> {
            throw new IllegalStateException("upstream went away");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(WorkspaceScope.current())
                .describedAs("a failed call must not leave its workspace behind")
                .isNull();
    }

    @Test
    void aBlankWorkspaceIsTreatedAsNoWorkspace() {
        // "" is not a workspace anyone owns, and letting it through would mean looking up a
        // credential under an empty name rather than admitting we do not know.
        WorkspaceScope.runWith("   ", () -> assertThat(WorkspaceScope.current()).isNull());
        WorkspaceScope.runWith(null, () -> assertThat(WorkspaceScope.current()).isNull());
    }

    @Test
    void aValueSetOnOneThreadDoesNotLeakToAnother() throws Exception {
        WorkspaceScope.runWith("ws-a", () -> {
            try {
                Thread other = Thread.ofVirtual().start(() ->
                        assertThat(WorkspaceScope.current()).isNull());
                other.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @Test
    void theHandleFormPutsBackWhatWasThereBefore() {
        WorkspaceScope.runWith("outer", () -> {
            try (WorkspaceScope.Scope ignored = WorkspaceScope.open("inner")) {
                assertThat(WorkspaceScope.current()).isEqualTo("inner");
            }
            assertThat(WorkspaceScope.current()).isEqualTo("outer");
        });
    }
}
