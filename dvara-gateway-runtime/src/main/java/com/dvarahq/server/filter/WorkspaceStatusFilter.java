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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rejects requests for any workspace whose status is {@link WorkspaceStatus#SUSPENDED} with HTTP
 * 403 {@code WORKSPACE_SUSPENDED}. The status is set in the workspace store, by editing the
 * workspace's {@code status} in a file-backed deployment or by an operator elsewhere.
 *
 * <p>Runs at {@link FilterOrder#WORKSPACE_STATUS_ENFORCEMENT}, ahead of the budget and token-cap
 * slots and the policy / PII / guardrail chain, so a suspended workspace is rejected before any of
 * that work is done.
 *
 * <p>A per-workspace snapshot with a 60-second TTL keeps the {@link WorkspaceRepository#findById}
 * round-trip off the hot path. When the request carries no workspace, or the lookup misses, the
 * filter lets the request through. Each block emits a {@code WORKSPACE_SUSPENDED_BLOCK} audit
 * event; the audit write is best-effort and never changes the 403.
 */
@Component
public class WorkspaceStatusFilter implements ChatFilter {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceStatusFilter.class);
    private static final long CACHE_TTL_MILLIS = 60_000L;
    static final String SUSPEND_REASON_KEY = "suspendedReason";
    static final String DEFAULT_REASON = "manual";

    private final WorkspaceRepository workspaces;
    private final ObjectProvider<AuditWriter> auditWriter;
    private final Clock clock;
    private final ConcurrentHashMap<String, CachedSnapshot> cache = new ConcurrentHashMap<>();

    @Autowired
    public WorkspaceStatusFilter(WorkspaceRepository workspaces,
                               ObjectProvider<AuditWriter> auditWriter) {
        this(workspaces, auditWriter, Clock.systemUTC());
    }

    /** Test-only — pass an injectable clock to drive the TTL window. */
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
            // Anonymous / keyless data-plane path. No workspace to check.
            return request;
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

    /**
     * Look up the workspace's status (and optional suspension reason),
     * with a 60-second TTL cache. Cold-miss pays the
     * {@link WorkspaceRepository#findById} round-trip; warm hits are a
     * single map lookup.
     */
    CachedSnapshot snapshotFor(String workspaceId) {
        long nowMillis = clock.millis();
        var hit = cache.get(workspaceId);
        if (hit != null && nowMillis - hit.fetchedAtMillis() < CACHE_TTL_MILLIS) {
            return hit;
        }

        var workspace = workspaces.findById(workspaceId).orElse(null);
        CachedSnapshot snapshot;
        if (workspace == null) {
            // Treat missing workspace as not-suspended — request proceeds,
            // downstream filters / repositories handle the not-found
            // case appropriately.
            snapshot = new CachedSnapshot(null, null, nowMillis);
        } else {
            snapshot = new CachedSnapshot(
                    workspace.getStatus(),
                    reasonFor(workspace),
                    nowMillis);
        }
        cache.put(workspaceId, snapshot);
        return snapshot;
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

    /** Test-only: clear the cache so a follow-up call hits the repository. */
    void clearCache() {
        cache.clear();
    }

    record CachedSnapshot(WorkspaceStatus status, String reason, long fetchedAtMillis) {}
}