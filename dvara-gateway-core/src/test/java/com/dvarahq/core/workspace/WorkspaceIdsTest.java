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

import com.dvarahq.core.id.Ids;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a workspace id may be, and the two values that cost something real.
 */
class WorkspaceIdsTest {

    @Test
    void aGeneratedIdIsAlwaysValid() {
        // Every path that mints a workspace id uses Ids.newId() and none of them validates; this is
        // what keeps that safe if the id format ever changes.
        for (int i = 0; i < 200; i++) {
            String id = Ids.newId();
            assertThat(WorkspaceIds.rejectionReason(id))
                    .as("generated id '%s'", id)
                    .isNull();
        }
    }

    @Test
    void theIdsOperatorsActuallyWriteAreAccepted() {
        // `default` is the file store's fallback; `e5-load` ships in this repository's own CI
        // bootstrap file. A rule that refused either would be a rule nobody could adopt.
        assertThat(WorkspaceIds.isValid("default")).isTrue();
        assertThat(WorkspaceIds.isValid("e5-load")).isTrue();
        assertThat(WorkspaceIds.isValid("acme.eu_west-1")).isTrue();
    }

    @Test
    void theSentinelIsRefusedByName() {
        // It is not a convention: two unique indexes are declared over
        // COALESCE(workspace_id, '__platform__'), and the credential cache and the plugin registry
        // both key on it. A workspace called this would hold the platform-default credential slot.
        assertThat(WorkspaceIds.rejectionReason("__platform__"))
                .contains("no workspace")
                .contains("platform-default");
    }

    @Test
    void theSentinelIsRefusedWhateverItsCase() {
        // The charset allows underscores, so this one has to be refused by name — and a different
        // case is the spelling somebody reaches for when the first is refused.
        assertThat(WorkspaceIds.isValid("__PLATFORM__")).isFalse();
        assertThat(WorkspaceIds.isValid("__Platform__")).isFalse();
    }

    @Test
    void aSeparatorIsRefused() {
        // The audit chain signs eventId|timestamp|workspaceId|eventType|payload|previousHash with
        // the fields unescaped, and workspaceId is the only one of the six an operator can type.
        // An id `A|B` with event type `C` signs identically to the id `A` with event type `B|C`.
        assertThat(WorkspaceIds.isValid("acme|evil")).isFalse();
        // The credential cache and the plugin registry both key on workspaceId + ":" + something.
        assertThat(WorkspaceIds.isValid("acme:evil")).isFalse();
    }

    @Test
    void theOtherShapesThatWouldTravelSomewhereUnwelcome() {
        assertThat(WorkspaceIds.isValid("../../etc/passwd")).isFalse();
        assertThat(WorkspaceIds.isValid("acme workspace")).isFalse();
        assertThat(WorkspaceIds.isValid("acme\nbeta")).isFalse();
        assertThat(WorkspaceIds.isValid("")).isFalse();
        assertThat(WorkspaceIds.isValid("   ")).isFalse();
        assertThat(WorkspaceIds.isValid(null)).isFalse();
    }

    @Test
    void anIdWiderThanTheColumnIsRefusedWithBothNumbers() {
        String tooLong = "a".repeat(WorkspaceIds.MAX_LENGTH + 1);

        assertThat(WorkspaceIds.rejectionReason(tooLong))
                .contains(String.valueOf(WorkspaceIds.MAX_LENGTH + 1))
                .contains(String.valueOf(WorkspaceIds.MAX_LENGTH));
        assertThat(WorkspaceIds.isValid("a".repeat(WorkspaceIds.MAX_LENGTH))).isTrue();
    }

    @Test
    void requireNamesTheIdAndTheReason() {
        assertThatThrownBy(() -> WorkspaceIds.require("acme|evil"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acme|evil")
                .hasMessageContaining("composite keys");
    }
}
