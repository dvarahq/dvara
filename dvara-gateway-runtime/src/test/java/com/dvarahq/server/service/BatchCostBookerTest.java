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
import com.dvarahq.core.batch.BatchJob;
import com.dvarahq.core.batch.BatchJobRepository;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostRecord;
import com.dvarahq.core.metering.TokenUsageRecord;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.providers.support.CredentialInterceptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BatchCostBookerTest {

    private BatchJobRepository batchJobRepository;
    private TokenUsageRepository tokenUsageRepository;
    private CostCalculationService costCalculationService;
    private BatchCostBooker booker;

    @BeforeEach
    void setUp() {
        batchJobRepository = mock(BatchJobRepository.class);
        tokenUsageRepository = mock(TokenUsageRepository.class);
        costCalculationService = mock(CostCalculationService.class);
        when(costCalculationService.calculateAndPersist(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        booker = new BatchCostBooker(batchJobRepository, tokenUsageRepository, TestProviders.of(costCalculationService));
    }

    @Test
    void book_claimLost_persistsNothing() {
        BatchJob job = job();
        when(batchJobRepository.claimBooking(any(), any(), any(), any())).thenReturn(false);

        BatchCostBooker.BookingResult result = booker.book(job, perModel(10, 5), "completed", "file_out");

        assertThat(result.claimed()).isFalse();
        verify(tokenUsageRepository, never()).save(any());
        verify(costCalculationService, never()).calculateAndPersist(any(), any(), any(), any(), any());
    }

    @Test
    void book_claimWon_persistsUsageAndCostPerModel() {
        BatchJob job = job();
        when(batchJobRepository.claimBooking(eq("job-1"), eq("t1"), eq("completed"), eq("file_out"))).thenReturn(true);
        CostRecord cr = CostRecord.builder().model("gpt-4o").totalCost(new BigDecimal("0.5")).build();
        when(costCalculationService.calculateAndPersist(any(), any(), eq("t1"), eq("k1"), eq("openai")))
                .thenReturn(Optional.of(cr));

        BatchCostBooker.BookingResult result = booker.book(job, perModel(30, 13), "completed", "file_out");

        assertThat(result.claimed()).isTrue();
        assertThat(result.costRecords()).containsExactly(cr);
        ArgumentCaptor<TokenUsageRecord> usage = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(tokenUsageRepository).save(usage.capture());
        assertThat(usage.getValue().getModel()).isEqualTo("gpt-4o");
        assertThat(usage.getValue().getInputTokens()).isEqualTo(30);
        assertThat(usage.getValue().getOutputTokens()).isEqualTo(13);
        assertThat(usage.getValue().isEstimated()).isFalse();
        assertThat(usage.getValue().getCacheStatus()).isEqualTo("MISS");
    }

    @AfterEach
    void clearRequestContext() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    /**
     * A batch goes upstream through the provider's own RestClient, so the credential interceptor
     * resolves a secret for it. The usage row carries that credential's fingerprint so the batch can
     * be found by credential.
     */
    @Test
    void book_stampsTheCredentialTheBatchWentOutUnder() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(CredentialInterceptor.FINGERPRINT_ATTRIBUTE, "fp-abc123");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));

        when(batchJobRepository.claimBooking(any(), any(), any(), any())).thenReturn(true);

        booker.book(job(), perModel(30, 13), "completed", "file_out");

        ArgumentCaptor<TokenUsageRecord> usage = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(tokenUsageRepository).save(usage.capture());
        assertThat(usage.getValue().getCredentialFingerprint()).isEqualTo("fp-abc123");
    }

    /**
     * With no request bound to the thread, nothing knows which credential was used, so the
     * fingerprint stays null rather than being invented.
     */
    @Test
    void book_withNoRequestBound_leavesTheFingerprintNull() {
        when(batchJobRepository.claimBooking(any(), any(), any(), any())).thenReturn(true);

        booker.book(job(), perModel(30, 13), "completed", "file_out");

        ArgumentCaptor<TokenUsageRecord> usage = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(tokenUsageRepository).save(usage.capture());
        assertThat(usage.getValue().getCredentialFingerprint()).isNull();
    }

    @Test
    void book_overflowingTokenSum_clampsToIntMax() {
        BatchJob job = job();
        when(batchJobRepository.claimBooking(any(), any(), any(), any())).thenReturn(true);
        Map<String, long[]> perModel = new LinkedHashMap<>();
        perModel.put("gpt-4o", new long[]{ (long) Integer.MAX_VALUE + 100L, 5L });

        booker.book(job, perModel, "completed", "file_out");

        ArgumentCaptor<TokenUsageRecord> usage = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(tokenUsageRepository).save(usage.capture());
        assertThat(usage.getValue().getInputTokens()).isEqualTo(Integer.MAX_VALUE); // clamped, not wrapped negative
    }

    private static Map<String, long[]> perModel(long in, long out) {
        Map<String, long[]> m = new LinkedHashMap<>();
        m.put("gpt-4o", new long[]{ in, out });
        return m;
    }

    private static BatchJob job() {
        return BatchJob.builder()
                .id("job-1").workspaceId("t1").apiKey("k1").provider("openai").providerBatchId("batch_1").build();
    }
}