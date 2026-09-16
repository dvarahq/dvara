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
package com.dvarahq.core.metering;

import com.dvarahq.core.util.Pages;
import java.time.Instant;
import java.util.List;

/**
 * Repository for persisting and querying token usage records.
 *
 * <p>This build registers a bounded in-memory implementation; a module with a database may
 * register a durable one.</p>
 *
 * <h2>Every time window here is half-open: {@code [from, to)}</h2>
 *
 * <p>{@code from} is included, {@code to} is <b>not</b>. Adjacent windows must abut, not overlap:
 * a row stamped exactly on a month boundary must count in one month's report, not two, and
 * half-open is the convention where {@code [Jan, Feb)} and {@code [Feb, Mar)} do.</p>
 *
 * <p>The one exception: {@link #findByTimestampBetween} is <b>inclusive at both ends</b>, because
 * that is what its name says and what SQL {@code BETWEEN} means.</p>
 *
 * <p>A {@code null} bound means "no bound" on every method that takes one.</p>
 */
public interface TokenUsageRepository {

    void save(TokenUsageRecord record);

    /**
     * Persist a whole batch of records. The default persists one at a time; a database-backed
     * implementation should override it with a single multi-row insert, idempotent on the record
     * {@code id}, so a retried batch lands each row exactly once.
     */
    default void saveAll(List<TokenUsageRecord> records) {
        records.forEach(this::save);
    }

    /**
     * Which workspaces sent upstream traffic under this credential, in this window.
     *
     * <p>The abuse-report query, and the reason the fingerprint is recorded at all. A provider names
     * a key and a time range; this maps that back to workspaces. The caller hashes the key the
     * provider gave them with {@code CredentialFingerprint.of}.
     *
     * <p>Rows with a null fingerprint are excluded: they made no attributable upstream call.
     *
     * <p><b>This one window is inclusive at both ends.</b> For a forensic query, one extra boundary
     * row in front of a human is a cheaper error than a workspace left out of an abuse report.
     */
    default List<TokenUsageRecord> findByCredentialFingerprint(String fingerprint,
                                                               java.time.Instant from,
                                                               java.time.Instant to) {
        // Throws rather than returning empty. An implementation that has not wired the forensic
        // query must say so: an empty list would answer "no workspace used this credential" to an
        // abuse report, which is the one wrong answer that looks like a right one. The in-memory
        // repository does not override this, so it throws there by design.
        throw new UnsupportedOperationException(
                getClass().getSimpleName() + " does not support credential-fingerprint lookup");
    }

    List<TokenUsageRecord> findAll();

    List<TokenUsageRecord> findByWorkspaceId(String workspaceId);

    List<TokenUsageRecord> findByApiKey(String apiKey);

    List<TokenUsageRecord> findByModel(String model);

    /**
     * Records in {@code [from, to]}, <b>inclusive at both ends</b> unlike every other window on this
     * interface, because that is what the name and SQL {@code BETWEEN} say.
     */
    List<TokenUsageRecord> findByTimestampBetween(Instant from, Instant to);

    TokenUsageSummary summarize(String workspaceId, String model, Instant from, Instant to);

    /**
     * Per-model usage breakdown over {@code [from, to)}, splitting exact, estimated and cache-hit
     * tokens. {@code workspaceId == null} aggregates across all workspaces.
     *
     * <p>Default returns empty so lightweight stubs of this interface need not implement it; a
     * database-backed implementation overrides it.
     */
    default java.util.List<UsageBreakdown> breakdownByModel(String workspaceId, Instant from, Instant to) {
        return java.util.List.of();
    }

    /**
     * A bounded, newest-first page of records matching all supplied filters (each
     * {@code null} filter is a wildcard; filters combine with AND). Pushes limit +
     * offset to the store so the list page never loads an unbounded result set.
     *
     * <p>Default is an in-memory slice over {@link #findAll()} so lightweight stubs keep working;
     * a database-backed implementation overrides it with SQL {@code LIMIT/OFFSET}. Pair with
     * {@link #countMatching} for the total.</p>
     */
    default List<TokenUsageRecord> findPage(String workspaceId, String apiKey, String model,
                                            Instant from, Instant to, int limit, int offset) {
        List<TokenUsageRecord> matched = matching(workspaceId, apiKey, model, from, to);
        return Pages.newestFirst(matched, TokenUsageRecord::getTimestamp, limit, offset);
    }

    /** Total records matching the same filters as {@link #findPage} (for the page count). */
    default long countMatching(String workspaceId, String apiKey, String model, Instant from, Instant to) {
        return matching(workspaceId, apiKey, model, from, to).size();
    }

    /**
     * Keyset (seek) page: O(limit) at any depth, unlike {@link #findPage}'s O(offset).
     * Fetch order is DESC when {@code older} (an "Older" walk from the tail), ASC when not
     * ("Newer"); the caller reverses the ASC case. A database-backed implementation overrides
     * this with a {@code (timestamp, id)} row-comparison seek.
     */
    default List<TokenUsageRecord> findKeyset(String workspaceId, String apiKey, String model,
                                              Instant from, Instant to,
                                              Instant cursorTs, String cursorId, boolean older, int limit) {
        var byKey = java.util.Comparator
                .comparing(TokenUsageRecord::getTimestamp, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(TokenUsageRecord::getId, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
        return matching(workspaceId, apiKey, model, from, to).stream()
                .filter(r -> cursorTs == null
                        || (older ? seekLess(r, cursorTs, cursorId) : seekGreater(r, cursorTs, cursorId)))
                .sorted(older ? byKey.reversed() : byKey)
                .limit(Math.max(1, limit))
                .toList();
    }

    // A record with no timestamp or no id cannot be placed in the order, so it is skipped rather
    // than dereferenced; the comparators beside these already treat such rows as possible.
    private static boolean seekLess(TokenUsageRecord r, Instant ts, String id) {
        if (r.getTimestamp() == null || r.getId() == null) {
            return false;
        }
        int c = r.getTimestamp().compareTo(ts);
        return c < 0 || (c == 0 && r.getId().compareTo(id) < 0);
    }

    private static boolean seekGreater(TokenUsageRecord r, Instant ts, String id) {
        if (r.getTimestamp() == null || r.getId() == null) {
            return false;
        }
        int c = r.getTimestamp().compareTo(ts);
        return c > 0 || (c == 0 && r.getId().compareTo(id) > 0);
    }

    private List<TokenUsageRecord> matching(String workspaceId, String apiKey, String model,
                                            Instant from, Instant to) {
        return findAll().stream()
                .filter(r -> workspaceId == null || workspaceId.equals(r.getWorkspaceId()))
                .filter(r -> apiKey == null || apiKey.equals(r.getApiKey()))
                .filter(r -> model == null || model.equals(r.getModel()))
                .filter(r -> from == null || (r.getTimestamp() != null && !r.getTimestamp().isBefore(from)))
                // isBefore, not !isAfter: the window excludes its end (see the class javadoc).
                .filter(r -> to == null || (r.getTimestamp() != null && r.getTimestamp().isBefore(to)))
                .sorted(java.util.Comparator.comparing(TokenUsageRecord::getTimestamp,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();
    }
}