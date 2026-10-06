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
package com.dvarahq.server.service;

import com.dvarahq.server.TestProviders;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.batch.BatchJob;
import com.dvarahq.core.batch.BatchJobRepository;
import com.dvarahq.core.batch.BatchModelCheck;
import com.dvarahq.core.batch.BatchSubmitGate;
import com.dvarahq.core.cost.CostRecord;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.WorkspaceStatus;
import com.dvarahq.server.filter.WorkspaceStatusFilter;
import com.dvarahq.server.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BatchExecutionServiceTest {

    private ProviderDispatcher dispatcher;
    private PiiEnforcer piiEnforcer;
    private BatchSubmitGate submitGate;
    private BatchJobRepository batchJobRepository;
    private BatchCostBooker costBooker;
    private WorkspaceUsageListener usageListener;
    private AuditWriter auditWriter;
    private GatewayMetrics metrics;
    private LlmProvider provider;
    private BatchExecutionService service;
    private WorkspaceRepository workspaces;

    private static final String OUTPUT_JSONL =
            "{\"custom_id\":\"1\",\"response\":{\"status_code\":200,\"body\":{\"model\":\"gpt-4o\",\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}}}\n"
          + "{\"custom_id\":\"2\",\"response\":{\"status_code\":200,\"body\":{\"model\":\"gpt-4o\",\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":8,\"total_tokens\":28}}}}";

    @BeforeEach
    void setUp() {
        dispatcher = mock(ProviderDispatcher.class);
        piiEnforcer = mock(PiiEnforcer.class);
        submitGate = mock(BatchSubmitGate.class);
        batchJobRepository = mock(BatchJobRepository.class);
        costBooker = mock(BatchCostBooker.class);
        usageListener = mock(WorkspaceUsageListener.class);
        auditWriter = mock(AuditWriter.class);
        metrics = mock(GatewayMetrics.class);
        provider = mock(LlmProvider.class);

        when(provider.name()).thenReturn("openai");
        when(dispatcher.selectBatchProvider(any())).thenReturn(provider);
        when(piiEnforcer.enforceBlob(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById(any())).thenReturn(Optional.empty());

        service = new BatchExecutionService(dispatcher, piiEnforcer, provider(submitGate), TestProviders.of(null),
                TestProviders.of(new WorkspaceStatusFilter(workspaces, TestProviders.of(auditWriter))),
                batchJobRepository,
                costBooker, TestProviders.of(usageListener), auditWriter, metrics);
    }

    @Test
    void uploadFile_scansBlobThenRelays() {
        when(provider.uploadFile(any(), eq("in.jsonl"), eq("batch"))).thenReturn("{\"id\":\"file_1\"}");
        String raw = service.uploadFile("hello".getBytes(StandardCharsets.UTF_8), "in.jsonl", "batch", "t1", "k1", null);
        assertThat(raw).contains("file_1");
        verify(piiEnforcer).enforceBlob("hello", "t1");
    }

    @Test
    void createBatch_refusedByTheGate_doesNotSubmit() {
        // The gate throws; what it threw is the caller's error, and nothing reaches the provider.
        // With no gate bean there is no check at all; see gateAbsent_submitsUngated.
        doThrow(new GatewayException("BUDGET_CAP_HARD", "over budget"))
                .when(submitGate).check("t1", "k1");

        assertThatThrownBy(() -> service.createBatch("{}", "t1", "k1", null))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("BUDGET_CAP_HARD"));
        verify(provider, never()).createBatch(anyString());
        verify(batchJobRepository, never()).save(any());
    }

    @Test
    void createBatch_persistsTrackingRow() {
        when(provider.createBatch(anyString()))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"validating\",\"input_file_id\":\"file_1\"}");
        service.createBatch("{}", "t1", "k1", null);
        ArgumentCaptor<BatchJob> captor = ArgumentCaptor.forClass(BatchJob.class);
        verify(batchJobRepository).save(captor.capture());
        BatchJob job = captor.getValue();
        assertThat(job.getProviderBatchId()).isEqualTo("batch_1");
        assertThat(job.getProvider()).isEqualTo("openai");
        assertThat(job.isCostBooked()).isFalse();
    }

    @Test
    void getBatch_notFound_throws() {
        when(batchJobRepository.findByProviderBatchIdAndWorkspaceId("batch_x", "t1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getBatch("batch_x", "t1"))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("BATCH_NOT_FOUND"));
    }

    @Test
    void getBatch_completed_delegatesToBookerAndRecordsMetricsOnWin() {
        stubCompletedBatch();
        CostRecord cr = CostRecord.builder().model("gpt-4o").totalCost(new BigDecimal("0.50")).build();
        when(costBooker.book(any(), any(), eq("completed"), eq("file_out")))
                .thenReturn(new BatchCostBooker.BookingResult(true, List.of(cr)));

        service.getBatch("batch_1", "t1");

        // Service sums the output file and hands per-model totals to the booker.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, long[]>> perModel = ArgumentCaptor.forClass(Map.class);
        verify(costBooker).book(any(), perModel.capture(), eq("completed"), eq("file_out"));
        assertThat(perModel.getValue().get("gpt-4o")).containsExactly(30L, 13L); // 10+20, 5+8

        // Post-commit side effects fire only for the winner.
        verify(metrics).recordCost("t1", "gpt-4o", "openai", 0.50);
        verify(usageListener).usageRecorded("t1");
        verify(auditWriter).write(argThat(e -> "BATCH_COST_BOOKED".equals(e.eventType())));
    }

    @Test
    void getBatch_completed_claimLost_noPostCommitSideEffects() {
        stubCompletedBatch();
        when(costBooker.book(any(), any(), any(), any()))
                .thenReturn(new BatchCostBooker.BookingResult(false, List.of())); // another poll won

        service.getBatch("batch_1", "t1");

        verify(metrics, never()).recordCost(any(), any(), any(), anyDouble());
        verify(usageListener, never()).usageRecorded(any());
        verify(auditWriter, never()).write(argThat(e -> "BATCH_COST_BOOKED".equals(e.eventType())));
    }

    @Test
    void getBatch_alreadyBooked_doesNotBook() {
        BatchJob job = job("batch_1", "t1", true);
        when(batchJobRepository.findByProviderBatchIdAndWorkspaceId("batch_1", "t1")).thenReturn(Optional.of(job));
        when(provider.getBatch("batch_1"))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"completed\",\"output_file_id\":\"file_out\"}");

        service.getBatch("batch_1", "t1");

        verify(costBooker, never()).book(any(), any(), any(), any());
        verify(provider, never()).getFileContent(anyString());
    }

    @Test
    void getBatch_inProgress_updatesStatusWorkspaceScoped() {
        BatchJob job = job("batch_1", "t1", false);
        when(batchJobRepository.findByProviderBatchIdAndWorkspaceId("batch_1", "t1")).thenReturn(Optional.of(job));
        when(provider.getBatch("batch_1")).thenReturn("{\"id\":\"batch_1\",\"status\":\"in_progress\"}");

        service.getBatch("batch_1", "t1");

        verify(batchJobRepository).updateStatus(eq(job.getId()), eq("t1"), eq("in_progress"), any());
        verify(costBooker, never()).book(any(), any(), any(), any());
    }

    @Test
    void getBatch_failed_claimsWithoutBooking() {
        BatchJob job = job("batch_1", "t1", false);
        when(batchJobRepository.findByProviderBatchIdAndWorkspaceId("batch_1", "t1")).thenReturn(Optional.of(job));
        when(provider.getBatch("batch_1")).thenReturn("{\"id\":\"batch_1\",\"status\":\"failed\"}");
        when(batchJobRepository.claimBooking(eq(job.getId()), eq("t1"), eq("failed"), any())).thenReturn(true);

        service.getBatch("batch_1", "t1");

        verify(batchJobRepository).claimBooking(eq(job.getId()), eq("t1"), eq("failed"), any());
        verify(costBooker, never()).book(any(), any(), any(), any());
        verify(provider, never()).getFileContent(anyString());
    }

    @Test
    void getFileContent_servesAFileThisWorkspacesBatchProduced() {
        BatchJob job = job("batch_1", "t1", false);
        job.setOutputFileId("file_out");
        when(batchJobRepository.findByFileIdAndWorkspaceId("file_out", "t1")).thenReturn(Optional.of(job));
        when(provider.getFileContent("file_out")).thenReturn(OUTPUT_JSONL.getBytes(StandardCharsets.UTF_8));

        assertThat(new String(service.getFileContent("file_out", "t1"), StandardCharsets.UTF_8))
                .contains("gpt-4o");
    }

    @Test
    void getFileContent_aFileThatIsNotThisWorkspaces_isNotFound_neverForbidden() {
        // File ids are guessable. Answering "exists but not yours" would say which of another
        // workspace's ids are real, so the miss is indistinguishable from a file that never existed.
        when(batchJobRepository.findByFileIdAndWorkspaceId("file_someone_else", "t1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getFileContent("file_someone_else", "t1"))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("No file");
        verify(provider, never()).getFileContent(anyString());
    }

    @Test
    void getFileContent_doesNotSettle_becauseADownloadIsNotEvidenceABatchFinished() {
        BatchJob job = job("batch_1", "t1", false);
        job.setOutputFileId("file_out");
        when(batchJobRepository.findByFileIdAndWorkspaceId("file_out", "t1")).thenReturn(Optional.of(job));
        when(provider.getFileContent("file_out")).thenReturn(OUTPUT_JSONL.getBytes(StandardCharsets.UTF_8));

        service.getFileContent("file_out", "t1");

        verify(provider, never()).getBatch(anyString());
        verify(costBooker, never()).book(any(), any(), any(), any());
    }

    // ---- cancel -----------------------------------------------------------

    @Test
    void cancelBatch_billsWhatAlreadyRan() {
        // The provider charges for the requests that finished before the stop and leaves them in
        // the output file, so a cancelled batch with an output file is booked like a completed one.
        BatchJob job = job("batch_1", "t1", false);
        when(batchJobRepository.findByProviderBatchIdAndWorkspaceId("batch_1", "t1")).thenReturn(Optional.of(job));
        when(provider.cancelBatch("batch_1"))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"cancelled\",\"output_file_id\":\"file_out\"}");
        when(provider.getFileContent("file_out")).thenReturn(OUTPUT_JSONL.getBytes(StandardCharsets.UTF_8));
        when(costBooker.book(any(), any(), eq("cancelled"), eq("file_out")))
                .thenReturn(new BatchCostBooker.BookingResult(true, List.of()));

        String raw = service.cancelBatch("batch_1", "t1");

        assertThat(raw).contains("cancelled");
        verify(costBooker).book(any(), any(), eq("cancelled"), eq("file_out"));
    }

    @Test
    void cancelBatch_withNoOutputFile_billsNothing() {
        BatchJob job = job("batch_1", "t1", false);
        when(batchJobRepository.findByProviderBatchIdAndWorkspaceId("batch_1", "t1")).thenReturn(Optional.of(job));
        when(provider.cancelBatch("batch_1"))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"cancelled\"}");

        service.cancelBatch("batch_1", "t1");

        verify(costBooker, never()).book(any(), any(), any(), any());
        verify(batchJobRepository).claimBooking("job-batch_1", "t1", "cancelled", null);
    }

    @Test
    void cancelBatch_anotherWorkspacesBatch_isRefusedBeforeTheProviderIsTold() {
        // Cancel is the first batch operation that destroys work rather than reading it, so
        // guessing an id must not be enough.
        when(batchJobRepository.findByProviderBatchIdAndWorkspaceId("batch_1", "intruder"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelBatch("batch_1", "intruder"))
                .isInstanceOf(GatewayException.class);
        verify(provider, never()).cancelBatch(anyString());
    }

    private void stubCompletedBatch() {
        BatchJob job = job("batch_1", "t1", false);
        when(batchJobRepository.findByProviderBatchIdAndWorkspaceId("batch_1", "t1")).thenReturn(Optional.of(job));
        when(provider.getBatch("batch_1"))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"completed\",\"output_file_id\":\"file_out\"}");
        when(provider.getFileContent("file_out")).thenReturn(OUTPUT_JSONL.getBytes(StandardCharsets.UTF_8));
    }

    private static BatchJob job(String providerBatchId, String workspaceId, boolean booked) {
        return BatchJob.builder()
                .id("job-" + providerBatchId).workspaceId(workspaceId).apiKey("k1").provider("openai")
                .providerBatchId(providerBatchId).costBooked(booked).build();
    }

    @Test
    void gateAbsent_submitsUngated() {
        // With no gate bean the submit is not checked, rather than checked by a gate that allows
        // everything.
        when(provider.createBatch(anyString()))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"validating\",\"input_file_id\":\"file_1\"}");
        var ungated = new BatchExecutionService(dispatcher, piiEnforcer, provider(null), TestProviders.of(null),
                TestProviders.of(null), batchJobRepository,
                costBooker, TestProviders.of(usageListener), auditWriter, metrics);

        ungated.createBatch("{}", "t1", "k1", null);

        verify(provider).createBatch("{}");
        verify(batchJobRepository).save(any());
    }

    // ---- model checks -----------------------------------------------------

    /** Three lines, two models; line 2 is blank, so the lines that name models are 1, 3 and 4. */
    private static final String INPUT_JSONL =
            "{\"custom_id\":\"r1\",\"method\":\"POST\",\"url\":\"/v1/chat/completions\",\"body\":{\"model\":\"gpt-4o\"}}\n"
          + "\n"
          + "{\"custom_id\":\"r3\",\"method\":\"POST\",\"url\":\"/v1/chat/completions\",\"body\":{\"model\":\"gpt-4o-mini\"}}\n"
          + "{\"custom_id\":\"r4\",\"method\":\"POST\",\"url\":\"/v1/chat/completions\",\"body\":{\"model\":\"gpt-4o\"}}\n";

    private BatchExecutionService checkedBy(BatchModelCheck check) {
        return new BatchExecutionService(dispatcher, piiEnforcer, provider(null), TestProviders.of(check),
                TestProviders.of(null), batchJobRepository, costBooker, TestProviders.of(usageListener), auditWriter, metrics);
    }

    /** A check that refuses one model the way a direct request for it is refused. */
    private static BatchModelCheck refusing(String model) {
        return (workspaceId, apiKeyId, m) -> {
            if (m.equals(model)) {
                throw new GatewayException("POLICY_DENIED", "Model " + m + " is turned off.",
                        Map.of("reason", "model_disabled", "model", m));
            }
        };
    }

    @Test
    void uploadFile_aCheckRefusesAModel_theFileIsNotSentAndTheErrorNamesTheLine() {
        BatchExecutionService checked = checkedBy(refusing("gpt-4o-mini"));

        assertThatThrownBy(() -> checked.uploadFile(INPUT_JSONL.getBytes(StandardCharsets.UTF_8),
                "in.jsonl", "batch", "t1", "k1", null))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    // The check's own refusal, so a caller handles it like a refused direct request ...
                    assertThat(e.getCode()).isEqualTo("POLICY_DENIED");
                    assertThat(e.getDetails()).containsEntry("reason", "model_disabled")
                            .containsEntry("model", "gpt-4o-mini")
                            // ... with the line that named the model added.
                            .containsEntry("line", 3)
                            .containsEntry("custom_id", "r3");
                    assertThat(e.getMessage())
                            .isEqualTo("Batch line 3 (custom_id r3): Model gpt-4o-mini is turned off.");
                });
        verify(provider, never()).uploadFile(any(), any(), any());
    }

    @Test
    void uploadFile_everyModelAllowed_asksOncePerModelAndRelaysTheFile() {
        BatchModelCheck check = mock(BatchModelCheck.class);
        when(provider.uploadFile(any(), eq("in.jsonl"), eq("batch"))).thenReturn("{\"id\":\"file_1\"}");

        String raw = checkedBy(check).uploadFile(INPUT_JSONL.getBytes(StandardCharsets.UTF_8),
                "in.jsonl", "batch", "t1", "k1", null);

        assertThat(raw).contains("file_1");
        verify(check).check("t1", "k1", "gpt-4o");
        verify(check).check("t1", "k1", "gpt-4o-mini");
        verifyNoMoreInteractions(check);
    }

    @Test
    void uploadFile_withACheck_aLineThatIsNotJsonIsRefused() {
        BatchModelCheck check = mock(BatchModelCheck.class);
        String jsonl = "{\"custom_id\":\"r1\",\"body\":{\"model\":\"gpt-4o\"}}\nnot json\n";

        assertThatThrownBy(() -> checkedBy(check).uploadFile(jsonl.getBytes(StandardCharsets.UTF_8),
                "in.jsonl", "batch", "t1", "k1", null))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                    assertThat(e.getDetails()).containsEntry("line", 2);
                    assertThat(e.getMessage()).startsWith("Batch line 2: is not a JSON object");
                });
        verify(provider, never()).uploadFile(any(), any(), any());
        verifyNoInteractions(check);
    }

    @Test
    void uploadFile_withACheck_aLineWithNoModelIsRefused() {
        String jsonl = "{\"custom_id\":\"r1\",\"body\":{\"messages\":[]}}\n";

        assertThatThrownBy(() -> checkedBy(mock(BatchModelCheck.class)).uploadFile(
                jsonl.getBytes(StandardCharsets.UTF_8), "in.jsonl", "batch", "t1", "k1", null))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_REQUEST");
                    assertThat(e.getDetails()).containsEntry("line", 1).containsEntry("custom_id", "r1");
                    assertThat(e.getMessage()).contains("names no model in body.model");
                });
        verify(provider, never()).uploadFile(any(), any(), any());
    }

    @Test
    void uploadFile_withNoCheck_theLinesAreNotRead() {
        // No check registered: a file the gateway could not read a model from is relayed as before,
        // and the provider is the one to refuse it.
        when(provider.uploadFile(any(), eq("in.jsonl"), eq("batch"))).thenReturn("{\"id\":\"file_1\"}");

        assertThat(service.uploadFile("not json".getBytes(StandardCharsets.UTF_8), "in.jsonl", "batch",
                "t1", "k1", null)).contains("file_1");
    }

    @Test
    void uploadFile_forAnotherPurpose_isNotChecked() {
        BatchModelCheck check = mock(BatchModelCheck.class);
        when(provider.uploadFile(any(), eq("train.jsonl"), eq("fine-tune"))).thenReturn("{\"id\":\"file_2\"}");

        checkedBy(check).uploadFile("{\"messages\":[]}".getBytes(StandardCharsets.UTF_8),
                "train.jsonl", "fine-tune", "t1", "k1", null);

        verifyNoInteractions(check);
    }

    @Test
    void createBatch_aModelTurnedOffAfterUpload_isRefusedAtSubmit() {
        // The file was accepted earlier; the model has been turned off since. The submit reads the
        // file back and asks again, and nothing reaches the provider's batch endpoint.
        when(provider.getFileContent("file_1")).thenReturn(INPUT_JSONL.getBytes(StandardCharsets.UTF_8));
        BatchExecutionService checked = checkedBy(refusing("gpt-4o"));

        assertThatThrownBy(() -> checked.createBatch(
                "{\"input_file_id\":\"file_1\",\"endpoint\":\"/v1/chat/completions\"}", "t1", "k1", null))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("POLICY_DENIED");
                    assertThat(e.getDetails()).containsEntry("line", 1).containsEntry("model", "gpt-4o");
                });
        verify(provider, never()).createBatch(anyString());
        verify(batchJobRepository, never()).save(any());
    }

    @Test
    void createBatch_everyModelAllowed_submits() {
        when(provider.getFileContent("file_1")).thenReturn(INPUT_JSONL.getBytes(StandardCharsets.UTF_8));
        when(provider.createBatch(anyString()))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"validating\",\"input_file_id\":\"file_1\"}");

        checkedBy(refusing("some-other-model")).createBatch("{\"input_file_id\":\"file_1\"}", "t1", "k1", null);

        verify(provider).createBatch("{\"input_file_id\":\"file_1\"}");
        verify(batchJobRepository).save(any());
    }

    @Test
    void createBatch_theProviderWillNotReturnTheFile_submitsOnTheUploadCheck() {
        // Reading the file back is a second look, not the only one: if the provider refuses to serve
        // it, the submit goes ahead and the check made at upload stands.
        BatchModelCheck check = mock(BatchModelCheck.class);
        when(provider.getFileContent("file_1"))
                .thenThrow(GatewayException.upstream(400, "Not allowed to download files of purpose: batch"));
        when(provider.createBatch(anyString()))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"validating\",\"input_file_id\":\"file_1\"}");

        checkedBy(check).createBatch("{\"input_file_id\":\"file_1\"}", "t1", "k1", null);

        verify(provider).createBatch("{\"input_file_id\":\"file_1\"}");
        verify(batchJobRepository).save(any());
        verifyNoInteractions(check);
    }

    @Test
    void createBatch_withNoCheck_doesNotReadTheFile() {
        when(provider.createBatch(anyString()))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"validating\",\"input_file_id\":\"file_1\"}");

        service.createBatch("{\"input_file_id\":\"file_1\"}", "t1", "k1", null);

        verify(provider, never()).getFileContent(anyString());
    }

    // ---- suspended workspace ----------------------------------------------

    private void suspend(String workspaceId) {
        when(workspaces.findById(workspaceId)).thenReturn(Optional.of(Workspace.builder()
                .id(workspaceId).name(workspaceId).status(WorkspaceStatus.SUSPENDED)
                .metadata(Map.of("suspendedReason", "unpaid")).build()));
    }

    @Test
    void uploadFile_suspendedWorkspace_isRefusedAsADirectRequestIs() {
        suspend("t1");

        assertThatThrownBy(() -> service.uploadFile("{}".getBytes(StandardCharsets.UTF_8), "in.jsonl",
                "batch", "t1", "k1", null))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("WORKSPACE_SUSPENDED");
                    assertThat(e.getMessage()).contains("Workspace t1 has been suspended (reason: unpaid)");
                });
        verify(provider, never()).uploadFile(any(), any(), any());
        verify(piiEnforcer, never()).enforceBlob(anyString(), any());
        verify(auditWriter).write(argThat(e -> "WORKSPACE_SUSPENDED_BLOCK".equals(e.eventType())
                && "t1".equals(e.workspaceId())));
    }

    @Test
    void createBatch_suspendedWorkspace_isRefusedBeforeTheProvider() {
        suspend("t1");

        assertThatThrownBy(() -> service.createBatch("{\"input_file_id\":\"file_1\"}", "t1", "k1", null))
                .isInstanceOfSatisfying(GatewayException.class,
                        e -> assertThat(e.getCode()).isEqualTo("WORKSPACE_SUSPENDED"));
        verify(provider, never()).createBatch(anyString());
        verify(batchJobRepository, never()).save(any());
        verify(submitGate, never()).check(any(), any());
    }

    @Test
    void suspendedWorkspace_canStillReadAndCancelItsBatches() {
        // Retrieve, cancel and download start no new spend, and a request filter does not stop reads
        // either. Cancel can only lower what the workspace spends.
        suspend("t1");
        BatchJob job = job("batch_1", "t1", false);
        when(batchJobRepository.findByProviderBatchIdAndWorkspaceId("batch_1", "t1")).thenReturn(Optional.of(job));
        when(batchJobRepository.findByFileIdAndWorkspaceId("file_1", "t1")).thenReturn(Optional.of(job));
        when(provider.getBatch("batch_1")).thenReturn("{\"id\":\"batch_1\",\"status\":\"in_progress\"}");
        when(provider.cancelBatch("batch_1")).thenReturn("{\"id\":\"batch_1\",\"status\":\"cancelling\"}");
        when(provider.getFileContent("file_1")).thenReturn("x".getBytes(StandardCharsets.UTF_8));

        assertThat(service.getBatch("batch_1", "t1")).contains("in_progress");
        assertThat(service.cancelBatch("batch_1", "t1")).contains("cancelling");
        assertThat(service.getFileContent("file_1", "t1")).isNotEmpty();
    }

    @Test
    void uploadFile_suspendedWorkspace_forAnotherPurpose_isNotRefusedHere() {
        suspend("t1");
        when(provider.uploadFile(any(), eq("train.jsonl"), eq("fine-tune"))).thenReturn("{\"id\":\"file_2\"}");

        assertThat(service.uploadFile("{}".getBytes(StandardCharsets.UTF_8), "train.jsonl", "fine-tune",
                "t1", "k1", null)).contains("file_2");
    }

    @Test
    void activeWorkspace_uploadsAndSubmitsAsBefore() {
        when(workspaces.findById("t1")).thenReturn(Optional.of(Workspace.builder()
                .id("t1").name("t1").status(WorkspaceStatus.ACTIVE).build()));
        when(provider.uploadFile(any(), eq("in.jsonl"), eq("batch"))).thenReturn("{\"id\":\"file_1\"}");
        when(provider.createBatch(anyString()))
                .thenReturn("{\"id\":\"batch_1\",\"status\":\"validating\",\"input_file_id\":\"file_1\"}");

        service.uploadFile("{}".getBytes(StandardCharsets.UTF_8), "in.jsonl", "batch", "t1", "k1", null);
        service.createBatch("{\"input_file_id\":\"file_1\"}", "t1", "k1", null);

        verify(provider).createBatch("{\"input_file_id\":\"file_1\"}");
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T value) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }

    // ---- listing ----------------------------------------------------------

    private static BatchJob job(String providerBatchId) {
        return BatchJob.builder().id("j-" + providerBatchId).workspaceId("acme")
                .provider("openai").providerBatchId(providerBatchId).status("in_progress").build();
    }

    @Test
    void listBatches_fetchesEachEntryFromTheProvider_ratherThanRenderingTheTrackingRow() {
        // A tracking row carries no request_counts, endpoint or lifecycle timestamps. Rendering one
        // as a batch object would hand the caller plausible zeroes, so the body must be the
        // provider's own.
        when(batchJobRepository.findByWorkspaceId("acme")).thenReturn(List.of(job("b1"), job("b2")));
        when(provider.getBatch("b1")).thenReturn("{\"id\":\"b1\",\"request_counts\":{\"total\":7}}");
        when(provider.getBatch("b2")).thenReturn("{\"id\":\"b2\",\"request_counts\":{\"total\":9}}");

        String raw = service.listBatches("acme", null, null);

        assertThat(raw).contains("\"object\":\"list\"")
                .contains("\"total\":7").contains("\"total\":9")
                .contains("\"first_id\":\"b1\"").contains("\"last_id\":\"b2\"")
                .contains("\"has_more\":false");
    }

    @Test
    void listBatches_pagesWithLimitAndAfter_andReportsHasMore() {
        when(batchJobRepository.findByWorkspaceId("acme"))
                .thenReturn(List.of(job("b1"), job("b2"), job("b3")));
        when(provider.getBatch(anyString())).thenAnswer(i -> "{\"id\":\"" + i.getArgument(0) + "\"}");

        assertThat(service.listBatches("acme", 1, null))
                .contains("\"first_id\":\"b1\"").contains("\"has_more\":true");

        // after=b1 starts at b2; two remain, so a page of 1 still has more.
        assertThat(service.listBatches("acme", 1, "b1"))
                .contains("\"first_id\":\"b2\"").contains("\"has_more\":true");

        // the last page reports no more
        assertThat(service.listBatches("acme", 1, "b2"))
                .contains("\"first_id\":\"b3\"").contains("\"has_more\":false");
    }

    @Test
    void listBatches_clampsAnAbsurdLimit_ratherThanFetchingThatMany() {
        when(batchJobRepository.findByWorkspaceId("acme")).thenReturn(List.of(job("b1")));
        when(provider.getBatch(anyString())).thenReturn("{\"id\":\"b1\"}");

        service.listBatches("acme", 100000, null);

        // one upstream call per entry on the page is the cost model, so the ceiling is the guard
        verify(provider, times(1)).getBatch(anyString());
    }

    @Test
    void listBatches_oneUnreachableBatch_doesNotFailTheWholePage() {
        // An expired batch the provider has forgotten must not make the workspace's list unreadable.
        when(batchJobRepository.findByWorkspaceId("acme")).thenReturn(List.of(job("gone"), job("b2")));
        when(provider.getBatch("gone")).thenThrow(new RuntimeException("404 from upstream"));
        when(provider.getBatch("b2")).thenReturn("{\"id\":\"b2\"}");

        String raw = service.listBatches("acme", null, null);

        assertThat(raw).contains("\"id\":\"b2\"").doesNotContain("gone");
        assertThat(raw).contains("\"first_id\":\"b2\"");
    }

    @Test
    void listBatches_doesNotBookACompletedEntry_becauseBookingDownloadsTheOutputFile() {
        // Booking sums per-line usage out of the output file, so settling here would download every
        // completed batch on the page. Metering stays on retrieve/results, where it costs one file.
        when(batchJobRepository.findByWorkspaceId("acme")).thenReturn(List.of(job("done")));
        when(provider.getBatch("done"))
                .thenReturn("{\"id\":\"done\",\"status\":\"completed\",\"output_file_id\":\"f_out\"}");

        String raw = service.listBatches("acme", null, null);

        assertThat(raw).contains("\"id\":\"done\"");
        verify(provider, never()).getFileContent(anyString());
        verify(batchJobRepository, never()).claimBooking(anyString(), anyString(), anyString(), anyString());
    }

}
