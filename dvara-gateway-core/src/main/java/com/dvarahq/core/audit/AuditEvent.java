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

import com.dvarahq.core.id.Ids;
import java.time.Instant;
import java.util.Map;

public record AuditEvent(
        String eventId,
        Instant timestamp,
        String workspaceId,
        String eventType,
        Map<String, Object> payload) {

    /**
     * A platform-scoped event: one that belongs to the installation rather than to any one
     * workspace, such as a signing key being seeded or a model drifting.
     *
     * <p>The workspace is {@code null}. Per-workspace audit views read by workspace id, so an event
     * about a workspace written through this method never appears in them. For anything a workspace
     * did, or that happened to a workspace, use {@link #forWorkspace(String, String, Map)}.
     */
    public static AuditEvent of(String eventType, Map<String, Object> payload) {
        return new AuditEvent(
                Ids.newId(),
                Instant.now(),
                null,
                eventType,
                payload);
    }

    /**
     * An event belonging to one workspace; use it whenever a workspace is in scope.
     *
     * <p>Not an overload of {@code of}: two adjacent {@code String} parameters would let a swapped
     * workspace id and event type be signed and stored unnoticed. The argument order follows the
     * record's own.
     */
    public static AuditEvent forWorkspace(String workspaceId, String eventType,
                                          Map<String, Object> payload) {
        return new AuditEvent(
                Ids.newId(),
                Instant.now(),
                workspaceId,
                eventType,
                payload);
    }
}