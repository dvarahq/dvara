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
import com.dvarahq.core.metering.CallOutcomeListener;
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
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * On a cache miss the cache must receive the tokenised response while the caller receives the
 * detokenised one. If detokenisation ran before {@code responseCache.put}, real PII would be cached
 * and served to later callers in the workspace.
 */
class ChatExecutionServiceCacheSafetyTest {

    private static final String TOKEN = "{{PII_EMAIL_deadbeef}}";
    private static final String ORIGINAL = "user@example.com";

    /** Fake enforcer whose detokenizeResponse restores the one seeded token on a NEW object. */
    private static class FakeDetokenizingEnforcer implements PiiEnforcer {
        @Override public ChatRequest enforceRequest(ChatRequest r, String t) { return r; }
        @Override public ChatResponse enforceResponse(ChatResponse r, String t) { return r; }
        @Override public ChatRequest stripForCache(ChatRequest r, String t) { return r; }
        // This fake is about the cache, not PII, so it rewrites nothing.
        @Override public com.dvarahq.core.pii.PiiAction resolvedAction(String w) {
            return com.dvarahq.core.pii.PiiAction.LOG;
        }
        @Override public String enforceResponseBlob(String content, String w) { return content; }
        @Override public java.util.List<com.dvarahq.core.pii.PiiEdit> enforceResponseEdits(
                String content, String w) { return java.util.List.of(); }
        @Override public ChatResponse detokenizeResponse(ChatResponse response, ChatRequest request, String workspaceId) {
            List<ChatResponse.Choice> choices = new ArrayList<>();
            for (var c : response.getChoices()) {
                String restored = text(c).replace(TOKEN, ORIGINAL);
                choices.add(ChatResponse.Choice.builder()
                        .index(c.getIndex())
                        .message(MultimodalMessage.builder().role("assistant")
                                .content(List.of(new ContentBlock.TextBlock(restored))).build())
                        .finishReason(c.getFinishReason())
                        .build());
            }
            // toBuilder keeps the headers, as the real PiiScanService does. A fresh ChatResponse would
            // drop gatewayHeaders inside the fake and the stale-header test would pass for the wrong reason.
            return response.toBuilder().choices(choices).build();
        }
    }

    /**
     * Behaves like the real {@code PiiScanService} on the failing case: it cannot resolve the token,
     * so it leaves the text alone and adds the signal to gatewayHeaders.
     */
    private static class FakeUnresolvableEnforcer implements PiiEnforcer {
        @Override public ChatRequest enforceRequest(ChatRequest r, String t) { return r; }
        @Override public ChatResponse enforceResponse(ChatResponse r, String t) { return r; }
        @Override public ChatRequest stripForCache(ChatRequest r, String t) { return r; }
        // This fake is about the cache, not PII, so it rewrites nothing.
        @Override public com.dvarahq.core.pii.PiiAction resolvedAction(String w) {
            return com.dvarahq.core.pii.PiiAction.LOG;
        }
        @Override public String enforceResponseBlob(String content, String w) { return content; }
        @Override public java.util.List<com.dvarahq.core.pii.PiiEdit> enforceResponseEdits(
                String content, String w) { return java.util.List.of(); }
        @Override public ChatResponse detokenizeResponse(ChatResponse response, ChatRequest request, String workspaceId) {
            java.util.Map<String, String> headers = response.getGatewayHeaders() == null
                    ? new java.util.LinkedHashMap<>()
                    : new java.util.LinkedHashMap<>(response.getGatewayHeaders());
            headers.put("X-Gateway-Pii-Unresolved", "1");
            return response.toBuilder().gatewayHeaders(headers).build();
        }
    }

    private static ChatExecutionService serviceWith(PiiEnforcer enforcer, ResponseCache cache,
                                                    ProviderDispatcher dispatcher,
                                                    RequestPipeline pipeline) {
        return new ChatExecutionService(
                dispatcher, pipeline, TestProviders.of(cache),
                mock(TokenUsageRepository.class), TestProviders.of(mock(WorkspaceUsageListener.class)),
                TestProviders.of(mock(CostCalculationService.class)), TestProviders.of(mock(CostEstimator.class)), enforcer, mock(RateLimiter.class),
                mock(StreamingResponseEnforcer.class), mock(AuditWriter.class),
                TestProviders.of(mock(PriorityAdmissionController.class)), mock(GatewayMetrics.class),
                mock(TokenEstimator.class), TestProviders.of(CallOutcomeListener.NOOP));
    }

