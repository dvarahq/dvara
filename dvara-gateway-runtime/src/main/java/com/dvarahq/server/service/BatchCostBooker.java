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

import com.dvarahq.core.batch.BatchJob;
import com.dvarahq.core.batch.BatchJobRepository;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostRecord;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.metering.TokenUsageRecord;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.providers.support.CredentialInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Transactional booking step for a completed batch. Kept as a separate bean (not a private
 * method on {@link BatchExecutionService}) so Spring's {@code @Transactional} proxy actually applies
 * — a self-invocation would bypass it.
 *
 * <p>The whole thing is one transaction that <b>claims then books</b>:
 * <ol>
 *   <li>{@link BatchJobRepository#claimBooking} does an atomic conditional flip of {@code cost_booked}
 *       false→true. Exactly one of any set of concurrent completion polls wins; the losers get
 *       {@code false} and skip (no double-book).</li>
 *   <li>Only the winner persists the per-model usage + cost. If any save throws, the transaction —
 *       <em>including the claim</em> — rolls back, so {@code cost_booked} returns to false and the
 *       next poll retries cleanly (no partial book, no re-book of already-persisted rows).</li>
 * </ol>
 *
 * <p>Prometheus cost metrics, threshold emission, and audit are post-commit side-effects and are
 * done by the caller from {@link BookingResult}, not here.</p>
 */
@Component
public class BatchCostBooker {

    private static final Logger log = LoggerFactory.getLogger(BatchCostBooker.class);

    private final BatchJobRepository batchJobRepository;
    private final TokenUsageRepository tokenUsageRepository;
    /** Null when nothing here can price a call. Usage rows are still written; cost rows are not. */
    private final CostCalculationService costCalculationService;

    public BatchCostBooker(BatchJobRepository batchJobRepository, TokenUsageRepository tokenUsageRepository,
                           org.springframework.beans.factory.ObjectProvider<CostCalculationService>
                                   costCalculationService) {
        this.batchJobRepository = batchJobRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.costCalculationService = costCalculationService.getIfAvailable();
    }

    /** Outcome of a booking attempt. {@code claimed=false} means another poll already booked. */
    public record BookingResult(boolean claimed, List<CostRecord> costRecords) {
        static BookingResult notClaimed() {
            return new BookingResult(false, List.of());
        }
    }

    /**
     * @param perModel model → {@code [inputTokens, outputTokens]} summed from the output file
     */
    @Transactional
    public BookingResult book(BatchJob job, Map<String, long[]> perModel, String status, String outputFileId) {
        if (!batchJobRepository.claimBooking(job.getId(), job.getWorkspaceId(), status, outputFileId)) {
            return BookingResult.notClaimed();
        }
        // Which upstream credential this batch's traffic went out under; a null would read as "made
        // no attributable upstream call" in the by-credential usage query. This is the credential in
        // use at poll time (booking runs on a retrieve or cancel request), which is the submitting
        // credential unless the workspace rotated in between. Null when no request is bound.
        String credentialFingerprint = CredentialInterceptor.resolveFingerprint();

        List<CostRecord> costRecords = new ArrayList<>();
        for (Map.Entry<String, long[]> e : perModel.entrySet()) {
            String model = e.getKey();
            int in = clampToInt(e.getValue()[0], job, model, "input");
            int out = clampToInt(e.getValue()[1], job, model, "output");

            tokenUsageRepository.save(TokenUsageRecord.builder()
                    .id(Ids.newId())
                    .workspaceId(job.getWorkspaceId())
                    .apiKey(job.getApiKey())
                    .model(model)
                    .provider(job.getProvider())
                    .inputTokens(in)
                    .outputTokens(out)
                    .totalTokens(in + out)
                    .estimated(false)
                    .cacheStatus("MISS")
                    .credentialFingerprint(credentialFingerprint)
                    .timestamp(Instant.now())
                    .build());

            // Reuse the pricing engine via a synthetic request/response carrying the summed usage.
            ChatRequest synthReq = ChatRequest.builder().model(model).build();
            ChatResponse synthResp = ChatResponse.builder()
                    .model(model)
                    .usage(ChatResponse.Usage.builder()
                            .promptTokens(in).completionTokens(out).totalTokens(in + out).build())
                    .build();
            if (costCalculationService != null) {
                costCalculationService.calculateAndPersist(synthReq, synthResp,
                        job.getWorkspaceId(), job.getApiKey(), job.getProvider())
                        .ifPresent(costRecords::add);
            }
        }
        return new BookingResult(true, costRecords);
    }

    /** {@code TokenUsageRecord} counts are {@code int}; clamp a summed long that overflows, loudly. */
    private static int clampToInt(long value, BatchJob job, String model, String direction) {
        if (value > Integer.MAX_VALUE) {
            log.warn("Batch {} {} token sum for model {} = {} exceeds int range; clamping to {}. "
                            + "Usage/cost for this batch are under-counted.",
                    job.getProviderBatchId(), direction, model, value, Integer.MAX_VALUE);
            return Integer.MAX_VALUE;
        }
        return (int) value;
    }
}