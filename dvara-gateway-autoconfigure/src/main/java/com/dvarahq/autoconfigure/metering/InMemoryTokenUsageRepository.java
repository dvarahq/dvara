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
package com.dvarahq.autoconfigure.metering;

import com.dvarahq.core.metering.TokenUsageRecord;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.metering.TokenUsageSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Token usage held in this process only, for a build with nowhere durable to put it.
 *
 * <p>This one cannot refuse writes the way the other file-store repositories do:
 * {@code ChatExecutionService.persistTokenUsage} runs on every completed request, so a throw here
 * would turn each successful call into a 500. Nor can the reads throw: this process is the whole
 * install, so its own count is the true answer for as long as it has been running, and a quota
 * that cannot count must serve rather than refuse.
 *
 * <p><b>Bounded and volatile.</b> The {@link #MAX_RECORDS} most recent records are kept, the oldest
 * evicted, and all of it is lost on restart. Usable for usage-page questions within one process
 * lifetime, and not a billing record. A startup WARN says so, because usage silently resetting to
 * zero is indistinguishable from no traffic.
 */
public class InMemoryTokenUsageRepository implements TokenUsageRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTokenUsageRepository.class);

    /**
     * Enough to answer a usage page, small enough that a busy gateway cannot exhaust the heap with
     * telemetry nobody is reading. At roughly 200 bytes a record this is single-digit megabytes.
     */
    static final int MAX_RECORDS = 50_000;

    private final Deque<TokenUsageRecord> records = new ArrayDeque<>();
    private final int maxRecords;

    public InMemoryTokenUsageRepository() {
        this(MAX_RECORDS);
    }

    InMemoryTokenUsageRepository(int maxRecords) {
        this.maxRecords = maxRecords;
        log.warn("Token usage is being recorded IN MEMORY ONLY: the most recent {} records, lost on "
                + "restart. Usage and cost figures describe this process since it started, not the "
                + "install. Configure a datasource for durable metering.", maxRecords);
    }

    @Override
    public synchronized void save(TokenUsageRecord record) {
        if (record == null) {
            return;
        }
        records.addLast(record);
        while (records.size() > maxRecords) {
            records.removeFirst();
        }
    }

    @Override
    public synchronized List<TokenUsageRecord> findAll() {
        return List.copyOf(records);
    }

    /**
     * Nothing for a null workspace, which is what the database store answers ({@code workspace_id = NULL} matches
     * no row). A caller holding a workspace it could not resolve must get no usage, not every workspace's.
     */
    @Override
    public List<TokenUsageRecord> findByWorkspaceId(String workspaceId) {
        return filter(r -> workspaceId != null && workspaceId.equals(r.getWorkspaceId()));
    }

    /** Nothing for a null key, for the same reason. */
    @Override
    public List<TokenUsageRecord> findByApiKey(String apiKey) {
        return filter(r -> apiKey != null && apiKey.equals(r.getApiKey()));
    }

    /** Nothing for a null model, for the same reason. */
    @Override
    public List<TokenUsageRecord> findByModel(String model) {
        return filter(r -> model != null && model.equals(r.getModel()));
    }

    @Override
    public List<TokenUsageRecord> findByTimestampBetween(Instant from, Instant to) {
        return filter(r -> withinInclusive(r, from, to));
    }

    @Override
    public TokenUsageSummary summarize(String workspaceId, String model, Instant from, Instant to) {
        List<TokenUsageRecord> matched = filter(r ->
                (workspaceId == null || workspaceId.equals(r.getWorkspaceId()))
                        && (model == null || model.equals(r.getModel()))
                        && windowHalfOpen(r, from, to));
        long in = 0;
        long out = 0;
        long total = 0;
        for (TokenUsageRecord r : matched) {
            in += r.getInputTokens();
            out += r.getOutputTokens();
            total += r.getTotalTokens();
        }
        return TokenUsageSummary.builder()
                .workspaceId(workspaceId)
                .model(model)
                .totalInputTokens(in)
                .totalOutputTokens(out)
                .totalTokens(total)
                .requestCount(matched.size())
                .build();
    }

    /**
     * Half-open: {@code from} included, {@code to} excluded — the convention
     * {@link TokenUsageRepository} states, so this store and the JDBC one answer the same question.
     *
     * <p>{@code findByTimestampBetween} is the documented exception and is inclusive at both ends;
     * it has its own predicate below rather than sharing this one.</p>
     */
    private static boolean windowHalfOpen(TokenUsageRecord r, Instant from, Instant to) {
        Instant ts = r.getTimestamp();
        if (ts == null) {
            return from == null && to == null;
        }
        return (from == null || !ts.isBefore(from)) && (to == null || ts.isBefore(to));
    }

    /** Inclusive at both ends, for the one method whose name promises that. */
    private static boolean withinInclusive(TokenUsageRecord r, Instant from, Instant to) {
        Instant ts = r.getTimestamp();
        if (ts == null) {
            return from == null && to == null;
        }
        return (from == null || !ts.isBefore(from)) && (to == null || !ts.isAfter(to));
    }

    private synchronized List<TokenUsageRecord> filter(java.util.function.Predicate<TokenUsageRecord> p) {
        List<TokenUsageRecord> out = new ArrayList<>();
        for (TokenUsageRecord r : records) {
            if (p.test(r)) {
                out.add(r);
            }
        }
        return List.copyOf(out);
    }
}