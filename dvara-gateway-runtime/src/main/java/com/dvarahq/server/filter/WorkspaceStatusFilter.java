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
package com.dvarahq.server.filter;

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.WorkspaceSuspendedException;
import com.dvarahq.core.filter.ChatFilter;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.FilterOrder;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.WorkspaceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rejects requests for any workspace whose status is {@link WorkspaceStatus#SUSPENDED} with HTTP
 * 403 {@code WORKSPACE_SUSPENDED}. The status is set in the workspace store, by editing the
 * workspace's {@code status} in a file-backed deployment or by an operator elsewhere.
 *
 * <p>Runs at {@link FilterOrder#WORKSPACE_STATUS_ENFORCEMENT}, ahead of the budget and token-cap
 * slots and the policy / PII / guardrail chain, so a suspended workspace is rejected before any of
 * that work is done.
 *
 * <p>The status is read from the {@link WorkspaceRepository} on every request, so a suspension, and
 * lifting one, applies on the next request. This filter keeps no copy of its own: caching the
 * workspace is the repository's job. A repository that caches invalidates on a change; one that does
 * not is read from memory. A private copy here with a time-to-live delayed every suspension by that
 * long, and nothing could clear it early. When the lookup misses, the filter lets the request
 * through. Each block emits a {@code WORKSPACE_SUSPENDED_BLOCK} audit event; the audit write is
 * best-effort and never changes the 403.
 */
@Component
public class WorkspaceStatusFilter implements ChatFilter {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceStatusFilter.class);
    static final String SUSPEND_REASON_KEY = "suspendedReason";
    static final String DEFAULT_REASON = "manual";

    private final WorkspaceRepository workspaces;
    private final ObjectProvider<AuditWriter> auditWriter;
    private final Clock clock;

    @Autowired
    public WorkspaceStatusFilter(WorkspaceRepository workspaces,
                               ObjectProvider<AuditWriter> auditWriter) {
        this(workspaces, auditWriter, Clock.systemUTC());
    }

    /** Test-only — pass an injectable clock for the audit event's timestamp. */
    WorkspaceStatusFilter(WorkspaceRepository workspaces,
                        ObjectProvider<AuditWriter> auditWriter,
                        Clock clock) {
        this.workspaces = workspaces;
        this.auditWriter = auditWriter;
        this.clock = clock;
    }

    @Override public int order() { return FilterOrder.WORKSPACE_STATUS_ENFORCEMENT; }

    @Override
    public ChatRequest preDispatch(ChatRequest request, FilterContext ctx) {
        var workspaceId = ctx.getWorkspaceId();
        if (workspaceId == null || workspaceId.isBlank()) {
            // A request with no workspace was not authenticated, and a suspension it could not be
            // checked against must not be the reason it is served.
            throw new IllegalStateException("A request reached the workspace status check with no"
                    + " workspace: it was not authenticated. ApiKeyAuthFilter did not run before it.");
        }

        var snapshot = snapshotFor(workspaceId);
        if (snapshot == null || snapshot.status() != WorkspaceStatus.SUSPENDED) {
            return request;
        }

        var reason = snapshot.reason();
        emitBlockAuditEvent(workspaceId, reason);
        throw new WorkspaceSuspendedException(
                "Workspace " + workspaceId + " has been suspended"
                        + (reason == null ? "" : " (reason: " + reason + ")")
                        + ". Contact the operator of this gateway if you believe this is in error.",
                reason);
    }

    /** The workspace's status and optional suspension reason, read from the repository. */
    Snapshot snapshotFor(String workspaceId) {
        var workspace = workspaces.findById(workspaceId).orElse(null);
        if (workspace == null) {
            // Treat missing workspace as not-suspended — request proceeds,
            // downstream filters / repositories handle the not-found
            // case appropriately.
            return new Snapshot(null, null);
        }
        return new Snapshot(workspace.getStatus(), reasonFor(workspace));
    }

    private static String reasonFor(Workspace workspace) {
        var metadata = workspace.getMetadata();
        if (metadata == null) return null;
        var raw = metadata.get(SUSPEND_REASON_KEY);
        if (raw instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    private void emitBlockAuditEvent(String workspaceId, String reason) {
        var writer = auditWriter.getIfAvailable();
        if (writer == null) return;
        try {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("reason", reason == null ? DEFAULT_REASON : reason);
            writer.write(new AuditEvent(
                    Ids.newId(),
                    Instant.now(clock),
                    workspaceId,
                    "WORKSPACE_SUSPENDED_BLOCK",
                    Map.copyOf(payload)));
        } catch (RuntimeException e) {
            // Best-effort audit — the 403 is the customer-visible signal.
            log.debug("Failed to emit WORKSPACE_SUSPENDED_BLOCK for workspace {}: {}",
                    workspaceId, e.getMessage());
        }
    }

    record Snapshot(WorkspaceStatus status, String reason) {}
}