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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Memoizes {@code WorkspaceRepository.findById} for the duration of one request.
 *
 * <p>Many components read {@code Workspace.metadata} independently on the request path, each for
 * its own setting (PII action, guardrail thresholds, rate-limit overrides, priority tier, and so
 * on). Each is a reasonable {@code findById} on its own; together they are many round trips for a
 * row that cannot change within a request.
 *
 * <p>A request scope rather than a TTL because a TTL can serve a value that changed. The window
 * here is one request, so there is no staleness, and a workspace mutated mid-request invalidates
 * the memo explicitly. It sits inside the JDBC repository, so it collapses the repeated reads
 * whether or not the cache decorators are enabled.
 *
 * <p>Outside a scope it is a straight pass-through: schedulers, the streaming tail that persists
 * usage on a different virtual thread, and tests that construct a repository directly see no memo.
 * A memo that guessed at its own lifetime would be a cache with an undefined expiry.
 *
 * <p>The ThreadLocal is bracketed by a servlet filter's {@code finally}, so it is removed on every
 * path including a thrown request, whether or not requests run on virtual threads.
 */
public final class WorkspaceLookupScope {

    private static final ThreadLocal<Map<String, Optional<Workspace>>> SCOPE = new ThreadLocal<>();

    private WorkspaceLookupScope() {}

    /** Begins a scope. Idempotent — a nested open does not discard what the outer one memoized. */
    public static void open() {
        if (SCOPE.get() == null) {
            SCOPE.set(new HashMap<>());
        }
    }

    /** Ends the scope and releases the map. Must be called from a {@code finally}. */
    public static void close() {
        SCOPE.remove();
    }

    /** True while a scope is active — for tests and diagnostics, not for branching in callers. */
    public static boolean isActive() {
        return SCOPE.get() != null;
    }

    /**
     * The memoized read.
     *
     * <p>Negative results are cached too. A request that asks about a workspace which does not exist
     * would otherwise repeat the miss as many times as it repeats the question, and a miss is exactly
     * as expensive as a hit.
     */
    public static Optional<Workspace> memoize(String id, Function<String, Optional<Workspace>> loader) {
        Map<String, Optional<Workspace>> scope = SCOPE.get();
        if (scope == null || id == null) {
            return loader.apply(id);
        }
        return scope.computeIfAbsent(id, loader);
    }

    /**
     * Drops what this request has memoized.
     *
     * <p>Called by the repository on any write, so a caller that updates a workspace and reads it
     * back within one request sees its own change. The whole map is cleared rather than one key:
     * writes on this path are rare, and a per-key eviction invites the question of which derived
     * entries also need dropping.
     */
    public static void invalidate() {
        Map<String, Optional<Workspace>> scope = SCOPE.get();
        if (scope != null) {
            scope.clear();
        }
    }
}