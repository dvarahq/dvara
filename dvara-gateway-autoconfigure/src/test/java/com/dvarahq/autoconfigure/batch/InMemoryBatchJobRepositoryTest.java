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
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryBatchJobRepositoryTest {

    private static BatchJob job(String id, String workspace, String providerBatchId) {
        return BatchJob.builder().id(id).workspaceId(workspace).provider("openai")
                .providerBatchId(providerBatchId).inputFileId("file-in").status("validating")
                .costBooked(false).createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    @Test
    void aSubmittedBatchIsFoundByItsProviderIdWithinItsWorkspace() {
        InMemoryBatchJobRepository repo = new InMemoryBatchJobRepository();
        repo.save(job("j1", "acme", "batch_1"));

        assertThat(repo.findByProviderBatchIdAndWorkspaceId("batch_1", "acme")).isPresent();
        assertThat(repo.findByWorkspaceId("acme")).hasSize(1);
    }

    @Test
    void anotherWorkspaceCannotSeeItByGuessingTheProviderId() {
        InMemoryBatchJobRepository repo = new InMemoryBatchJobRepository();
        repo.save(job("j1", "acme", "batch_1"));

        assertThat(repo.findByProviderBatchIdAndWorkspaceId("batch_1", "globex")).isEmpty();
        assertThat(repo.findByWorkspaceId("globex")).isEmpty();
        assertThat(repo.claimBooking("j1", "globex", "completed", "file-out")).isFalse();
    }

    @Test
    void theCostIsClaimedExactlyOnce() {
        InMemoryBatchJobRepository repo = new InMemoryBatchJobRepository();
        repo.save(job("j1", "acme", "batch_1"));

        assertThat(repo.claimBooking("j1", "acme", "completed", "file-out")).isTrue();
        assertThat(repo.claimBooking("j1", "acme", "completed", "file-out")).isFalse();
        BatchJob stored = repo.findByProviderBatchIdAndWorkspaceId("batch_1", "acme").orElseThrow();
        assertThat(stored.isCostBooked()).isTrue();
        assertThat(stored.getStatus()).isEqualTo("completed");
        assertThat(stored.getOutputFileId()).isEqualTo("file-out");
    }

    @Test
    void theOldestJobsAreEvictedPastTheBound() {
        InMemoryBatchJobRepository repo = new InMemoryBatchJobRepository(3);
        for (int i = 0; i < 5; i++) repo.save(job("j" + i, "acme", "batch_" + i));

        assertThat(repo.size()).isEqualTo(3);
        assertThat(repo.findByProviderBatchIdAndWorkspaceId("batch_0", "acme")).isEmpty();
        assertThat(repo.findByProviderBatchIdAndWorkspaceId("batch_4", "acme")).isPresent();
    }

    @Test
    void findByWorkspaceId_returnsNewestFirst_matchingTheDurableStore() {
        // The durable store orders by created_at DESC; this store must list in the same order so a
        // listing's first page does not depend on which store serves it.
        InMemoryBatchJobRepository repo = new InMemoryBatchJobRepository();
        repo.save(BatchJob.builder().id("j1").workspaceId("acme").providerBatchId("b1").build());
        repo.save(BatchJob.builder().id("j2").workspaceId("acme").providerBatchId("b2").build());
        repo.save(BatchJob.builder().id("j3").workspaceId("acme").providerBatchId("b3").build());

        assertThat(repo.findByWorkspaceId("acme"))
                .extracting(BatchJob::getProviderBatchId)
                .containsExactly("b3", "b2", "b1");
    }
}
