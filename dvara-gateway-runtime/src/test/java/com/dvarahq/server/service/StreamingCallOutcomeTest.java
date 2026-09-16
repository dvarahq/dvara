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
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.server.metrics.GatewayMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The streaming tail is the only place a stream's outcome can be reported, since streaming has no
 * post-dispatch pass. These pin what the outcome listener is told there.
 */
class StreamingCallOutcomeTest {

    private CallOutcomeListener listener;
    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
        listener = mock(CallOutcomeListener.class);
        CostEstimator costEstimator = mock(CostEstimator.class);
        TokenEstimator tokenEstimator = mock(TokenEstimator.class);
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(40);
        when(tokenEstimator.estimateTokens(anyString())).thenReturn(60);
        when(costEstimator.calculateActualCost(any(), any())).thenReturn(0.0072);
        service = new ChatExecutionService(
                mock(ProviderDispatcher.class), mock(RequestPipeline.class), TestProviders.of(mock(ResponseCache.class)),
                mock(TokenUsageRepository.class), TestProviders.of(mock(WorkspaceUsageListener.class)),
                TestProviders.of(mock(CostCalculationService.class)), TestProviders.of(costEstimator), mock(PiiEnforcer.class),
                mock(RateLimiter.class), mock(StreamingResponseEnforcer.class), mock(AuditWriter.class),
                TestProviders.of(mock(PriorityAdmissionController.class)), mock(GatewayMetrics.class),
                tokenEstimator, TestProviders.of(listener));
    }

    private void stream(FilterContext ctx, String output, long latencyMs, boolean error) {
        service.persistStreamingUsage(mock(HttpServletRequest.class),
                ChatRequest.builder().model("gpt-4o").build(), output, ctx, latencyMs, error);
    }

    @Test
    void aStreamIsReportedWithItsLatencyCostAndEstimatedTokens() {
        FilterContext ctx = FilterContext.builder().workspaceId("acme").build();

        stream(ctx, "hello there", 812L, false);

        // 40 input + 60 output, estimated because no usage block was reported
        verify(listener).callCompleted(same(ctx), eq(812L), eq(0.0072), eq(100L), eq(false), eq(true));
    }

    @Test
    void aStreamThatFailedPartwayIsReportedAsAnErrorWithTheTokensItEmitted() {
        FilterContext ctx = FilterContext.builder().workspaceId("acme").build();

        stream(ctx, "half an ans", 90L, true);

        verify(listener).callCompleted(same(ctx), eq(90L), eq(0.0072), eq(100L), eq(true), eq(true));
    }

    @Test
    void aStreamThatEmittedNothingIsNotReported() {
        stream(FilterContext.builder().workspaceId("acme").build(), "", 40L, true);

        verifyNoInteractions(listener);
    }

    @Test
    void aStreamWithNoContextIsStillReported() {
        stream(null, "hello there", 55L, false);

        verify(listener).callCompleted(isNull(), eq(55L), eq(0.0072), eq(100L), eq(false), eq(true));
    }
}
