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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Data;

import java.time.Instant;

/**
 * Tracking record for a submitted Batch API job. Persisted at submit time so the
 * upload→submit→poll→results lifecycle stays pinned to one provider, and so cost is booked
 * <em>exactly once</em> when the job completes (the {@link #costBooked} flag guards against
 * repeated client polls re-charging the workspace).
 *
 * <p>Workspace-scoped: every lookup filters by {@code workspaceId} so a workspace can never poll or fetch
 * results for another workspace's batch by guessing a provider batch id.</p>
 */
@Data
@Builder
// Jackson needs a constructor it can call when a batch job crosses a process boundary;
// @Data + @Builder alone leave none.
@NoArgsConstructor
@AllArgsConstructor
public class BatchJob {

    /** Internal primary key. */
    private String id;

    private String workspaceId;

    /**
     * The opaque id of the API key that submitted the batch, so cost and usage attribute correctly
     * at completion. The id, never the bearer token: this row is persisted.
     */
    private String apiKey;

    /** Resolved provider name (e.g. {@code openai}); the whole lifecycle stays on this provider. */
    private String provider;

    /** The provider's batch id — what the client references on {@code GET /v1/batches/{id}}. */
    private String providerBatchId;

    /** The uploaded input file id the batch was submitted against. */
    private String inputFileId;

    /** The provider's output file id, discovered when the job reaches a terminal state (nullable until then). */
    private String outputFileId;

    /** Last observed provider status (e.g. {@code validating}, {@code in_progress}, {@code completed}). */
    private String status;

    /** True once usage + cost have been booked for this job — prevents double-charging on repeated polls. */
    private boolean costBooked;

    private Instant createdAt;

    private Instant updatedAt;
}