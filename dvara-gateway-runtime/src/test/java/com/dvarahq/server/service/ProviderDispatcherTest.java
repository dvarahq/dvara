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
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.RoutingStrategy;
import com.dvarahq.core.region.DataResidencyPolicy;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.routing.CanaryMetricsCollector;
import com.dvarahq.core.routing.LatencyTracker;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.autoconfigure.region.PassthroughDataResidencyPolicy;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProviderDispatcherTest {

    private static final RoutingStrategy DEFAULT_STRATEGY = new ModelPrefixRoutingStrategy();

    private static final ProviderHealthRegistry ALWAYS_HEALTHY = name -> ProviderHealthStatus.HEALTHY;

    private static final FallbackResolver DEFAULT_FALLBACK = (request, failedProvider, allProviders) ->
            allProviders.stream()
                    .filter(p -> p.supports(request))
                    .filter(p -> !p.name().equals(failedProvider.name()))
                    .collect(Collectors.toList());

    // -------------------------------------------------------------------------
    // chat() — basic routing
    // -------------------------------------------------------------------------

    @Test
    void chat_selectsFirstMatchingProvider() {
        LlmProvider noMatch = mock(LlmProvider.class);
        LlmProvider match   = mock(LlmProvider.class);
        when(noMatch.supports(any())).thenReturn(false);
        when(match.supports(any())).thenReturn(true);

        ChatResponse expected = chatResponse("chatcmpl-1", "gpt-4o");
        when(match.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(noMatch, match));
        ChatRequest request = chatRequest("gpt-4o");

        ChatResponse result = dispatcher.chat(request);

        assertThat(result.getId()).isEqualTo("chatcmpl-1");
        verify(match).chat(request);
    }

    @Test
    void chat_noMatchingProvider_throwsGatewayExceptionWithNoProviderCode() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.supports(any())).thenReturn(false);

        ProviderDispatcher dispatcher = dispatcher(List.of(provider));

        assertThatThrownBy(() -> dispatcher.chat(chatRequest("unknown-model")))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("NO_PROVIDER"))
                .hasMessageContaining("unknown-model");
    }

    @Test
    void chat_emptyProviderList_throwsGatewayException() {
        ProviderDispatcher dispatcher = dispatcher(List.of());

        assertThatThrownBy(() -> dispatcher.chat(chatRequest("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("NO_PROVIDER"));
    }

    @Test
    void chat_usesCustomRoutingStrategy() {
        LlmProvider providerA = mock(LlmProvider.class);
        LlmProvider providerB = mock(LlmProvider.class);
        when(providerA.name()).thenReturn("a");
        when(providerB.name()).thenReturn("b");

        ChatResponse expected = chatResponse("routed-1", "gpt-4o");
        when(providerB.chat(any())).thenReturn(expected);

        RoutingStrategy alwaysB = (req, providers) -> providerB;

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(providerA, providerB), alwaysB, ALWAYS_HEALTHY, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);
        ChatRequest request = chatRequest("gpt-4o");

        ChatResponse result = dispatcher.chat(request);

        assertThat(result.getId()).isEqualTo("routed-1");
        verify(providerB).chat(request);
    }

    // -------------------------------------------------------------------------
    // chat() — primary-path health pre-filter
    // -------------------------------------------------------------------------

    @Test
    void chat_unhealthyProviderFilteredBeforeRoutingStrategy() {
        LlmProvider healthy = mockProvider("openai", true);
        LlmProvider unhealthy = mockProvider("anthropic", true);

        ChatResponse expected = chatResponse("healthy-primary", "gpt-4o");
        when(healthy.chat(any())).thenReturn(expected);

        ProviderHealthRegistry registry = name -> "anthropic".equals(name)
                ? ProviderHealthStatus.UNHEALTHY : ProviderHealthStatus.HEALTHY;

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(healthy, unhealthy), DEFAULT_STRATEGY, registry, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        ChatResponse result = dispatcher.chat(chatRequest("gpt-4o"));

        assertThat(result.getId()).isEqualTo("healthy-primary");
        verify(healthy).chat(any());
        verify(unhealthy, never()).chat(any());
    }

    @Test
    void chat_allUnhealthy_fallsThroughToStrategyWithFullList() {
        LlmProvider providerA = mockProvider("openai", true);
        LlmProvider providerB = mockProvider("anthropic", true);

        ChatResponse expected = chatResponse("fallthrough-1", "gpt-4o");
        when(providerA.chat(any())).thenReturn(expected);

        ProviderHealthRegistry allUnhealthy = name -> ProviderHealthStatus.UNHEALTHY;

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(providerA, providerB), DEFAULT_STRATEGY, allUnhealthy, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        ChatResponse result = dispatcher.chat(chatRequest("gpt-4o"));

        assertThat(result.getId()).isEqualTo("fallthrough-1");
    }

    // -------------------------------------------------------------------------
    // chat() — failover
    // -------------------------------------------------------------------------

    // --- the credential fingerprint moves with the provider on a fallback ---------------

    private static final String FINGERPRINT = com.dvarahq.providers.support.CredentialInterceptor.FINGERPRINT_ATTRIBUTE;

    /** A request context, as the servlet path has one; the primary's interceptor already stamped it. */
    private static org.springframework.mock.web.MockHttpServletRequest requestStampedBy(String primaryFingerprint) {
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setAttribute(FINGERPRINT, primaryFingerprint);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
        return request;
    }

    @org.junit.jupiter.api.AfterEach
    void clearRequestContext() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    /**
     * The primary went out under a credential the interceptor fingerprinted; the fallback resolves
     * its own credential without that interceptor and stamps nothing. The row must not say
     * "gemini, under openai's credential".
     */
    @Test
    void chat_aFallbackWithoutTheInterceptor_doesNotInheritThePrimarysFingerprint() {
        org.springframework.mock.web.MockHttpServletRequest request = requestStampedBy("fp-openai");
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("gemini", true);
        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "upstream down"));
        when(secondary.chat(any())).thenReturn(chatResponse("fallback-g", "gemini-pro"));

        dispatcher(List.of(primary, secondary)).chat(chatRequest("gpt-4o"));

        assertThat(request.getAttribute("gateway.provider")).isEqualTo("gemini");
        assertThat(request.getAttribute(FINGERPRINT)).as("the primary's credential is not attributed to the fallback").isNull();
    }

    /** A fallback that does go through the interceptor re-stamps its own, which then wins. */
    @Test
    void chat_aFallbackThroughTheInterceptor_stampsItsOwnFingerprint() {
        org.springframework.mock.web.MockHttpServletRequest request = requestStampedBy("fp-openai");
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("mistral", true);
        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "upstream down"));
        when(secondary.chat(any())).thenAnswer(inv -> {
            request.setAttribute(FINGERPRINT, "fp-mistral");   // what the interceptor does during the call
            return chatResponse("fallback-m", "mistral-large");
        });

        dispatcher(List.of(primary, secondary)).chat(chatRequest("gpt-4o"));

        assertThat(request.getAttribute(FINGERPRINT)).isEqualTo("fp-mistral");
    }

    /** The embeddings path has its own fallback loop; it moves the same two fields. */
    @Test
    void embed_aFallbackWithoutTheInterceptor_isStampedAndDoesNotInheritThePrimarysFingerprint() {
        org.springframework.mock.web.MockHttpServletRequest request = requestStampedBy("fp-openai");
        LlmProvider primary = mockProvider("openai", false);
        LlmProvider secondary = mockProvider("custom-embedder", false);
        when(primary.supportsEmbedding("text-embedding-3-small")).thenReturn(true);
        when(secondary.supportsEmbedding("text-embedding-3-small")).thenReturn(true);
        when(primary.embed(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "upstream down"));
        when(secondary.embed(any())).thenReturn(mock(EmbeddingResponse.class));

        dispatcher(List.of(primary, secondary)).embed(embeddingRequest("text-embedding-3-small"));

        assertThat(request.getAttribute("gateway.provider")).isEqualTo("custom-embedder");
        assertThat(request.getAttribute(FINGERPRINT)).isNull();
    }

    /** The primary embed call stamps its provider and keeps its own fingerprint. */
    @Test
    void embed_stampsThePrimaryProvider() {
        org.springframework.mock.web.MockHttpServletRequest request = requestStampedBy("fp-openai");
        LlmProvider primary = mockProvider("openai", false);
        when(primary.supportsEmbedding("text-embedding-3-small")).thenReturn(true);
        when(primary.embed(any())).thenReturn(mock(EmbeddingResponse.class));

        dispatcher(List.of(primary)).embed(embeddingRequest("text-embedding-3-small"));

        assertThat(request.getAttribute("gateway.provider")).isEqualTo("openai");
        assertThat(request.getAttribute(FINGERPRINT)).as("the primary's own fingerprint stays").isEqualTo("fp-openai");
    }

    @Test
    void chat_fallsBackOnProviderError() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "upstream down"));
        ChatResponse expected = chatResponse("fallback-1", "claude-3");
        when(secondary.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));
        ChatResponse result = dispatcher.chat(chatRequest("gpt-4o"));

        assertThat(result.getId()).isEqualTo("fallback-1");
        verify(primary).chat(any());
        verify(secondary).chat(any());
    }

    @Test
    void chat_fallsBackOnCircuitOpen() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_CIRCUIT_OPEN", "circuit open"));
        ChatResponse expected = chatResponse("fallback-2", "claude-3");
        when(secondary.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));
        ChatResponse result = dispatcher.chat(chatRequest("gpt-4o"));

        assertThat(result.getId()).isEqualTo("fallback-2");
    }

    @Test
    void chat_fallsBackOnProviderRateLimited() {
        // a shed on the primary (its upstream credential is near quota) fails over to the next
        // provider on the route, exactly like a provider error or open circuit.
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_RATE_LIMITED",
                "Upstream openai rate limit near exhaustion", 20L));
        ChatResponse expected = chatResponse("fallback-rl", "claude-3");
        when(secondary.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));
        ChatResponse result = dispatcher.chat(chatRequest("gpt-4o"));

        assertThat(result.getId()).isEqualTo("fallback-rl");
        verify(primary).chat(any());
        verify(secondary).chat(any());
    }

    @Test
    void chat_throwsOriginalExceptionWhenAllFallbacksExhausted() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "primary down"));
        when(secondary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "secondary down"));

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));

        assertThatThrownBy(() -> dispatcher.chat(chatRequest("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .hasMessage("primary down");
    }

    @Test
    void chat_doesNotFallbackOnNoProviderError() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.chat(any())).thenThrow(new GatewayException("NO_PROVIDER", "no provider"));

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));

        assertThatThrownBy(() -> dispatcher.chat(chatRequest("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("NO_PROVIDER"));
        verify(secondary, never()).chat(any());
    }

    @Test
    void chat_skipsUnhealthyFallbackProviders() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider unhealthy = mockProvider("anthropic", true);
        LlmProvider healthy = mockProvider("gemini", true);

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "down"));
        ChatResponse expected = chatResponse("healthy-1", "gemini");
        when(healthy.chat(any())).thenReturn(expected);

        ProviderHealthRegistry registry = name -> "anthropic".equals(name)
                ? ProviderHealthStatus.UNHEALTHY
                : ProviderHealthStatus.HEALTHY;

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(primary, unhealthy, healthy), DEFAULT_STRATEGY, registry, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        ChatResponse result = dispatcher.chat(chatRequest("gpt-4o"));

        assertThat(result.getId()).isEqualTo("healthy-1");
        verify(unhealthy, never()).chat(any());
    }

    @Test
    void chat_emptyFallbackList_throwsOriginalException() {
        LlmProvider primary = mockProvider("openai", true);

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "primary error"));

        // Fallback resolver returns empty list
        FallbackResolver emptyFallback = (req, failed, all) -> List.of();
        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(primary), DEFAULT_STRATEGY, ALWAYS_HEALTHY, emptyFallback,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        assertThatThrownBy(() -> dispatcher.chat(chatRequest("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .hasMessage("primary error");
    }

    @Test
    void chat_fallbackChainAcrossThreeProviders() {
        LlmProvider p1 = mockProvider("openai", true);
        LlmProvider p2 = mockProvider("anthropic", true);
        LlmProvider p3 = mockProvider("gemini", true);

        when(p1.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "p1 down"));
        when(p2.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "p2 down"));
        ChatResponse expected = chatResponse("p3-success", "gemini-pro");
        when(p3.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(p1, p2, p3));

        ChatResponse result = dispatcher.chat(chatRequest("gpt-4o"));

        assertThat(result.getId()).isEqualTo("p3-success");
        verify(p1).chat(any());
        verify(p2).chat(any());
        verify(p3).chat(any());
    }

    @Test
    void chat_allThreeProvidersDown_throwsOriginalException() {
        LlmProvider p1 = mockProvider("openai", true);
        LlmProvider p2 = mockProvider("anthropic", true);
        LlmProvider p3 = mockProvider("gemini", true);

        when(p1.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "p1 down"));
        when(p2.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "p2 down"));
        when(p3.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "p3 down"));

        ProviderDispatcher dispatcher = dispatcher(List.of(p1, p2, p3));

        assertThatThrownBy(() -> dispatcher.chat(chatRequest("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .hasMessage("p1 down"); // Original exception from primary
    }

    @Test
    void chat_doesNotFallbackOnUnknownErrorCode() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.chat(any())).thenThrow(new GatewayException("UNKNOWN_CODE", "mystery error"));

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));

        assertThatThrownBy(() -> dispatcher.chat(chatRequest("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("UNKNOWN_CODE"));
        verify(secondary, never()).chat(any());
    }

    @Test
    void chat_allFallbacksUnhealthy_throwsOriginalException() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider fallback1 = mockProvider("anthropic", true);
        LlmProvider fallback2 = mockProvider("gemini", true);

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "primary down"));

        // All fallbacks are unhealthy
        ProviderHealthRegistry registry = name -> "openai".equals(name)
                ? ProviderHealthStatus.HEALTHY
                : ProviderHealthStatus.UNHEALTHY;

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(primary, fallback1, fallback2), DEFAULT_STRATEGY, registry, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        assertThatThrownBy(() -> dispatcher.chat(chatRequest("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .hasMessage("primary down");
        verify(fallback1, never()).chat(any());
        verify(fallback2, never()).chat(any());
    }

    // -------------------------------------------------------------------------
    // streamChat() — basic
    // -------------------------------------------------------------------------

    @Test
    void streamChat_delegatesToMatchingProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.supports(any())).thenReturn(true);

        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("id").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("id").model("gpt-4o").delta(null).finishReason("stop").done(true).build()
        );
        when(provider.streamChat(any())).thenReturn(chunks.iterator());

        ProviderDispatcher dispatcher = dispatcher(List.of(provider));
        ChatRequest request = chatRequest("gpt-4o");

        Iterator<SseChunk> result = dispatcher.streamChat(request);

        assertThat(result.hasNext()).isTrue();
        assertThat(result.next().getDelta()).isEqualTo("Hi");
        verify(provider).streamChat(request);
    }

    @Test
    void streamChat_noProvider_throwsGatewayException() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.supports(any())).thenReturn(false);

        ProviderDispatcher dispatcher = dispatcher(List.of(provider));

        assertThatThrownBy(() -> dispatcher.streamChat(chatRequest("unknown")))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("NO_PROVIDER"));
    }

    // -------------------------------------------------------------------------
    // streamChat() — failover
    // -------------------------------------------------------------------------

    @Test
    void streamChat_fallsBackOnProviderError() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.streamChat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "stream fail"));
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("id").model("gpt-4o").delta("Hi").done(false).build()
        );
        when(secondary.streamChat(any())).thenReturn(chunks.iterator());

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));
        Iterator<SseChunk> result = dispatcher.streamChat(chatRequest("gpt-4o"));

        assertThat(result.hasNext()).isTrue();
        assertThat(result.next().getDelta()).isEqualTo("Hi");
    }

    @Test
    void streamChat_fallsBackOnCircuitOpen() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.streamChat(any())).thenThrow(new GatewayException("PROVIDER_CIRCUIT_OPEN", "circuit open"));
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("id").model("claude-3").delta("Hello").done(false).build()
        );
        when(secondary.streamChat(any())).thenReturn(chunks.iterator());

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));
        Iterator<SseChunk> result = dispatcher.streamChat(chatRequest("gpt-4o"));

        assertThat(result.hasNext()).isTrue();
        assertThat(result.next().getDelta()).isEqualTo("Hello");
    }

    @Test
    void streamChat_doesNotFallbackOnNoProvider() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.streamChat(any())).thenThrow(new GatewayException("NO_PROVIDER", "no provider"));

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));

        assertThatThrownBy(() -> dispatcher.streamChat(chatRequest("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("NO_PROVIDER"));
        verify(secondary, never()).streamChat(any());
    }

    @Test
    void streamChat_throwsOriginalWhenAllFallbacksExhausted() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        when(primary.streamChat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "stream primary fail"));
        when(secondary.streamChat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "stream secondary fail"));

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));

        assertThatThrownBy(() -> dispatcher.streamChat(chatRequest("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .hasMessage("stream primary fail");
    }

    @Test
    void streamChat_skipsUnhealthyFallbackProviders() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider unhealthy = mockProvider("anthropic", true);
        LlmProvider healthy = mockProvider("gemini", true);

        when(primary.streamChat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "down"));
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("id").model("gemini-pro").delta("Bonjour").done(false).build()
        );
        when(healthy.streamChat(any())).thenReturn(chunks.iterator());

        ProviderHealthRegistry registry = name -> "anthropic".equals(name)
                ? ProviderHealthStatus.UNHEALTHY
                : ProviderHealthStatus.HEALTHY;

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(primary, unhealthy, healthy), DEFAULT_STRATEGY, registry, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        Iterator<SseChunk> result = dispatcher.streamChat(chatRequest("gpt-4o"));

        assertThat(result.hasNext()).isTrue();
        assertThat(result.next().getDelta()).isEqualTo("Bonjour");
        verify(unhealthy, never()).streamChat(any());
    }

    // -------------------------------------------------------------------------
    // embed() — basic
    // -------------------------------------------------------------------------

    @Test
    void embed_selectsEmbeddingCapableProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);

        EmbeddingResponse expected = EmbeddingResponse.builder()
                .model("text-embedding-ada-002")
                .data(List.of())
                .build();
        when(provider.embed(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(provider));
        EmbeddingRequest request = embeddingRequest("text-embedding-ada-002");

        EmbeddingResponse result = dispatcher.embed(request);

        assertThat(result.getModel()).isEqualTo("text-embedding-ada-002");
        verify(provider).embed(request);
    }

    @Test
    void embed_noEmbeddingCapableProvider_throwsGatewayException() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.supportsEmbedding(any())).thenReturn(false);

        ProviderDispatcher dispatcher = dispatcher(List.of(provider));

        assertThatThrownBy(() -> dispatcher.embed(embeddingRequest("text-embedding-ada-002")))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("NO_PROVIDER"))
                .hasMessageContaining("text-embedding-ada-002");
    }

    // -------------------------------------------------------------------------
    // embed() — failover
    // -------------------------------------------------------------------------

    @Test
    void embed_fallsBackOnProviderError() {
        LlmProvider primary = mock(LlmProvider.class);
        LlmProvider secondary = mock(LlmProvider.class);
        when(primary.name()).thenReturn("openai");
        when(secondary.name()).thenReturn("bedrock");
        when(primary.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);
        when(secondary.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);

        when(primary.embed(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "embed failed"));
        EmbeddingResponse expected = EmbeddingResponse.builder()
                .model("text-embedding-ada-002").data(List.of()).build();
        when(secondary.embed(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));
        EmbeddingResponse result = dispatcher.embed(embeddingRequest("text-embedding-ada-002"));

        assertThat(result.getModel()).isEqualTo("text-embedding-ada-002");
        verify(primary).embed(any());
        verify(secondary).embed(any());
    }

    @Test
    void embed_fallsBackOnCircuitOpen() {
        LlmProvider primary = mock(LlmProvider.class);
        LlmProvider secondary = mock(LlmProvider.class);
        when(primary.name()).thenReturn("openai");
        when(secondary.name()).thenReturn("bedrock");
        when(primary.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);
        when(secondary.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);

        when(primary.embed(any())).thenThrow(new GatewayException("PROVIDER_CIRCUIT_OPEN", "circuit open"));
        EmbeddingResponse expected = EmbeddingResponse.builder()
                .model("text-embedding-ada-002").data(List.of()).build();
        when(secondary.embed(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));
        EmbeddingResponse result = dispatcher.embed(embeddingRequest("text-embedding-ada-002"));

        assertThat(result.getModel()).isEqualTo("text-embedding-ada-002");
    }

    @Test
    void embed_doesNotFallbackOnNoProvider() {
        LlmProvider primary = mock(LlmProvider.class);
        LlmProvider secondary = mock(LlmProvider.class);
        when(primary.name()).thenReturn("openai");
        when(secondary.name()).thenReturn("bedrock");
        when(primary.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);
        when(secondary.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);

        when(primary.embed(any())).thenThrow(new GatewayException("NO_PROVIDER", "no provider"));

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));

        // NO_PROVIDER is not fallback-eligible, so embed throws the original exception
        assertThatThrownBy(() -> dispatcher.embed(embeddingRequest("text-embedding-ada-002")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("NO_PROVIDER"));
        verify(secondary, never()).embed(any());
    }

    @Test
    void embed_skipsUnhealthyFallbackProvider() {
        LlmProvider primary = mock(LlmProvider.class);
        LlmProvider unhealthy = mock(LlmProvider.class);
        LlmProvider healthy = mock(LlmProvider.class);
        when(primary.name()).thenReturn("openai");
        when(unhealthy.name()).thenReturn("anthropic");
        when(healthy.name()).thenReturn("gemini");
        when(primary.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);
        when(unhealthy.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);
        when(healthy.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);

        when(primary.embed(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "failed"));
        EmbeddingResponse expected = EmbeddingResponse.builder()
                .model("text-embedding-ada-002").data(List.of()).build();
        when(healthy.embed(any())).thenReturn(expected);

        ProviderHealthRegistry registry = name -> "anthropic".equals(name)
                ? ProviderHealthStatus.UNHEALTHY
                : ProviderHealthStatus.HEALTHY;

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(primary, unhealthy, healthy), DEFAULT_STRATEGY, registry, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        EmbeddingResponse result = dispatcher.embed(embeddingRequest("text-embedding-ada-002"));

        assertThat(result.getModel()).isEqualTo("text-embedding-ada-002");
        verify(unhealthy, never()).embed(any());
    }

    @Test
    void embed_allFallbacksFail_throwsOriginalException() {
        LlmProvider primary = mock(LlmProvider.class);
        LlmProvider secondary = mock(LlmProvider.class);
        when(primary.name()).thenReturn("openai");
        when(secondary.name()).thenReturn("bedrock");
        when(primary.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);
        when(secondary.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);

        when(primary.embed(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "primary embed down"));
        when(secondary.embed(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "secondary embed down"));

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, secondary));

        assertThatThrownBy(() -> dispatcher.embed(embeddingRequest("text-embedding-ada-002")))
                .isInstanceOf(GatewayException.class)
                .hasMessage("primary embed down");
    }

    // -------------------------------------------------------------------------
    // allProviders()
    // -------------------------------------------------------------------------

    @Test
    void allProviders_returnsUnmodifiableCopy() {
        LlmProvider p = mock(LlmProvider.class);
        ProviderDispatcher dispatcher = dispatcher(List.of(p));

        List<LlmProvider> all = dispatcher.allProviders();

        assertThat(all).hasSize(1).contains(p);
        assertThatThrownBy(() -> all.add(mock(LlmProvider.class)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void allProviders_returnsAllRegisteredProviders() {
        LlmProvider p1 = mockProvider("openai", true);
        LlmProvider p2 = mockProvider("anthropic", true);
        LlmProvider p3 = mockProvider("gemini", true);

        ProviderDispatcher dispatcher = dispatcher(List.of(p1, p2, p3));

        assertThat(dispatcher.allProviders()).hasSize(3);
    }

    // -------------------------------------------------------------------------
    // Capability-aware routing — json_schema
    // -------------------------------------------------------------------------

    @Test
    void chat_jsonSchema_filtersOutIncapableProviders() {
        LlmProvider capable = mockProviderWithCaps("openai", true, fullCaps());
        LlmProvider incapable = mockProviderWithCaps("ollama", true, noCaps());

        ChatResponse expected = chatResponse("cap-1", "gpt-4o");
        when(capable.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(incapable, capable));
        ChatRequest request = chatRequestWithFormat("gpt-4o", new ResponseFormat.JsonSchema("test", java.util.Map.of(), false));

        ChatResponse result = dispatcher.chat(request);

        assertThat(result.getId()).isEqualTo("cap-1");
        verify(capable).chat(any());
        verify(incapable, never()).chat(any());
    }

    @Test
    void chat_jsonSchema_noCapableProvider_throwsNoCapableProvider() {
        LlmProvider incapable = mockProviderWithCaps("ollama", true, noCaps());

        ProviderDispatcher dispatcher = dispatcher(List.of(incapable));

        assertThatThrownBy(() -> dispatcher.chat(
                chatRequestWithFormat("gpt-4o", new ResponseFormat.JsonSchema("test", java.util.Map.of(), false))))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("NO_CAPABLE_PROVIDER"))
                .hasMessageContaining("json_schema");
    }

    // -------------------------------------------------------------------------
    // Capability-aware routing — json_object
    // -------------------------------------------------------------------------

    @Test
    void chat_jsonObject_filtersOutIncapableProviders() {
        LlmProvider capable = mockProviderWithCaps("openai", true, fullCaps());
        LlmProvider incapable = mockProviderWithCaps("ollama", true, noCaps());

        ChatResponse expected = chatResponse("json-obj-1", "gpt-4o");
        when(capable.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(incapable, capable));
        ChatRequest request = chatRequestWithFormat("gpt-4o", new ResponseFormat.JsonObject());

        ChatResponse result = dispatcher.chat(request);

        assertThat(result.getId()).isEqualTo("json-obj-1");
        verify(capable).chat(any());
        verify(incapable, never()).chat(any());
    }

    @Test
    void chat_jsonObject_noCapableProvider_throwsNoCapableProvider() {
        LlmProvider incapable = mockProviderWithCaps("ollama", true, noCaps());

        ProviderDispatcher dispatcher = dispatcher(List.of(incapable));

        assertThatThrownBy(() -> dispatcher.chat(
                chatRequestWithFormat("gpt-4o", new ResponseFormat.JsonObject())))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("NO_CAPABLE_PROVIDER"))
                .hasMessageContaining("json_object");
    }

    // -------------------------------------------------------------------------
    // Capability-aware routing — no format / text
    // -------------------------------------------------------------------------

    @Test
    void chat_noResponseFormat_noFiltering() {
        LlmProvider incapable = mockProviderWithCaps("ollama", true, noCaps());

        ChatResponse expected = chatResponse("no-fmt-1", "ollama/llama3");
        when(incapable.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(incapable));
        ChatRequest request = chatRequest("gpt-4o");

        ChatResponse result = dispatcher.chat(request);
        assertThat(result.getId()).isEqualTo("no-fmt-1");
    }

    @Test
    void chat_textFormat_noFiltering() {
        LlmProvider incapable = mockProviderWithCaps("ollama", true, noCaps());

        ChatResponse expected = chatResponse("text-fmt-1", "ollama/llama3");
        when(incapable.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(incapable));
        ChatRequest request = chatRequestWithFormat("gpt-4o", new ResponseFormat.Text());

        ChatResponse result = dispatcher.chat(request);
        assertThat(result.getId()).isEqualTo("text-fmt-1");
    }

    // -------------------------------------------------------------------------
    // Capability-aware streaming
    // -------------------------------------------------------------------------

    @Test
    void streamChat_jsonSchema_filtersOutIncapableProviders() {
        LlmProvider capable = mockProviderWithCaps("openai", true, fullCaps());
        LlmProvider incapable = mockProviderWithCaps("ollama", true, noCaps());

        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("id").model("gpt-4o").delta("Hi").done(false).build()
        );
        when(capable.streamChat(any())).thenReturn(chunks.iterator());

        ProviderDispatcher dispatcher = dispatcher(List.of(incapable, capable));
        ChatRequest request = chatRequestWithFormat("gpt-4o", new ResponseFormat.JsonSchema("test", java.util.Map.of(), false));

        Iterator<SseChunk> result = dispatcher.streamChat(request);

        assertThat(result.hasNext()).isTrue();
        assertThat(result.next().getDelta()).isEqualTo("Hi");
        verify(incapable, never()).streamChat(any());
    }

    // -------------------------------------------------------------------------
    // Capability-aware failover
    // -------------------------------------------------------------------------

    @Test
    void chat_failover_filtersOutIncapableFallbacks() {
        LlmProvider primary = mockProviderWithCaps("openai", true, fullCaps());
        LlmProvider incapableFallback = mockProviderWithCaps("ollama", true, noCaps());
        LlmProvider capableFallback = mockProviderWithCaps("anthropic", true, fullCaps());

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "primary down"));
        ChatResponse expected = chatResponse("cap-fallback-1", "claude-3");
        when(capableFallback.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, incapableFallback, capableFallback));
        ChatRequest request = chatRequestWithFormat("gpt-4o", new ResponseFormat.JsonSchema("test", java.util.Map.of(), false));

        ChatResponse result = dispatcher.chat(request);

        assertThat(result.getId()).isEqualTo("cap-fallback-1");
        verify(incapableFallback, never()).chat(any());
    }

    @Test
    void chat_failover_noCapableFallback_throwsFailoverCapabilityMismatch() {
        LlmProvider primary = mockProviderWithCaps("openai", true, fullCaps());
        LlmProvider incapableFallback = mockProviderWithCaps("ollama", true, noCaps());

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "primary down"));

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, incapableFallback));
        ChatRequest request = chatRequestWithFormat("gpt-4o", new ResponseFormat.JsonSchema("test", java.util.Map.of(), false));

        assertThatThrownBy(() -> dispatcher.chat(request))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> assertThat(((GatewayException) ex).getCode()).isEqualTo("FAILOVER_CAPABILITY_MISMATCH"))
                .hasMessageContaining("json_schema")
                .hasMessageContaining("openai");
    }

    @Test
    void chat_failover_noFormat_fallsBackNormally() {
        LlmProvider primary = mockProviderWithCaps("openai", true, fullCaps());
        LlmProvider incapable = mockProviderWithCaps("ollama", true, noCaps());

        when(primary.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "primary down"));
        ChatResponse expected = chatResponse("no-fmt-fallback", "ollama/llama3");
        when(incapable.chat(any())).thenReturn(expected);

        ProviderDispatcher dispatcher = dispatcher(List.of(primary, incapable));
        ChatRequest request = chatRequest("gpt-4o");

        ChatResponse result = dispatcher.chat(request);
        assertThat(result.getId()).isEqualTo("no-fmt-fallback");
    }

    // -------------------------------------------------------------------------
    // Latency tracking
    // -------------------------------------------------------------------------

    @Test
    void chat_recordsLatencyOnSuccess() {
        LlmProvider provider = mockProvider("openai", true);
        ChatResponse expected = chatResponse("lat-1", "gpt-4o");
        when(provider.chat(any())).thenReturn(expected);

        LatencyTracker mockTracker = mock(LatencyTracker.class);
        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(provider), DEFAULT_STRATEGY, ALWAYS_HEALTHY, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, mockTracker,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        dispatcher.chat(chatRequest("gpt-4o"));

        verify(mockTracker).record(
                org.mockito.ArgumentMatchers.eq("openai"),
                org.mockito.ArgumentMatchers.eq("gpt-4o"),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void chat_doesNotRecordLatencyOnError() {
        LlmProvider provider = mockProvider("openai", true);
        when(provider.chat(any())).thenThrow(new GatewayException("NO_PROVIDER", "fail"));

        LatencyTracker mockTracker = mock(LatencyTracker.class);
        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(provider), DEFAULT_STRATEGY, ALWAYS_HEALTHY, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, mockTracker,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        try {
            dispatcher.chat(chatRequest("gpt-4o"));
        } catch (GatewayException ignored) {
        }

        verify(mockTracker, never()).record(any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static final RegionContext TEST_REGION = () -> java.util.Optional.empty();
    private static final DataResidencyPolicy TEST_RESIDENCY = new PassthroughDataResidencyPolicy();
    private static final GatewayMetrics TEST_METRICS = new GatewayMetrics(new SimpleMeterRegistry(), TEST_REGION);
    /** A tracker that records nowhere, so these cases exercise the recording call rather than the null skip. */
    private static final LatencyTracker TEST_LATENCY_TRACKER = new LatencyTracker() {
        @Override
        public void record(String provider, String model, long latencyMs) {
        }

        @Override
        public java.util.OptionalDouble getEwmaLatency(String provider, String model) {
            return java.util.OptionalDouble.empty();
        }
    };
    private static final CanaryMetricsCollector TEST_CANARY_COLLECTOR = mock(CanaryMetricsCollector.class);
    private static final CostEstimator TEST_COST_ESTIMATOR = mock(CostEstimator.class);
    private static final com.dvarahq.core.routing.ShadowDispatcher TEST_SHADOW_DISPATCHER = (req, resp, latency, route) -> { };

    private static ProviderDispatcher dispatcher(List<LlmProvider> providers) {
        return new ProviderDispatcher(providers, DEFAULT_STRATEGY, ALWAYS_HEALTHY, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, ObservationRegistry.NOOP, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);
    }

    private static LlmProvider mockProvider(String name, boolean supports) {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn(name);
        when(provider.supports(any())).thenReturn(supports);
        return provider;
    }

    private static ChatRequest chatRequest(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .build();
    }

    private static ChatResponse chatResponse(String id, String model) {
        return ChatResponse.builder()
                .id(id)
                .model(model)
                .choices(List.of())
                .build();
    }

    private static EmbeddingRequest embeddingRequest(String model) {
        return EmbeddingRequest.builder()
                .model(model)
                .input("hello")
                .build();
    }

    private static ChatRequest chatRequestWithFormat(String model, ResponseFormat format) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .responseFormat(format)
                .build();
    }

    private static LlmProvider mockProviderWithCaps(String name, boolean supports, ProviderCapabilities caps) {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn(name);
        when(provider.supports(any())).thenReturn(supports);
        when(provider.capabilities()).thenReturn(caps);
        return provider;
    }

    private static ProviderCapabilities fullCaps() {
        return new ProviderCapabilities(true, true, true, true, true, 128_000);
    }

    private static ProviderCapabilities noCaps() {
        return new ProviderCapabilities(true, false, false, false, false, 32_000);
    }

    // ================== function-calling capability routing ==================

    @Test
    void chat_withTools_noToolCapableProvider_throwsNoCapableProvider() {
        // A tools request must never silent-drop onto a non-tool provider.
        ProviderCapabilities noTools = new ProviderCapabilities(true, false, false, false, false, 32_000);
        LlmProvider ollama = mockProviderWithCaps("ollama", true, noTools);
        ProviderDispatcher dispatcher = dispatcher(List.of(ollama));

        ChatRequest request = ChatRequest.builder()
                .model("ollama/llama3")
                .messages(List.of(MultimodalMessage.user("hi")))
                .tools(List.of(ToolDefinition.builder().name("get_weather").build()))
                .build();

        assertThatThrownBy(() -> dispatcher.chat(request))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                        ((GatewayException) e).getCode()).isEqualTo("NO_CAPABLE_PROVIDER"));
    }

    @Test
    void chat_toolCallInHistoryWithNoToolsBeside_stillNeedsAToolCapableProvider() {
        // A client is not obliged to resend `tools` on a later turn of an agent loop, but the tool
        // call it replays is still something only a tool-capable provider can represent; the others
        // would drop it and answer plain chat with a 200.
        ProviderCapabilities noTools = new ProviderCapabilities(true, false, false, false, false, 32_000);
        ProviderDispatcher dispatcher = dispatcher(List.of(mockProviderWithCaps("ollama", true, noTools)));

        ChatRequest request = ChatRequest.builder()
                .model("ollama/llama3")
                .messages(List.of(
                        MultimodalMessage.user("weather in Paris?"),
                        MultimodalMessage.builder()
                                .role("assistant")
                                .toolCalls(List.of(com.dvarahq.core.model.ToolCall.builder()
                                        .id("call_1").name("get_weather").arguments("{}").build()))
                                .build(),
                        MultimodalMessage.toolResult("call_1", "17C")))
                .build();

        assertThatThrownBy(() -> dispatcher.chat(request))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                        ((GatewayException) e).getCode()).isEqualTo("NO_CAPABLE_PROVIDER"));
    }

    @Test
    void chat_withTools_toolCapableProvider_dispatches() {
        ProviderCapabilities withTools = new ProviderCapabilities(true, true, true, true, true, 128_000);
        LlmProvider openai = mockProviderWithCaps("openai", true, withTools);
        ChatResponse expected = ChatResponse.builder().id("x").build();
        when(openai.chat(any())).thenReturn(expected);
        ProviderDispatcher dispatcher = dispatcher(List.of(openai));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("hi")))
                .tools(List.of(ToolDefinition.builder().name("get_weather").build()))
                .build();

        org.assertj.core.api.Assertions.assertThat(dispatcher.chat(request)).isSameAs(expected);
    }
    // ================== streamed function calling needs a decoder that relays it ==================

    /** Declares tool calls, but not on a stream: fine for chat(), never selected for a tool stream. */
    private static ProviderCapabilities toolsButNotStreamed() {
        return new ProviderCapabilities(true, true, true, true, true, false, false, 128_000);
    }

    private static ProviderCapabilities toolsStreamed() {
        return new ProviderCapabilities(true, true, true, true, true, false, true, 128_000);
    }

    private static ChatRequest toolStream(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("weather?")))
                .tools(List.of(ToolDefinition.builder().name("get_weather").build()))
                .stream(true)
                .build();
    }

    @Test
    void streamChat_withTools_selectsOnlyAProviderThatRelaysStreamedToolCalls() {
        LlmProvider cannotStream = mockProviderWithCaps("groq", true, toolsButNotStreamed());
        LlmProvider can = mockProviderWithCaps("openai", true, toolsStreamed());
        when(can.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c").model("gpt-4o").finishReason("tool_calls").done(true).build()).iterator());
        ProviderDispatcher dispatcher = dispatcher(List.of(cannotStream, can));

        Iterator<SseChunk> result = dispatcher.streamChat(toolStream("gpt-4o"));

        assertThat(result.hasNext()).isTrue();
        verify(cannotStream, never()).streamChat(any());
        verify(can).streamChat(any());
    }

    @Test
    void streamChat_withTools_noProviderRelaysStreamedToolCalls_throwsNoCapableProvider() {
        LlmProvider cannotStream = mockProviderWithCaps("groq", true, toolsButNotStreamed());
        ProviderDispatcher dispatcher = dispatcher(List.of(cannotStream));

        assertThatThrownBy(() -> dispatcher.streamChat(toolStream("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("NO_CAPABLE_PROVIDER"))
                .hasMessageContaining("on a stream");
    }

    @Test
    void streamChat_failover_withTools_skipsAFallbackThatCannotStreamThem() {
        LlmProvider primary = mockProviderWithCaps("openai", true, toolsStreamed());
        LlmProvider cannotStream = mockProviderWithCaps("groq", true, toolsButNotStreamed());
        LlmProvider can = mockProviderWithCaps("mistral", true, toolsStreamed());
        when(primary.streamChat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "primary down"));
        when(can.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("f").model("mistral-large").finishReason("tool_calls").done(true).build()).iterator());
        ProviderDispatcher dispatcher = dispatcher(List.of(primary, cannotStream, can));

        Iterator<SseChunk> result = dispatcher.streamChat(toolStream("gpt-4o"));

        assertThat(result.next().getId()).isEqualTo("f");
        verify(cannotStream, never()).streamChat(any());
    }

    @Test
    void streamChat_failover_withTools_noFallbackStreamsThem_throwsFailoverCapabilityMismatch() {
        LlmProvider primary = mockProviderWithCaps("openai", true, toolsStreamed());
        LlmProvider cannotStream = mockProviderWithCaps("groq", true, toolsButNotStreamed());
        when(primary.streamChat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "primary down"));
        ProviderDispatcher dispatcher = dispatcher(List.of(primary, cannotStream));

        assertThatThrownBy(() -> dispatcher.streamChat(toolStream("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("FAILOVER_CAPABILITY_MISMATCH"))
                .hasMessageContaining("on a stream")
                .hasMessageContaining("openai");
    }

    /** Off a stream the flag is irrelevant: a provider that cannot stream a call still serves it whole. */
    @Test
    void chat_withTools_doesNotRequireStreamedToolCalls() {
        LlmProvider cannotStream = mockProviderWithCaps("groq", true, toolsButNotStreamed());
        ChatResponse expected = ChatResponse.builder().id("whole").build();
        when(cannotStream.chat(any())).thenReturn(expected);
        ProviderDispatcher dispatcher = dispatcher(List.of(cannotStream));

        ChatRequest request = toolStream("gpt-4o").toBuilder().stream(false).build();
        assertThat(dispatcher.chat(request)).isSameAs(expected);
    }
}