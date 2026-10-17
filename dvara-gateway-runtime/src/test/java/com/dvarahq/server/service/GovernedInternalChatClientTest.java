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

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.call.InternalCall;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.TokenUsageRecord;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.server.TestProviders;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.web.AccessLogFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The gateway's own calls run through the governed path, on a request of their own, billed to their workspace. */
class GovernedInternalChatClientTest {

    private static final InternalCall CALL = new InternalCall("eval", "ws-1", "internal-eval", "parent-trace-1");

    private final ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
    private final RequestPipeline pipeline = mock(RequestPipeline.class);
    private final ResponseCache cache = mock(ResponseCache.class);
    private final TokenUsageRepository usage = mock(TokenUsageRepository.class);
    private final CostCalculationService cost = mock(CostCalculationService.class);
    private final AuditWriter audit = mock(AuditWriter.class);
    private GovernedInternalChatClient client;

    @BeforeEach
    void setUp() {
        when(pipeline.preDispatch(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(pipeline.postDispatch(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        PiiEnforcer pii = mock(PiiEnforcer.class);
        when(pii.detokenizeResponse(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(cost.calculateAndPersist(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(java.util.Optional.empty());
        ChatExecutionService execution = new ChatExecutionService(
                dispatcher, pipeline, TestProviders.of(cache), usage,
                TestProviders.of(mock(WorkspaceUsageListener.class)), TestProviders.of(cost),
                TestProviders.of(mock(CostEstimator.class)), pii, mock(RateLimiter.class),
                mock(StreamingResponseEnforcer.class), audit,
                TestProviders.of(mock(PriorityAdmissionController.class)), mock(GatewayMetrics.class),
                mock(TokenEstimator.class), TestProviders.of(CallOutcomeListener.NOOP));
        client = new GovernedInternalChatClient(execution, audit);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static ChatRequest request() {
        return ChatRequest.builder().model("judge-model").messages(List.of(MultimodalMessage.user("grade this"))).build();
    }

    /** What the dispatcher does on a real call: stamps the provider and the served model on the bound request. */
    private void dispatchServedBy(String provider, String servedModel, AtomicReference<InternalCall> seen) {
        when(dispatcher.chat(any())).thenAnswer(inv -> {
            seen.set(InternalCall.current());
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            attrs.getRequest().setAttribute(AccessLogFilter.ATTR_PROVIDER, provider);
            attrs.getRequest().setAttribute(ProviderDispatcher.SERVED_MODEL_ATTR, servedModel);
            return ChatResponse.builder().id("r1").model(servedModel).choices(List.of())
                    .usage(ChatResponse.Usage.builder().promptTokens(10).completionTokens(5).totalTokens(15).build())
                    .build();
        });
    }

    @Test
    void runsBothPipelinesAndBillsTheCallsWorkspaceAndKey() {
        AtomicReference<InternalCall> seen = new AtomicReference<>();
        dispatchServedBy("openai", "judge-model-2", seen);

        ChatResponse response = client.chat(request(), CALL);

        assertThat(response.getUsage().getTotalTokens()).isEqualTo(15);
        // Code on the governed path can tell the call apart from a caller's request, and only while it runs.
        assertThat(seen.get()).isEqualTo(CALL);
        assertThat(InternalCall.current()).isNull();

        ArgumentCaptor<FilterContext> ctx = ArgumentCaptor.forClass(FilterContext.class);
        verify(pipeline).preDispatch(any(), ctx.capture());
        verify(pipeline).postDispatch(any(), any(), any());
        assertThat(ctx.getValue().getWorkspaceId()).isEqualTo("ws-1");
        assertThat(ctx.getValue().getApiKey()).isEqualTo("internal-eval");

        ArgumentCaptor<TokenUsageRecord> row = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(usage).save(row.capture());
        assertThat(row.getValue().getWorkspaceId()).isEqualTo("ws-1");
        assertThat(row.getValue().getApiKey()).isEqualTo("internal-eval");
        assertThat(row.getValue().getProvider()).isEqualTo("openai");
        assertThat(row.getValue().getModel()).isEqualTo("judge-model-2");
        verify(cost).calculateAndPersist(any(), any(), eq("ws-1"), eq("internal-eval"), eq("openai"), eq(false));
    }

    @Test
    void writesAnAuditRecordNamingTheKindAndTheParentTrace() {
        dispatchServedBy("openai", "judge-model", new AtomicReference<>());

        client.chat(request(), CALL);

        AuditEvent event = internalCallEvent();
        assertThat(event.workspaceId()).isEqualTo("ws-1");
        assertThat(event.payload())
                .containsEntry("kind", "eval")
                .containsEntry("parent_trace_id", "parent-trace-1")
                .containsEntry("provider", "openai")
                .containsEntry("model", "judge-model")
                .containsEntry("status", "OK")
                .containsEntry("api_key", "internal-eval")
                .containsEntry("tokens_total", "15")
                .containsKey("trace_id");
    }

    @Test
    void neverReadsOrFillsTheResponseCache() {
        dispatchServedBy("openai", "judge-model", new AtomicReference<>());

        client.chat(request(), CALL);

        // The answer belongs to the request that caused the call; it must not be served to a caller.
        verify(cache, never()).get(any());
        verify(cache, never()).put(any(), any());
    }

    @Test
    void leavesTheCallersRequestAsItWas() {
        MockHttpServletRequest callers = new MockHttpServletRequest();
        callers.setAttribute(AccessLogFilter.ATTR_PROVIDER, "anthropic");
        ServletRequestAttributes callersAttributes = new ServletRequestAttributes(callers);
        RequestContextHolder.setRequestAttributes(callersAttributes);
        dispatchServedBy("openai", "judge-model", new AtomicReference<>());

        client.chat(request(), CALL);

        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(callersAttributes);
        assertThat(callers.getAttribute(AccessLogFilter.ATTR_PROVIDER)).isEqualTo("anthropic");
        assertThat(callers.getAttribute(ProviderDispatcher.SERVED_MODEL_ATTR)).isNull();
    }

    @Test
    void aRefusedCallIsAuditedAndThrownAndLeavesNothingBound() {
        when(pipeline.preDispatch(any(), any())).thenThrow(new GatewayException("POLICY_DENIED", "denied"));

        assertThatThrownBy(() -> client.chat(request(), CALL))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getCode()).isEqualTo("POLICY_DENIED");

        verifyNoInteractions(dispatcher);
        assertThat(internalCallEvent().payload())
                .containsEntry("status", "ERROR")
                .containsEntry("error_code", "POLICY_DENIED");
        assertThat(InternalCall.current()).isNull();
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Test
    void anInternalCallNeverStartsAnother() {
        try (InternalCall.Scope ignored = new InternalCall("routing", "ws-1", "internal-routing", null).open()) {
            assertThatThrownBy(() -> client.chat(request(), CALL))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("never starts another");
        }
        verifyNoInteractions(dispatcher);
    }

    @Test
    void aContextWindowWarningNeedsNoResponseToSetHeadersOn() {
        when(pipeline.preDispatch(any(), any())).thenAnswer(inv -> {
            FilterContext ctx = inv.getArgument(1);
            ctx.setAttribute("context.warning", true);
            return inv.getArgument(0);
        });
        dispatchServedBy("openai", "judge-model", new AtomicReference<>());

        assertThat(client.chat(request(), CALL).getUsage().getTotalTokens()).isEqualTo(15);
    }

    private AuditEvent internalCallEvent() {
        ArgumentCaptor<AuditEvent> events = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, org.mockito.Mockito.atLeastOnce()).write(events.capture());
        return events.getAllValues().stream()
                .filter(e -> GovernedInternalChatClient.AUDIT_EVENT.equals(e.eventType()))
                .findFirst().orElseThrow();
    }
}
