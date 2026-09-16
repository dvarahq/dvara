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
package com.dvarahq.core.batch;

import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for Batch API tracking rows.
 *
 * <p>This build registers a bounded in-memory store, lost on restart; a module with a database may
 * register a durable one. A batch cannot be polled or its results fetched without a record of which
 * provider took it, so some store is required for the Batch endpoints to work at all.
 *
 * <p>All per-id lookups are workspace-scoped: a batch is resolved by {@code (providerBatchId,
 * workspaceId)}, never by provider batch id alone, so a workspace cannot poll another's batch.</p>
 */
public interface BatchJobRepository {

    void save(BatchJob job);

    /** Workspace-scoped lookup used on every poll / results / status path. */
    Optional<BatchJob> findByProviderBatchIdAndWorkspaceId(String providerBatchId, String workspaceId);

    List<BatchJob> findByWorkspaceId(String workspaceId);

    /**
     * The batch this workspace owns that produced the given file, if any.
     *
     * <p>This is how a file id becomes workspace-scoped. The provider's Files API is keyed by file
     * id alone, and file ids are guessable, so serving file content needs some way to answer "is
     * this the caller's file". A batch row is the only thing that knows — it recorded both the
     * input file it was submitted with and the output file it produced.
     *
     * <p>Matches on <b>either</b> the input or the output file, so a client can read back what it
     * uploaded as well as what came out.
     *
     * <p>A miss must be reported as <em>not found</em>, never as <em>not yours</em>: the two are the
     * same answer here on purpose, or the endpoint tells a caller which of another workspace's file
     * ids exist.
     */
    Optional<BatchJob> findByFileIdAndWorkspaceId(String fileId, String workspaceId);

    /**
     * Atomically claims a job for cost booking: a single conditional {@code UPDATE ... SET
     * cost_booked = true ... WHERE id = ? AND workspace_id (matches) AND cost_booked = false}. Returns
     * {@code true} iff <em>this</em> call flipped the flag (row count 1) — so exactly one of any set
     * of concurrent completion polls wins and does the metering; the losers get {@code false} and
     * skip. Also stamps the observed {@code status} + discovered {@code outputFileId}. The workspace
     * predicate is defence-in-depth on top of the workspace-scoped read.
     */
    boolean claimBooking(String id, String workspaceId, String status, String outputFileId);

    /** Updates the last-observed status without touching the booking flag (non-terminal polls). */
    void updateStatus(String id, String workspaceId, String status, String outputFileId);
}