    /**
     * A pipeline whose {@code postDispatch} hands the response back unchanged.
     *
     * <p>A cache hit runs the output pipeline, and a bare {@code mock(RequestPipeline.class)} would
     * return null from {@code postDispatch}, which reads as the pipeline destroying the response.</p>
     */
    private static RequestPipeline passThroughPipeline() {
        RequestPipeline p = mock(RequestPipeline.class);
        when(p.postDispatch(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        return p;
    }

    private static String text(ChatResponse.Choice c) {
        return ((ContentBlock.TextBlock) c.getMessage().getContent().getFirst()).text();
    }

    private static ChatResponse tokenizedResponse() {
        return ChatResponse.builder().id("r1").model("gpt-4o")
                .choices(List.of(ChatResponse.Choice.builder().index(0)
                        .message(MultimodalMessage.builder().role("assistant")
                                .content(List.of(new ContentBlock.TextBlock("Your email is " + TOKEN)))
                                .build())
                        .build()))
                .build();
    }

    // --- a cache hit must not swallow this request's governance signals ---

    @Test
    void cacheHit_dropsTheCachedResponsesStaleGatewayHeaders() {
        // Stored headers describe the upstream call that filled the cache, not this one. Replaying
        // an X-Gateway-Strict-Downgraded from a request that did not happen would be false.
        ResponseCache cache = mock(ResponseCache.class);
        ChatResponse stored = tokenizedResponse().toBuilder()
                .gatewayHeaders(java.util.Map.of("X-Gateway-Strict-Downgraded", "true"))
                .build();
        when(cache.get(any())).thenReturn(Optional.of(stored));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute("workspaceId")).thenReturn("t1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("key-1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR)).thenReturn("key-1");

        var result = serviceWith(new FakeDetokenizingEnforcer(), cache,
                mock(ProviderDispatcher.class), passThroughPipeline())
                .executeSync(ChatRequest.builder().model("gpt-4o")
                                .messages(List.of(MultimodalMessage.user("hi"))).build(),
                        FilterContext.builder().workspaceId("t1").build(), httpRequest);

        assertThat(result.cacheHit()).isTrue();
        assertThat(result.response().getGatewayHeaders() == null
                || !result.response().getGatewayHeaders().containsKey("X-Gateway-Strict-Downgraded"))
                .isTrue();
    }

