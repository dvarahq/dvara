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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-request workspace memo.
 *
 * <p>The saving is easy; the correctness is the part worth testing. A memo that serves a stale workspace
 * inside a request, or that survives a request, would be a worse bug than the ten redundant reads it
 * removes — so most of what is asserted here is about the memo <em>not</em> applying.
 */
class WorkspaceLookupScopeTest {

    @AfterEach
    void closeScope() {
        WorkspaceLookupScope.close();
    }

    private static Workspace workspace(String id) {
        Workspace t = new Workspace();
        t.setId(id);
        return t;
    }

    @Test
    void oneLoadPerWorkspaceWithinAScope() {
        AtomicInteger loads = new AtomicInteger();
        WorkspaceLookupScope.open();

        for (int i = 0; i < 20; i++) {
            var found = WorkspaceLookupScope.memoize("acme", id -> {
                loads.incrementAndGet();
                return Optional.of(workspace(id));
            });
            assertThat(found).isPresent();
        }

        // The common case: about twenty components each asking once. Asserting the load count rather
        // than elapsed time, because a memo that is merely faster still queries.
        assertThat(loads).hasValue(1);
    }

    @Test
    void withoutAScopeEveryCallLoads() {
        // Schedulers, the streaming tail on another virtual thread, and direct unit tests all run
        // here. Pass-through is the deliberate behaviour: a memo that guessed at its own lifetime
        // would be a cache with an undefined expiry.
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            WorkspaceLookupScope.memoize("acme", id -> {
                loads.incrementAndGet();
                return Optional.of(workspace(id));
            });
        }

        assertThat(WorkspaceLookupScope.isActive()).isFalse();
        assertThat(loads).hasValue(5);
    }

    @Test
    void aWriteInsideTheRequestIsVisibleToTheNextRead() {
        // Read-your-writes. An admin request that updates a workspace and reads it back must see its own
        // change; breaking that with an optimization would be far worse than the redundant reads the
        // memo removes. The repository calls invalidate() on save and delete.
        AtomicInteger loads = new AtomicInteger();
        WorkspaceLookupScope.open();

        WorkspaceLookupScope.memoize("acme", id -> {
            loads.incrementAndGet();
            return Optional.of(workspace(id));
        });
        WorkspaceLookupScope.invalidate();
        WorkspaceLookupScope.memoize("acme", id -> {
            loads.incrementAndGet();
            return Optional.of(workspace(id));
        });

        assertThat(loads).hasValue(2);
    }

    @Test
    void aMissIsMemoizedToo() {
        // A miss costs exactly as much as a hit. A request asking repeatedly about a workspace that does
        // not exist would otherwise repeat the round trip every time.
        AtomicInteger loads = new AtomicInteger();
        WorkspaceLookupScope.open();

        for (int i = 0; i < 5; i++) {
            var found = WorkspaceLookupScope.memoize("ghost", id -> {
                loads.incrementAndGet();
                return Optional.empty();
            });
            assertThat(found).isEmpty();
        }

        assertThat(loads).hasValue(1);
    }

    @Test
    void differentWorkspacesAreMemoizedIndependently() {
        AtomicInteger loads = new AtomicInteger();
        WorkspaceLookupScope.open();

        WorkspaceLookupScope.memoize("a", id -> { loads.incrementAndGet(); return Optional.of(workspace(id)); });
        WorkspaceLookupScope.memoize("b", id -> { loads.incrementAndGet(); return Optional.of(workspace(id)); });
        WorkspaceLookupScope.memoize("a", id -> { loads.incrementAndGet(); return Optional.of(workspace(id)); });

        assertThat(loads).hasValue(2);
    }

    @Test
    void closingEndsTheScopeSoTheNextRequestStartsClean() {
        // The property the filter's finally exists to guarantee. If a scope outlived its request, a
        // later request on the same thread would serve a workspace read before its own authentication —
        // which is a correctness bug, not a performance one.
        AtomicInteger loads = new AtomicInteger();

        WorkspaceLookupScope.open();
        WorkspaceLookupScope.memoize("acme", id -> { loads.incrementAndGet(); return Optional.of(workspace(id)); });
        WorkspaceLookupScope.close();

        assertThat(WorkspaceLookupScope.isActive()).isFalse();

        WorkspaceLookupScope.open();
        WorkspaceLookupScope.memoize("acme", id -> { loads.incrementAndGet(); return Optional.of(workspace(id)); });

        assertThat(loads).hasValue(2);
    }

    @Test
    void aScopeDoesNotLeakBetweenThreads() throws Exception {
        WorkspaceLookupScope.open();
        WorkspaceLookupScope.memoize("acme", id -> Optional.of(workspace(id)));

        AtomicInteger otherThreadLoads = new AtomicInteger();
        Thread other = Thread.ofVirtual().start(() -> {
            assertThat(WorkspaceLookupScope.isActive()).isFalse();
            WorkspaceLookupScope.memoize("acme", id -> {
                otherThreadLoads.incrementAndGet();
                return Optional.of(workspace(id));
            });
        });
        other.join();

        // Each request gets its own virtual thread, so one request's memo must be invisible to
        // another's — otherwise the scope would be a shared cache with no eviction.
        assertThat(otherThreadLoads).hasValue(1);
    }
}