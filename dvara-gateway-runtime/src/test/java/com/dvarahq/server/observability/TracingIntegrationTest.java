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
package com.dvarahq.server.observability;

import com.dvarahq.server.TestProviders;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
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
import com.dvarahq.server.service.ProviderDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistryAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link ProviderDispatcher} creates Micrometer {@link io.micrometer.observation.Observation}
 * instances with correct names and low-cardinality key values for chat, stream, and embed operations.
 */
class TracingIntegrationTest {

    private static final RoutingStrategy DEFAULT_STRATEGY = new ModelPrefixRoutingStrategy();
    private static final ProviderHealthRegistry ALWAYS_HEALTHY = name -> ProviderHealthStatus.HEALTHY;
    private static final FallbackResolver DEFAULT_FALLBACK = (request, failedProvider, allProviders) ->
            allProviders.stream()
                    .filter(p -> p.supports(request))
                    .filter(p -> !p.name().equals(failedProvider.name()))
                    .collect(Collectors.toList());
    private static final RegionContext TEST_REGION = () -> java.util.Optional.empty();
    private static final DataResidencyPolicy TEST_RESIDENCY = new PassthroughDataResidencyPolicy();
    private static final GatewayMetrics TEST_METRICS = new GatewayMetrics(new SimpleMeterRegistry(), TEST_REGION);
    private static final LatencyTracker TEST_LATENCY_TRACKER = new LatencyTracker() {
        @Override public void record(String provider, String model, long latencyMs) {}
        @Override public java.util.OptionalDouble getEwmaLatency(String provider, String model) { return java.util.OptionalDouble.empty(); }
    };
    private static final CanaryMetricsCollector TEST_CANARY_COLLECTOR = mock(CanaryMetricsCollector.class);
    private static final CostEstimator TEST_COST_ESTIMATOR = mock(CostEstimator.class);
    private static final com.dvarahq.core.routing.ShadowDispatcher TEST_SHADOW_DISPATCHER = (req, resp, latency, route) -> { };

    private TestObservationRegistry observationRegistry;

    @BeforeEach
    void setUp() {
        observationRegistry = TestObservationRegistry.create();
    }

    @Test
    void chat_createsObservationWithCorrectNameAndAttributes() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("openai");
        when(provider.supports(any())).thenReturn(true);
        when(provider.chat(any())).thenReturn(chatResponse("test-1", "gpt-4o"));

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(provider), DEFAULT_STRATEGY, ALWAYS_HEALTHY, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, observationRegistry, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        dispatcher.chat(chatRequest("gpt-4o"));

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .doesNotHaveAnyRemainingCurrentObservation()
                .hasObservationWithNameEqualTo("gateway.provider.chat")
                .that()
                .hasLowCardinalityKeyValue("provider", "openai")
                .hasLowCardinalityKeyValue("model", "gpt-4o")
                .hasBeenStarted()
                .hasBeenStopped();
    }

    @Test
    void streamChat_createsObservationWithCorrectNameAndAttributes() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("anthropic");
        when(provider.supports(any())).thenReturn(true);
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("id").model("claude-3").delta("Hi").done(false).build()
        );
        when(provider.streamChat(any())).thenReturn(chunks.iterator());

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(provider), DEFAULT_STRATEGY, ALWAYS_HEALTHY, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, observationRegistry, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        Iterator<SseChunk> result = dispatcher.streamChat(chatRequest("claude-3"));
        assertThat(result.hasNext()).isTrue();

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .doesNotHaveAnyRemainingCurrentObservation()
                .hasObservationWithNameEqualTo("gateway.provider.stream")
                .that()
                .hasLowCardinalityKeyValue("provider", "anthropic")
                .hasLowCardinalityKeyValue("model", "claude-3")
                .hasBeenStarted()
                .hasBeenStopped();
    }

    @Test
    void embed_createsObservationWithCorrectNameAndAttributes() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("openai");
        when(provider.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);
        when(provider.embed(any())).thenReturn(
                EmbeddingResponse.builder().model("text-embedding-ada-002").data(List.of()).build());

        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(provider), DEFAULT_STRATEGY, ALWAYS_HEALTHY, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, observationRegistry, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        dispatcher.embed(EmbeddingRequest.builder().model("text-embedding-ada-002").input("hello").build());

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .doesNotHaveAnyRemainingCurrentObservation()
                .hasObservationWithNameEqualTo("gateway.provider.embed")
                .that()
                .hasLowCardinalityKeyValue("provider", "openai")
                .hasLowCardinalityKeyValue("model", "text-embedding-ada-002")
                .hasBeenStarted()
                .hasBeenStopped();
    }

    @Test
    void chat_observationRecordsErrorOnProviderFailure() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("openai");
        when(provider.supports(any())).thenReturn(true);
        when(provider.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "upstream error"));

        FallbackResolver noFallback = (req, failed, all) -> List.of();
        ProviderDispatcher dispatcher = new ProviderDispatcher(
                List.of(provider), DEFAULT_STRATEGY, ALWAYS_HEALTHY, noFallback,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, observationRegistry, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);

        try {
            dispatcher.chat(chatRequest("gpt-4o"));
        } catch (GatewayException ignored) {
            // expected
        }

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .doesNotHaveAnyRemainingCurrentObservation()
                .hasObservationWithNameEqualTo("gateway.provider.chat")
                .that()
                .hasLowCardinalityKeyValue("provider", "openai")
                .hasBeenStarted()
                .hasBeenStopped();
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
}