    @Test
    void cacheHit_keepsAHeaderThisRequestProduced() {
        // The controller relies on this: after the clear, anything on gatewayHeaders came from this
        // request, so it can be emitted unconditionally.
        ResponseCache cache = mock(ResponseCache.class);
        when(cache.get(any())).thenReturn(Optional.of(tokenizedResponse().toBuilder()
                .gatewayHeaders(java.util.Map.of("X-Gateway-Strict-Downgraded", "true"))
                .build()));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute("workspaceId")).thenReturn("t1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("key-1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR)).thenReturn("key-1");

        var result = serviceWith(new FakeUnresolvableEnforcer(), cache,
                mock(ProviderDispatcher.class), passThroughPipeline())
                .executeSync(ChatRequest.builder().model("gpt-4o")
                                .messages(List.of(MultimodalMessage.user("hi"))).build(),
                        FilterContext.builder().workspaceId("t1").build(), httpRequest);

        assertThat(result.response().getGatewayHeaders())
                .containsEntry("X-Gateway-Pii-Unresolved", "1")
                .doesNotContainKey("X-Gateway-Strict-Downgraded");
    }

    @Test
    void cacheHit_doesNotMutateTheStoredResponse() {
        // A cache may hand back a shared instance; clearing headers on it would corrupt the entry
        // for every later reader. An in-heap cache would show that corruption, a JSON-deserialising
        // one would hide it.
        ResponseCache cache = mock(ResponseCache.class);
        ChatResponse stored = tokenizedResponse().toBuilder()
                .gatewayHeaders(java.util.Map.of("X-Gateway-Strict-Downgraded", "true"))
                .build();
        when(cache.get(any())).thenReturn(Optional.of(stored));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute("workspaceId")).thenReturn("t1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("key-1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR)).thenReturn("key-1");

        serviceWith(new FakeDetokenizingEnforcer(), cache,
                mock(ProviderDispatcher.class), passThroughPipeline())
                .executeSync(ChatRequest.builder().model("gpt-4o")
                                .messages(List.of(MultimodalMessage.user("hi"))).build(),
                        FilterContext.builder().workspaceId("t1").build(), httpRequest);

        assertThat(stored.getGatewayHeaders()).containsEntry("X-Gateway-Strict-Downgraded", "true");
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeSync_cachesTokenisedFormButReturnsDetokenised() {
        ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
        RequestPipeline requestPipeline = mock(RequestPipeline.class);
        ResponseCache responseCache = mock(ResponseCache.class);
        CostCalculationService costService = mock(CostCalculationService.class);

        when(dispatcher.chat(any())).thenReturn(tokenizedResponse());
        when(requestPipeline.postDispatch(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(responseCache.get(any())).thenReturn(Optional.empty());
        when(costService.calculateAndPersist(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute("workspaceId")).thenReturn("t1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("key-1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR)).thenReturn("key-1");

        ChatExecutionService svc = new ChatExecutionService(
                dispatcher, requestPipeline, TestProviders.of(responseCache),
                mock(TokenUsageRepository.class), TestProviders.of(mock(WorkspaceUsageListener.class)),
                TestProviders.of(costService), TestProviders.of(mock(CostEstimator.class)), new FakeDetokenizingEnforcer(), mock(RateLimiter.class),
                mock(StreamingResponseEnforcer.class), mock(AuditWriter.class),
                TestProviders.of(mock(PriorityAdmissionController.class)), mock(GatewayMetrics.class),
                mock(TokenEstimator.class), TestProviders.of(CallOutcomeListener.NOOP));

        ChatRequest request = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("hi"))).build();

        ChatExecutionService.SyncResult result =
                svc.executeSync(request, FilterContext.builder().workspaceId("t1").build(), httpRequest);

        // What the cache stored must be the tokenised form.
        ArgumentCaptor<ChatResponse> cached = ArgumentCaptor.forClass(ChatResponse.class);
        verify(responseCache).put(any(), cached.capture());
        assertThat(text(cached.getValue().getChoices().getFirst()))
                .contains(TOKEN).doesNotContain(ORIGINAL);

        // What the caller receives must be the detokenised form.
        assertThat(text(result.response().getChoices().getFirst()))
                .contains(ORIGINAL).doesNotContain(TOKEN);
        assertThat(result.cacheHit()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void executeSync_cacheHit_detokenisesServedResponseWithoutReWriting() {
        // On a hit the cache holds the tokenised form; it is detokenised for this caller and the
        // cache is not re-written.
        ProviderDispatcher dispatcher = mock(ProviderDispatcher.class);
        // A hit runs the output pipeline, so the pipeline must hand the response back rather than
        // Mockito's null. This test is about detokenisation, not filters.
        RequestPipeline requestPipeline = passThroughPipeline();
        ResponseCache responseCache = mock(ResponseCache.class);
        CostCalculationService costService = mock(CostCalculationService.class);

        when(responseCache.get(any())).thenReturn(Optional.of(tokenizedResponse()));

        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        when(httpRequest.getAttribute("workspaceId")).thenReturn("t1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("key-1");
        when(httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR)).thenReturn("key-1");

        ChatExecutionService svc = new ChatExecutionService(
                dispatcher, requestPipeline, TestProviders.of(responseCache),
                mock(TokenUsageRepository.class), TestProviders.of(mock(WorkspaceUsageListener.class)),
                TestProviders.of(costService), TestProviders.of(mock(CostEstimator.class)), new FakeDetokenizingEnforcer(), mock(RateLimiter.class),
                mock(StreamingResponseEnforcer.class), mock(AuditWriter.class),
                TestProviders.of(mock(PriorityAdmissionController.class)), mock(GatewayMetrics.class),
                mock(TokenEstimator.class), TestProviders.of(CallOutcomeListener.NOOP));

        ChatRequest request = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("hi"))).build();

        ChatExecutionService.SyncResult result =
                svc.executeSync(request, FilterContext.builder().workspaceId("t1").build(), httpRequest);

        assertThat(result.cacheHit()).isTrue();
        assertThat(text(result.response().getChoices().getFirst()))
                .contains(ORIGINAL).doesNotContain(TOKEN);
        // The upstream was never called and the cache was never re-written.
        verify(dispatcher, never()).chat(any());
        verify(responseCache, never()).put(any(), any());
    }
}