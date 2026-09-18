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
package com.dvarahq.autoconfigure.batch;

import com.dvarahq.core.batch.BatchJob;
import com.dvarahq.core.batch.BatchJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Batch job tracking for a build configured from {@code gateway.yaml} alone: the rows live in this
 * process and nowhere else.
 *
 * <p>A submitted batch needs one record to be remembered: which provider took it, its provider
 * batch id, the file ids, its status, and whether its cost was booked. Without that record the
 * endpoints cannot poll or fetch results and cannot book the cost exactly once. This store keeps
 * that record in memory, so the Batch API works on a build with no database.
 *
 * <p><b>Bounded and volatile.</b> The {@link #MAX_JOBS} most recent jobs are kept and the oldest
 * evicted; all of it is lost when the process stops. A batch submitted before a restart can no
 * longer be polled through the gateway afterwards, and its cost is never booked. Both are stated
 * once at startup rather than discovered from a 404. A build that needs batches to survive a
 * restart needs a database-backed repository, which another module or the application may
 * register.
 *
 * <p>Every lookup is scoped by workspace, the same rule the durable store follows: a batch is
 * found by provider batch id <em>and</em> workspace, never by provider batch id alone, so one
 * workspace cannot poll another's batch by guessing an id.
 */
public class InMemoryBatchJobRepository implements BatchJobRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBatchJobRepository.class);

    static final int MAX_JOBS = 10_000;

    private final int maxJobs;
    private final Map<String, BatchJob> byId = new LinkedHashMap<>();

    public InMemoryBatchJobRepository() {
        this(MAX_JOBS);
    }

    InMemoryBatchJobRepository(int maxJobs) {
        this.maxJobs = maxJobs;
        log.warn("Batch jobs are tracked IN MEMORY ONLY: the most recent {} jobs, lost on restart. "
                + "A batch submitted before a restart cannot be polled afterwards and its cost is "
                + "never booked. Configure a database to keep them.", maxJobs);
    }

    @Override
    public synchronized void save(BatchJob job) {
        if (job.getId() == null) {
            throw new IllegalArgumentException("a batch job needs an id");
        }
        byId.put(job.getId(), job);
        while (byId.size() > maxJobs) {
            String oldest = byId.keySet().iterator().next();
            byId.remove(oldest);
        }
    }

    @Override
    public synchronized Optional<BatchJob> findByProviderBatchIdAndWorkspaceId(String providerBatchId, String workspaceId) {
        return byId.values().stream()
                .filter(j -> providerBatchId != null && providerBatchId.equals(j.getProviderBatchId()))
                .filter(j -> sameWorkspace(j, workspaceId))
                .findFirst();
    }

    @Override
    public synchronized Optional<BatchJob> findByFileIdAndWorkspaceId(String fileId, String workspaceId) {
        if (fileId == null) {
            return Optional.empty();
        }
        return byId.values().stream()
                .filter(j -> sameWorkspace(j, workspaceId))
                .filter(j -> fileId.equals(j.getOutputFileId()) || fileId.equals(j.getInputFileId()))
                .findFirst();
    }

    @Override
    public synchronized List<BatchJob> findByWorkspaceId(String workspaceId) {
        // Newest first, which is what a database store's ORDER BY created_at DESC gives and what
        // the listing endpoint pages through. The map iterates in insertion order, so it is reversed.
        List<BatchJob> out = new ArrayList<>();
        for (BatchJob j : byId.values()) {
            if (sameWorkspace(j, workspaceId)) out.add(j);
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * Flips {@code costBooked} from false to true for the named job and workspace, and says
     * whether this call was the one that flipped it. Two concurrent polls both reach a terminal
     * status; exactly one of them books the cost.
     */
    @Override
    public synchronized boolean claimBooking(String id, String workspaceId, String status, String outputFileId) {
        BatchJob job = byId.get(id);
        if (job == null || !sameWorkspace(job, workspaceId) || job.isCostBooked()) {
            return false;
        }
        job.setCostBooked(true);
        job.setStatus(status);
        job.setOutputFileId(outputFileId);
        job.setUpdatedAt(Instant.now());
        return true;
    }

    @Override
    public synchronized void updateStatus(String id, String workspaceId, String status, String outputFileId) {
        BatchJob job = byId.get(id);
        if (job == null || !sameWorkspace(job, workspaceId)) return;
        job.setStatus(status);
        if (outputFileId != null) job.setOutputFileId(outputFileId);
        job.setUpdatedAt(Instant.now());
    }

    synchronized int size() {
        return byId.size();
    }

    /**
     * A job belongs to exactly one workspace and is only ever looked up by it. There is no tenant
     * for "no workspace": a lookup with none matches nothing, so two callers that both lack one can
     * never see each other's jobs.
     */
    private static boolean sameWorkspace(BatchJob job, String workspaceId) {
        return workspaceId != null && !workspaceId.isBlank() && workspaceId.equals(job.getWorkspaceId());
    }
}
