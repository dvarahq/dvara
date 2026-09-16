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
package com.dvarahq.core.audit;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two factories, and which one loses a workspace.
 *
 * <p>An event written with no workspace never reaches that workspace again: an audit query by
 * workspace reads {@code findByWorkspaceId}, so a null there means the record is absent from the
 * one place its subject looks.
 */
class AuditEventTest {

    @Test
    void ofIsThePlatformScopedForm() {
        AuditEvent event = AuditEvent.of("CONFIG_RELOADED", Map.of("sections", 0));

        assertThat(event.workspaceId())
                .as("the null is the whole difference between this and the constructor")
                .isNull();
        assertThat(event.eventType()).isEqualTo("CONFIG_RELOADED");
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void forWorkspaceCarriesTheWorkspace() {
        AuditEvent event = AuditEvent.forWorkspace("acme", "PAT_CREATED", Map.of("token_name", "ci"));

        assertThat(event.workspaceId()).isEqualTo("acme");
        assertThat(event.eventType()).isEqualTo("PAT_CREATED");
    }

    @Test
    void theTwoFactoriesMintDistinctIds() {
        // Ids.newId() per call, not a shared one: two events written in the same millisecond are
        // still two rows, and a duplicate primary key on audit_events aborts the whole transaction.
        AuditEvent a = AuditEvent.forWorkspace("acme", "PAT_CREATED", Map.of());
        AuditEvent b = AuditEvent.forWorkspace("acme", "PAT_CREATED", Map.of());

        assertThat(a.eventId()).isNotEqualTo(b.eventId());
    }
}
