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
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import com.dvarahq.server.web.RateLimitServletFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A streaming call settles the caller's token window the way a non-streaming one does.
 *
 * <p>The rate-limit filter reserves an estimate for a chat request at admission and stores what it
 * reserved on the request; the streaming tail settles reserved against actual once usage is known.
 * A Responses stream reserves nothing, so it settles against zero.
 */
class StreamingTokenSettlementTest {

    private RateLimiter rateLimiter;
    private TokenUsageRepository tokenUsageRepository;
    private CostCalculationService costCalculationService;
    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        tokenUsageRepository = mock(TokenUsageRepository.class);
        costCalculationService = mock(CostCalculationService.class);
        when(costCalculationService.calculateAndPersist(any(), any(), any(), any(), any())).thenReturn(java.util.Optional.empty());
        TokenEstimator tokenEstimator = mock(TokenEstimator.class);
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(40);
        when(tokenEstimator.estimateTokens(anyString())).thenReturn(60);
        service = new ChatExecutionService(
                mock(ProviderDispatcher.class), mock(RequestPipeline.class), TestProviders.of(mock(ResponseCache.class)),
                tokenUsageRepository, TestProviders.of(mock(WorkspaceUsageListener.class)),
                TestProviders.of(costCalculationService), TestProviders.of(mock(CostEstimator.class)), mock(PiiEnforcer.class),
                rateLimiter, mock(StreamingResponseEnforcer.class), mock(AuditWriter.class),
                TestProviders.of(mock(PriorityAdmissionController.class)), mock(GatewayMetrics.class),
                tokenEstimator, TestProviders.of(CallOutcomeListener.NOOP));
    }

    private static HttpServletRequest request(String apiKey, Integer reserved) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        // What ApiKeyAuthFilter stamps on a served request: the limiter's bucket, the key id and
        // the workspace. A null apiKey models a request the filter never saw.
        when(request.getAttribute(RateLimitServletFilter.API_KEY_ATTR)).thenReturn(apiKey);
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR)).thenReturn(apiKey);
        when(request.getAttribute(ApiKeyAuthFilter.WORKSPACE_ID_ATTR)).thenReturn(apiKey == null ? null : "acme");
        when(request.getAttribute(RateLimitServletFilter.RESERVED_TOKENS_ATTR)).thenReturn(reserved);
        when(request.getAttribute(RateLimitServletFilter.RESERVATION_ID_ATTR))
                .thenReturn(reserved == null ? null : 7_001L);
        return request;
    }

    private static ChatResponse.Usage usage(int prompt, int completion) {
        return ChatResponse.Usage.builder()
                .promptTokens(prompt).completionTokens(completion).totalTokens(prompt + completion).build();
    }

    /** The controllers' shape: the settlement was captured before the emit loop, off a live request. */
    private void stream(HttpServletRequest request, String output, ChatResponse.Usage reported) {
        ChatExecutionService.TokenSettlement settlement = ChatExecutionService.TokenSettlement.capture(request);
        service.persistStreamingUsage(request, ChatRequest.builder().model("gpt-4o").build(),
                output, FilterContext.builder().workspaceId("acme").build(), 12L, false, reported, settlement);
    }

    /** A chat stream that reserved 500 is charged what it used, not the 500. */
    @Test
    void aChatStreamSettlesItsReservationAgainstTheReportedUsage() {
        stream(request("key-1", 500), "hello", usage(80, 40));
        verify(rateLimiter).reconcileTokens("key-1", 500, 120, 7_001L);
    }

    /** A Responses stream reserves nothing, so it settles against zero. */
    @Test
    void aStreamWithNoReservationIsChargedItsActualOnce() {
        stream(request("key-1", null), "hello", usage(80, 40));
        verify(rateLimiter).reconcileTokens("key-1", 0, 120, 0L);
    }

    /** No usage block from the upstream: the settled figure is the estimate the row is marked with. */
    @Test
    void withoutAnUpstreamUsageBlockTheEstimateIsSettled() {
        stream(request("key-1", 500), "hello", null);
        verify(rateLimiter).reconcileTokens("key-1", 500, 100, 7_001L);
    }

    /** A stream with no key behind it was never authenticated; it cannot be settled under any bucket. */
    @Test
    void aStreamWithNoKeyCannotBeSettled() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> stream(request(null, 500), "hello", usage(80, 40)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ApiKeyAuthFilter");
        verify(rateLimiter, never()).reconcileTokens(any(), anyInt(), anyInt(), anyLong());
    }

    /**
     * A stream that emitted nothing keeps its reservation as charged, deliberately: the prompt may
     * have reached the upstream, what it consumed is unknowable at this point, and refunding it
     * would under-charge a caller whose streams keep failing. Bounded to one window either way.
     */
    @Test
    void aStreamThatEmittedNothingAndReportedNothingLeavesTheReservationCharged() {
        stream(request("key-1", 500), "", null);
        verifyNoInteractions(rateLimiter);
    }

    /** A refusal by deferred enforcement delivers no text but the upstream was consumed whole. */
    @Test
    void aStreamThatDeliveredNoTextButReportedUsageStillSettles() {
        stream(request("key-1", 500), "", usage(80, 40));
        verify(rateLimiter).reconcileTokens("key-1", 500, 120, 7_001L);
        org.mockito.ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> row =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository).save(row.capture());
        assertThat(row.getValue().isEstimated()).isFalse();
        assertThat(row.getValue().getTotalTokens()).isEqualTo(120);
        verify(costCalculationService).calculateAndPersist(any(), any(), any(), any(), any());
    }

    /** ... and one that delivered no text and reported nothing writes no row and books no cost. */
    @Test
    void aStreamThatDeliveredNoTextAndReportedNothingWritesNothing() {
        stream(request("key-1", 500), "", null);
        verifyNoInteractions(tokenUsageRepository, costCalculationService);
    }

    /**
     * On a client disconnect or the emitter timeout the container completes first, and the tail may
     * meet a recycled request. The settlement comes from the snapshot the controller captured before
     * the emit loop, never from the request the tail is handed.
     */
    @Test
    void theTailSettlesFromTheSnapshotNotFromTheRequestItIsHanded() {
        ChatExecutionService.TokenSettlement captured =
                ChatExecutionService.TokenSettlement.capture(request("key-1", 500));
        HttpServletRequest recycled = request(null, null);
        service.persistStreamingUsage(recycled, ChatRequest.builder().model("gpt-4o").build(),
                "hello", FilterContext.builder().workspaceId("acme").build(), 12L, false, usage(80, 40), captured);
        verify(rateLimiter).reconcileTokens("key-1", 500, 120, 7_001L);
    }

    @Test
    void aSnapshotOfARequestWithNoKeyIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ChatExecutionService.TokenSettlement.capture(request(null, null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aSnapshotOfARequestWithAKeyAndNoReservationIsZero() {
        ChatExecutionService.TokenSettlement captured =
                ChatExecutionService.TokenSettlement.capture(request("key-1", null));
        org.assertj.core.api.Assertions.assertThat(captured)
                .isEqualTo(new ChatExecutionService.TokenSettlement("key-1", 0, 0L));
    }
}
