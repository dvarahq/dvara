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

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * The default methods of {@code TokenUsageRepository}: paging, keyset walking, filters and the
 * window bounds that an implementation inherits unless it overrides them.
 *
 * <p>The one to know about is {@link #breakdownByModel_isAnEmptyStub_notAComputedBreakdown()}: that
 * default returns an empty list, so an implementation that inherits it reports no volume rather
 * than failing.
 */
class TokenUsageRepositoryDefaultsTest {

    private static final Instant T0 = Instant.parse("2026-08-01T00:00:00Z");

    private static final class InMemoryUsage implements TokenUsageRepository {
        private final List<TokenUsageRecord> rows = new ArrayList<>();

        @Override public void save(TokenUsageRecord record) { rows.add(record); }
        @Override public List<TokenUsageRecord> findAll() { return List.copyOf(rows); }
        @Override public List<TokenUsageRecord> findByWorkspaceId(String t) {
            return rows.stream().filter(r -> t.equals(r.getWorkspaceId())).toList();
        }
        @Override public List<TokenUsageRecord> findByApiKey(String k) {
            return rows.stream().filter(r -> k.equals(r.getApiKey())).toList();
        }
        @Override public List<TokenUsageRecord> findByModel(String m) {
            return rows.stream().filter(r -> m.equals(r.getModel())).toList();
        }
        @Override public List<TokenUsageRecord> findByTimestampBetween(Instant from, Instant to) {
            return rows.stream().filter(r -> r.getTimestamp() != null
                    && !r.getTimestamp().isBefore(from) && !r.getTimestamp().isAfter(to)).toList();
        }
        @Override public TokenUsageSummary summarize(String t, String m, Instant f, Instant to) {
            return TokenUsageSummary.builder().workspaceId(t).build();
        }
    }

    private static TokenUsageRecord usage(String id, String workspace, String key, String model,
                                          Instant at) {
        return TokenUsageRecord.builder()
                .id(id).workspaceId(workspace).apiKey(key).model(model)
                .inputTokens(10).outputTokens(20).totalTokens(30).timestamp(at).build();
    }

    private static TokenUsageRepository seeded(TokenUsageRecord... records) {
        TokenUsageRepository repo = new InMemoryUsage();
        for (TokenUsageRecord r : records) {
            repo.save(r);
        }
        return repo;
    }

    private static TokenUsageRepository usageLog() {
        return seeded(
                usage("a", "t1", "k1", "gpt-4o", T0.plusSeconds(10)),
                usage("b", "t1", "k1", "gpt-4o", T0.plusSeconds(20)),
                usage("c", "t1", "k2", "claude-3", T0.plusSeconds(30)),
                usage("d", "t2", "k3", "gpt-4o", T0.plusSeconds(40)));
    }

    // --- paging ------------------------------------------------------------------------------------

    @Test
    void findPage_isNewestFirstBoundedAndClamped() {
        assertThat(usageLog().findPage("t1", null, null, T0, T0.plusSeconds(3600), 2, 0))
                .extracting(TokenUsageRecord::getId).containsExactly("c", "b");
        assertThat(usageLog().findPage("t1", null, null, T0, T0.plusSeconds(3600), 10, 99)).isEmpty();
        assertThat(usageLog().findPage("t1", null, null, T0, T0.plusSeconds(3600), -1, 0)).isEmpty();
    }

    @Test
    void filters_areWildcardsWhenNullAndAndCombine() {
        assertThat(usageLog().countMatching("t1", null, null, T0, T0.plusSeconds(3600))).isEqualTo(3);
        assertThat(usageLog().countMatching("t1", "k1", null, T0, T0.plusSeconds(3600))).isEqualTo(2);
        assertThat(usageLog().countMatching("t1", "k1", "gpt-4o", T0, T0.plusSeconds(3600))).isEqualTo(2);
        assertThat(usageLog().countMatching("t1", "k2", "gpt-4o", T0, T0.plusSeconds(3600))).isZero();
        assertThat(usageLog().countMatching(null, null, null, T0, T0.plusSeconds(3600))).isEqualTo(4);
    }

    @Test
    void findKeyset_walksBothDirectionsAndBreaksTiesById() {
        assertThat(usageLog().findKeyset("t1", null, null, T0, T0.plusSeconds(3600),
                T0.plusSeconds(30), "c", true, 10))
                .extracting(TokenUsageRecord::getId).containsExactly("b", "a");
        assertThat(usageLog().findKeyset("t1", null, null, T0, T0.plusSeconds(3600),
                T0.plusSeconds(10), "a", false, 10))
                .extracting(TokenUsageRecord::getId).containsExactly("b", "c");

        TokenUsageRepository tied = seeded(
                usage("a", "t1", "k", "m", T0), usage("b", "t1", "k", "m", T0),
                usage("c", "t1", "k", "m", T0));
        assertThat(tied.findKeyset("t1", null, null, T0, T0.plusSeconds(60), T0, "b", true, 10))
                .extracting(TokenUsageRecord::getId).containsExactly("a");
    }

    @Test
    void findKeyset_limitIsFlooredAtOne() {
        assertThat(usageLog().findKeyset("t1", null, null, T0, T0.plusSeconds(3600),
                null, null, true, 0)).hasSize(1);
    }

    // --- the stubs -----------------------------------------------------------------------------------

    /**
     * The default is a <b>stub returning empty</b>, not a computed breakdown. It feeds the monthly
     * usage report's served-volume split (exact / estimated / cache-hit), so an implementation that
     * inherits it renders a billing artifact with an empty volume section — no exception, no warning,
     * and indistinguishable from a workspace that made no calls.
     *
     * <p>Pinned rather than implemented: computing it in-memory here would be a second definition of
     * a billing figure, and the right home for that decision is the interface's owner, not a test.</p>
     */
    @Test
    void breakdownByModel_isAnEmptyStub_notAComputedBreakdown() {
        TokenUsageRepository repo = seeded(
                usage("a", "t1", "k1", "gpt-4o", T0),
                usage("b", "t1", "k1", "gpt-4o", T0.plusSeconds(10)));

        assertThat(repo.breakdownByModel("t1", T0, T0.plusSeconds(3600)))
                .as("two records for one model, and the default still reports nothing")
                .isEmpty();
    }

    /**
     * The counter-example, and the one this interface gets right: the forensic lookup <b>refuses</b>
     * rather than returning empty, because an empty list is an answer — "no workspace used that
     * credential" — and the caller is asking during an incident, after a provider named a leaked key.
     *
     * <p>Which makes the contrast with {@link #breakdownByModel_isAnEmptyStub_notAComputedBreakdown()}
     * the thing to notice: three unimplemented defaults in this family, and only this one says so. The
     * other two return empty and are indistinguishable from a true zero.</p>
     */
    @Test
    void findByCredentialFingerprint_refusesRatherThanAnsweringEmptily() {
        TokenUsageRepository repo = seeded(TokenUsageRecord.builder()
                .id("a").workspaceId("t1").model("gpt-4o").timestamp(T0)
                .credentialFingerprint("fp-1").build());

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> repo.findByCredentialFingerprint("fp-1", T0, T0.plusSeconds(60)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("credential-fingerprint lookup");
    }

    @Test
    void saveAll_persistsEveryRecord() {
        TokenUsageRepository repo = new InMemoryUsage();

        repo.saveAll(List.of(usage("a", "t1", "k", "m", T0), usage("b", "t1", "k", "m", T0)));

        assertThat(repo.findAll()).hasSize(2);
    }

    // ---------------------------------------------------------------------------------------------
    // The window is half-open, and a record that cannot be ordered does not crash the seek.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theWindowExcludesItsEndSoAdjacentPeriodsDoNotShareARow() {
        // The monthly report calls breakdownByModel and summarize with to = the first instant of
        // the next month, so both must exclude the end, or a row stamped exactly there is billed in
        // two consecutive months.
        Instant boundary = Instant.parse("2026-10-01T00:00:00Z");
        var repo = repoOf(
                record("before", Instant.parse("2026-09-30T23:59:59Z")),
                record("onTheBoundary", boundary),
                record("after", Instant.parse("2026-10-01T00:00:01Z")));

        var september = repo.findPage(null, null, null,
                Instant.parse("2026-09-01T00:00:00Z"), boundary, 100, 0);
        var october = repo.findPage(null, null, null,
                boundary, Instant.parse("2026-11-01T00:00:00Z"), 100, 0);

        assertThat(september).extracting(TokenUsageRecord::getId).containsExactly("before");
        assertThat(october).extracting(TokenUsageRecord::getId)
                .containsExactlyInAnyOrder("onTheBoundary", "after");
        assertThat(repo.countMatching(null, null, null,
                Instant.parse("2026-09-01T00:00:00Z"), boundary)).isEqualTo(1);
    }

    @Test
    void theStartOfTheWindowIsIncluded() {
        // Half-open means one end moves, not both.
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        var repo = repoOf(record("atTheStart", start));

        assertThat(repo.findPage(null, null, null, start, start.plusSeconds(60), 100, 0))
                .extracting(TokenUsageRecord::getId).containsExactly("atTheStart");
    }

    @Test
    void aRecordWithNoTimestampDoesNotBreakTheSeekPage() {
        // findPage orders these with nullsLast comparators; findKeyset must not dereference the
        // timestamp either.
        var repo = repoOf(record("dated", Instant.parse("2026-09-10T00:00:00Z")),
                record("noDate", null));

        assertThatNoException().isThrownBy(() -> repo.findKeyset(null, null, null, null, null,
                Instant.parse("2026-09-20T00:00:00Z"), "zzz", true, 10));
        assertThat(repo.findKeyset(null, null, null, null, null,
                Instant.parse("2026-09-20T00:00:00Z"), "zzz", true, 10))
                .extracting(TokenUsageRecord::getId).containsExactly("dated");
    }

    @Test
    void aRecordWithNoIdDoesNotBreakTheSeekPageEither() {
        var repo = repoOf(record(null, Instant.parse("2026-09-10T00:00:00Z")));

        assertThatNoException().isThrownBy(() -> repo.findKeyset(null, null, null, null, null,
                Instant.parse("2026-09-20T00:00:00Z"), "zzz", true, 10));
    }

    private static TokenUsageRecord record(String id, Instant ts) {
        return TokenUsageRecord.builder().id(id).timestamp(ts).build();
    }

    /** A minimal repository that implements only findAll, so the interface defaults are under test. */
    private static TokenUsageRepository repoOf(TokenUsageRecord... records) {
        List<TokenUsageRecord> all = List.of(records);
        return new TokenUsageRepository() {
            @Override public void save(TokenUsageRecord record) { }
            @Override public List<TokenUsageRecord> findAll() { return all; }
            @Override public List<TokenUsageRecord> findByWorkspaceId(String workspaceId) { return all; }
            @Override public List<TokenUsageRecord> findByApiKey(String apiKey) { return all; }
            @Override public List<TokenUsageRecord> findByModel(String model) { return all; }
            @Override public List<TokenUsageRecord> findByTimestampBetween(Instant from, Instant to) { return all; }
            @Override public TokenUsageSummary summarize(String workspaceId, String model,
                                                         Instant from, Instant to) {
                return TokenUsageSummary.builder().build();
            }
        };
    }
}
