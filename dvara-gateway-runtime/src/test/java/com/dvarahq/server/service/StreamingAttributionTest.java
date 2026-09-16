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
 * The streaming tail attributes the usage row and the cost record from a snapshot taken while the
 * request was live, never from the request it is handed. On a client disconnect or the emitter
 * timeout the container completes first and may recycle the request before the tail runs, and a
 * recycled request answers every access with nulls or an exception.
 */
class StreamingAttributionTest {

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

    /** What Tomcat hands a late reader: every access refuses. */
    private static HttpServletRequest recycled() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(anyString())).thenThrow(new IllegalStateException("The request object has been recycled"));
        org.mockito.Mockito.doThrow(new IllegalStateException("The request object has been recycled"))
                .when(request).setAttribute(anyString(), any());
        return request;
    }

    private static ChatResponse.Usage usage(int prompt, int completion) {
        return ChatResponse.Usage.builder()
                .promptTokens(prompt).completionTokens(completion).totalTokens(prompt + completion).build();
    }

    @Test
    void theTailAttributesFromTheSnapshot_evenWhenTheRequestIsRecycled() {
        ChatExecutionService.Attribution who = ChatExecutionService.Attribution.capture(live());
        ChatExecutionService.TokenSettlement settlement = new ChatExecutionService.TokenSettlement("key-1", 0, 0L);

        service.persistStreamingUsage(recycled(), ChatRequest.builder().model("gpt-4o").build(),
                "hello", FilterContext.builder().workspaceId("ws-1").build(), 12L, false, usage(80, 40), settlement, who);

        org.mockito.ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> row =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository).save(row.capture());
        org.assertj.core.api.Assertions.assertThat(row.getValue().getWorkspaceId()).isEqualTo("ws-1");
        org.assertj.core.api.Assertions.assertThat(row.getValue().getApiKey()).isEqualTo("key-1");
        org.assertj.core.api.Assertions.assertThat(row.getValue().getProvider()).isEqualTo("openai");
        org.assertj.core.api.Assertions.assertThat(row.getValue().getCredentialFingerprint()).isEqualTo("fp-9");
        verify(costCalculationService).calculateAndPersist(any(), any(), org.mockito.ArgumentMatchers.eq("ws-1"),
                org.mockito.ArgumentMatchers.eq("key-1"), org.mockito.ArgumentMatchers.eq("openai"));
    }

    /** An attribution captured from a request that answers nothing is all nulls, which is why the tail must not capture late. */
    @Test
    void anAttributionCapturedFromARecycledRequestWouldBeUnknown_whichIsTheOldDefect() {
        HttpServletRequest nulls = mock(HttpServletRequest.class);
        ChatExecutionService.Attribution who = ChatExecutionService.Attribution.capture(nulls);
        org.assertj.core.api.Assertions.assertThat(who).isEqualTo(new ChatExecutionService.Attribution(null, null, null, null));
    }

    /** The provider and credential are stamped while the stream opens; the second phase picks them up and keeps the first. */
    @Test
    void withUpstreamFrom_addsProviderAndCredential_andKeepsThemOnARecycledRequest() {
        HttpServletRequest beforeOpen = mock(HttpServletRequest.class);
        when(beforeOpen.getAttribute(com.dvarahq.server.web.ApiKeyAuthFilter.API_KEY_ID_ATTR)).thenReturn("key-1");
        when(beforeOpen.getAttribute("workspaceId")).thenReturn("ws-1");
        ChatExecutionService.Attribution first = ChatExecutionService.Attribution.capture(beforeOpen);
        org.assertj.core.api.Assertions.assertThat(first.provider()).isNull();

        ChatExecutionService.Attribution opened = first.withUpstreamFrom(live());
        org.assertj.core.api.Assertions.assertThat(opened)
                .isEqualTo(new ChatExecutionService.Attribution("key-1", "ws-1", "openai", "fp-9"));
        org.assertj.core.api.Assertions.assertThat(opened.withUpstreamFrom(recycled())).isEqualTo(opened);
    }
}
