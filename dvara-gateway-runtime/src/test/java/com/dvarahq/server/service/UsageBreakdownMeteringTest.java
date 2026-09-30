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
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The usage row and the cost calculation see the cached-input, cache-write and reasoning counts the
 * upstream reported, on the streamed path as on the others; an estimate carries none.
 */
class UsageBreakdownMeteringTest {

    private TokenUsageRepository tokenUsageRepository;
    private CostCalculationService costCalculationService;
    private ChatExecutionService service;

    @BeforeEach
    void setUp() {
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
                mock(RateLimiter.class), mock(StreamingResponseEnforcer.class), mock(AuditWriter.class),
                TestProviders.of(mock(PriorityAdmissionController.class)), mock(GatewayMetrics.class),
                tokenEstimator, TestProviders.of(CallOutcomeListener.NOOP));
    }

    /** A live request, as the controller sees it once the stream is open. */
    private static HttpServletRequest live() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(com.dvarahq.server.web.ApiKeyAuthFilter.API_KEY_ID_ATTR)).thenReturn("key-1");
        when(request.getAttribute("workspaceId")).thenReturn("ws-1");
        when(request.getAttribute(com.dvarahq.server.web.AccessLogFilter.ATTR_PROVIDER)).thenReturn("openai");
        when(request.getAttribute(com.dvarahq.providers.support.CredentialInterceptor.FINGERPRINT_ATTRIBUTE)).thenReturn("fp-9");
        return request;
    }

    @Test
    void aStreamedCallsUsageRowAndCostCarryTheBreakdown() {
        ChatExecutionService.Attribution who = ChatExecutionService.Attribution.capture(live());
        ChatExecutionService.TokenSettlement settlement = new ChatExecutionService.TokenSettlement("key-1", 0, 0L);
        ChatResponse.Usage reported = ChatResponse.Usage.builder()
                .promptTokens(1200).completionTokens(500).totalTokens(1700)
                .cachedInputTokens(1024).cacheWriteTokens(100).reasoningTokens(448).build();

        service.persistStreamingUsage(live(), ChatRequest.builder().model("o3").build(),
                "hello", FilterContext.builder().workspaceId("ws-1").build(), 12L, false, reported, settlement, who);

        org.mockito.ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> row =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository).save(row.capture());
        assertThat(row.getValue().getInputTokens()).isEqualTo(1200);
        assertThat(row.getValue().getOutputTokens()).isEqualTo(500);
        assertThat(row.getValue().getTotalTokens()).isEqualTo(1700);
        assertThat(row.getValue().getCachedInputTokens()).isEqualTo(1024);
        assertThat(row.getValue().getCacheWriteTokens()).isEqualTo(100);
        assertThat(row.getValue().getReasoningTokens()).isEqualTo(448);

        org.mockito.ArgumentCaptor<ChatResponse> priced = org.mockito.ArgumentCaptor.forClass(ChatResponse.class);
        verify(costCalculationService).calculateAndPersist(any(), priced.capture(), any(), any(), any());
        assertThat(priced.getValue().getUsage().getCachedInputTokens()).isEqualTo(1024);
        assertThat(priced.getValue().getUsage().getCacheWriteTokens()).isEqualTo(100);
        assertThat(priced.getValue().getUsage().getReasoningTokens()).isEqualTo(448);
    }

    @Test
    void anEstimatedStreamCarriesNoBreakdown() {
        ChatExecutionService.Attribution who = ChatExecutionService.Attribution.capture(live());
        ChatExecutionService.TokenSettlement settlement = new ChatExecutionService.TokenSettlement("key-1", 0, 0L);

        service.persistStreamingUsage(live(), ChatRequest.builder().model("o3").build(),
                "hello", FilterContext.builder().workspaceId("ws-1").build(), 12L, false, null, settlement, who);

        org.mockito.ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> row =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository).save(row.capture());
        assertThat(row.getValue().isEstimated()).isTrue();
        assertThat(row.getValue().getCachedInputTokens()).isZero();
        assertThat(row.getValue().getCacheWriteTokens()).isZero();
        assertThat(row.getValue().getReasoningTokens()).isZero();
    }
}
