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
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.FilterOrder;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.WorkspaceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkspaceStatusFilterTest {

    private CountingWorkspaceRepository workspaces;
    private RecordingAuditWriter audit;
    private MutableClock clock;
    private WorkspaceStatusFilter filter;

    @BeforeEach
    void setUp() {
        workspaces = new CountingWorkspaceRepository();
        audit = new RecordingAuditWriter();
        clock = new MutableClock(Instant.parse("2026-05-15T12:00:00Z"));
        filter = new WorkspaceStatusFilter(workspaces, new StubObjectProvider<>(audit), clock);
    }

    @Test
    void order_runsBeforeTokenCap() {
        // Filter ordering is part of the design contract — WORKSPACE_STATUS
        // must fire before TOKEN_CAP so a suspended workspace doesn't pay
        // the cap-counter query cost.
        assertThat(filter.order()).isEqualTo(FilterOrder.WORKSPACE_STATUS_ENFORCEMENT);
        assertThat(filter.order()).isLessThan(FilterOrder.BUDGET_ENFORCEMENT);
        assertThat(filter.order()).isLessThan(FilterOrder.TOKEN_CAP_ENFORCEMENT);
    }

    @Test
    void noWorkspaceId_skipsCheck() {
        var ctx = FilterContext.builder().build();
        assertThatNoException().isThrownBy(() -> filter.preDispatch(req(), ctx));
        assertThat(workspaces.findByIdCalls).isZero();
        assertThat(audit.lastEvent.get()).isNull();
    }

    @Test
    void blankWorkspaceId_skipsCheck() {
        var ctx = FilterContext.builder().workspaceId("   ").build();
        assertThatNoException().isThrownBy(() -> filter.preDispatch(req(), ctx));
        assertThat(workspaces.findByIdCalls).isZero();
    }

    @Test
    void workspaceNotFound_backCompatPasses() {
        // Race condition (workspace deleted), dev fixture, integration test
        // setup. Treat missing workspace as not-suspended.
        var ctx = ctxFor("t-deleted");
        assertThatNoException().isThrownBy(() -> filter.preDispatch(req(), ctx));
        assertThat(audit.lastEvent.get()).isNull();
    }

    @Test
    void activeWorkspace_passes() {
        addWorkspace("t-active", WorkspaceStatus.ACTIVE, null);
        assertThatNoException().isThrownBy(() -> filter.preDispatch(req(), ctxFor("t-active")));
        assertThat(audit.lastEvent.get()).isNull();
    }

    @Test
    void suspendedWorkspace_blocksWith403() {
        addWorkspace("t-suspended", WorkspaceStatus.SUSPENDED, null);
        assertThatThrownBy(() -> filter.preDispatch(req(), ctxFor("t-suspended")))
                .isInstanceOf(WorkspaceSuspendedException.class)
                .hasMessageContaining("t-suspended")
                .hasMessageContaining("suspended");
    }

    @Test
    void suspendedWithReason_carriesReasonIntoException() {
        addWorkspace("t-policy", WorkspaceStatus.SUSPENDED, Map.of(
                "suspendedReason", "policy"));

        assertThatThrownBy(() -> filter.preDispatch(req(), ctxFor("t-policy")))
                .isInstanceOfSatisfying(WorkspaceSuspendedException.class, ex -> {
                    assertThat(ex.getReason()).isEqualTo("policy");
                    assertThat(ex.getCode()).isEqualTo("WORKSPACE_SUSPENDED");
                    assertThat(ex.getMessage()).contains("policy");
                });
    }

    @Test
    void suspendedWithoutReason_defaultsToManualInAudit() {
        // The exception reason is null when metadata didn't carry one,
        // but the audit-event payload normalises to "manual" so operations
        // dashboards always have a value to group by.
        addWorkspace("t-no-reason", WorkspaceStatus.SUSPENDED, null);
        assertThatThrownBy(() -> filter.preDispatch(req(), ctxFor("t-no-reason")))
                .isInstanceOfSatisfying(WorkspaceSuspendedException.class, ex ->
                        assertThat(ex.getReason()).isNull());

        var event = audit.lastEvent.get();
        assertThat(event).isNotNull();
        assertThat(event.eventType()).isEqualTo("WORKSPACE_SUSPENDED_BLOCK");
        assertThat(event.workspaceId()).isEqualTo("t-no-reason");
        assertThat(event.payload()).containsEntry("reason", "manual");
    }

    @Test
    void suspendedWithReason_payloadContainsReason() {
        addWorkspace("t-review", WorkspaceStatus.SUSPENDED, Map.of(
                "suspendedReason", "manual_review"));

        assertThatThrownBy(() -> filter.preDispatch(req(), ctxFor("t-review")))
                .isInstanceOf(WorkspaceSuspendedException.class);

        assertThat(audit.lastEvent.get().payload()).containsEntry("reason", "manual_review");
    }

    @Test
    void warmCache_skipsSecondLookup() {
        // First call cold-misses, fetches from repo. Second call within
        // the 60s TTL is a pure map hit.
        addWorkspace("t-warm", WorkspaceStatus.ACTIVE, null);
        filter.preDispatch(req(), ctxFor("t-warm"));
        filter.preDispatch(req(), ctxFor("t-warm"));
        filter.preDispatch(req(), ctxFor("t-warm"));
        assertThat(workspaces.findByIdCalls).isEqualTo(1);
    }

    @Test
    void cacheTtlExpiry_refetchesFromRepo() {
        addWorkspace("t-ttl", WorkspaceStatus.ACTIVE, null);
        filter.preDispatch(req(), ctxFor("t-ttl"));
        clock.advance(Duration.ofSeconds(61));
        filter.preDispatch(req(), ctxFor("t-ttl"));
        assertThat(workspaces.findByIdCalls).isEqualTo(2);
    }

    @Test
    void cacheKeyIsPerWorkspace() {
        addWorkspace("t-a", WorkspaceStatus.ACTIVE, null);
        addWorkspace("t-b", WorkspaceStatus.ACTIVE, null);
        filter.preDispatch(req(), ctxFor("t-a"));
        filter.preDispatch(req(), ctxFor("t-b"));
        assertThat(workspaces.findByIdCalls).isEqualTo(2);
    }

    @Test
    void unsuspendThenReadAfterCacheExpiry_unblocks() {
        // Operator manually un-suspends; after cache expiry the next
        // request passes.
        addWorkspace("t-unsuspend", WorkspaceStatus.SUSPENDED, null);
        assertThatThrownBy(() -> filter.preDispatch(req(), ctxFor("t-unsuspend")))
                .isInstanceOf(WorkspaceSuspendedException.class);

        var updated = workspaces.findById("t-unsuspend").orElseThrow();
        updated.setStatus(WorkspaceStatus.ACTIVE);
        clock.advance(Duration.ofSeconds(61));

        assertThatNoException().isThrownBy(() -> filter.preDispatch(req(), ctxFor("t-unsuspend")));
    }

    @Test
    void auditWriterFailure_doesNotMaskBlock() {
        // Audit-write throwing must NOT prevent the customer-facing 403.
        var failing = new RecordingAuditWriter() {
            @Override public void write(AuditEvent event) {
                lastEvent.set(event);
                throw new RuntimeException("audit DB down");
            }
        };
        var f = new WorkspaceStatusFilter(workspaces, new StubObjectProvider<>(failing), clock);
        addWorkspace("t-audit-fail", WorkspaceStatus.SUSPENDED, null);

        assertThatThrownBy(() -> f.preDispatch(req(), ctxFor("t-audit-fail")))
                .isInstanceOf(WorkspaceSuspendedException.class);
        // Audit was attempted before the throw.
        assertThat(failing.lastEvent.get()).isNotNull();
    }

    @Test
    void auditWriterUnavailable_stillBlocks() {
        var f = new WorkspaceStatusFilter(workspaces, new StubObjectProvider<>(null), clock);
        addWorkspace("t-no-audit", WorkspaceStatus.SUSPENDED, null);

        assertThatThrownBy(() -> f.preDispatch(req(), ctxFor("t-no-audit")))
                .isInstanceOf(WorkspaceSuspendedException.class);
    }

    @Test
    void emitsBlockEventEveryRequest_noDedup() {
        // Operations needs the rate of blocked requests, not just one
        // "block started" event. Each blocked request must produce its
        // own audit row.
        addWorkspace("t-dedup", WorkspaceStatus.SUSPENDED, null);
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> filter.preDispatch(req(), ctxFor("t-dedup")))
                    .isInstanceOf(WorkspaceSuspendedException.class);
        }
        assertThat(audit.writeCount.get()).isEqualTo(3);
    }

    private static ChatRequest req() {
        return ChatRequest.builder().model("gpt-4o-mini").build();
    }

    private static FilterContext ctxFor(String workspaceId) {
        return FilterContext.builder().workspaceId(workspaceId).build();
    }

    private void addWorkspace(String id, WorkspaceStatus status, Map<String, Object> metadata) {
        workspaces.save(Workspace.builder()
                .id(id)
                .name(id)
                .status(status)
                .metadata(metadata == null ? new HashMap<>() : new HashMap<>(metadata))
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
    }

    /** In-memory workspace repo that counts findById hits for cache tests. */
    static class CountingWorkspaceRepository implements WorkspaceRepository {
        private final List<Workspace> store = new ArrayList<>();
        int findByIdCalls = 0;

        @Override
        public Optional<Workspace> findById(String id) {
            findByIdCalls++;
            return store.stream().filter(t -> id.equals(t.getId())).findFirst();
        }
        @Override public List<Workspace> findAll() { return List.copyOf(store); }
        @Override public Workspace save(Workspace t) { store.add(t); return t; }
        @Override public boolean deleteById(String id) { return store.removeIf(t -> id.equals(t.getId())); }
        @Override public boolean existsById(String id) { return store.stream().anyMatch(t -> id.equals(t.getId())); }
    }

    static class RecordingAuditWriter implements AuditWriter {
        final AtomicReference<AuditEvent> lastEvent = new AtomicReference<>();
        final AtomicInteger writeCount = new AtomicInteger();

        @Override
        public void write(AuditEvent event) {
            lastEvent.set(event);
            writeCount.incrementAndGet();
        }
    }

    static class StubObjectProvider<T> implements ObjectProvider<T> {
        private final AtomicReference<T> instance = new AtomicReference<>();
        StubObjectProvider(T value) { instance.set(value); }
        @Override public T getObject(Object... args) { return instance.get(); }
        @Override public T getObject() { return instance.get(); }
        @Override public T getIfAvailable() { return instance.get(); }
        @Override public T getIfUnique() { return instance.get(); }
    }

    static class MutableClock extends Clock {
        private final AtomicLong nowMillis;
        MutableClock(Instant start) { this.nowMillis = new AtomicLong(start.toEpochMilli()); }
        void advance(Duration d) { nowMillis.addAndGet(d.toMillis()); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(nowMillis.get()); }
        @Override public long millis() { return nowMillis.get(); }
    }
}