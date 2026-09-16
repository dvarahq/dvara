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
import com.dvarahq.core.batch.BatchSubmitGate;
import com.dvarahq.core.cost.CostRecord;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.metering.WorkspaceUsageListener;
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

        service = new BatchExecutionService(dispatcher, piiEnforcer, provider(submitGate), batchJobRepository,
                costBooker, TestProviders.of(usageListener), auditWriter, metrics);
    }

    @Test
    void uploadFile_scansBlobThenRelays() {
        when(provider.uploadFile(any(), eq("in.jsonl"), eq("batch"))).thenReturn("{\"id\":\"file_1\"}");
        String raw = service.uploadFile("hello".getBytes(StandardCharsets.UTF_8), "in.jsonl", "batch", "t1", null);
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
        var ungated = new BatchExecutionService(dispatcher, piiEnforcer, provider(null), batchJobRepository,
                costBooker, TestProviders.of(usageListener), auditWriter, metrics);

        ungated.createBatch("{}", "t1", "k1", null);

        verify(provider).createBatch("{}");
        verify(batchJobRepository).save(any());
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
