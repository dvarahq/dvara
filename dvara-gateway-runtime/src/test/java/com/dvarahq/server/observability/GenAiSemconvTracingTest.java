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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The OTel GenAI semantic-convention ({@code gen_ai.*}) attributes on the provider spans: they are
 * emitted alongside the existing provider/model/token attributes, {@code gen_ai.system} maps to the
 * convention's value, and they are suppressed when
 * {@code management.tracing.genai-semconv.enabled} is false.
 */
class GenAiSemconvTracingTest {

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

    private ProviderDispatcher dispatcher(LlmProvider provider) {
        return new ProviderDispatcher(
                List.of(provider), DEFAULT_STRATEGY, ALWAYS_HEALTHY, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, observationRegistry, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);
    }

    @Test
    void chat_emitsGenAiAttributesAlongsideExisting() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("OpenAI");
        when(provider.supports(any())).thenReturn(true);
        when(provider.chat(any())).thenReturn(chatResponse("resp-1", "gpt-4o-2024", "stop"));

        dispatcher(provider).chat(chatRequest("gpt-4o", 256, 0.7));

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("gateway.provider.chat")
                .that()
                // GenAI semconv — request (low-card)
                .hasLowCardinalityKeyValue("gen_ai.system", "openai")
                .hasLowCardinalityKeyValue("gen_ai.request.model", "gpt-4o")
                .hasLowCardinalityKeyValue("gen_ai.operation.name", "chat")
                // GenAI semconv — request (high-card) + response (high-card)
                .hasHighCardinalityKeyValue("gen_ai.request.max_tokens", "256")
                .hasHighCardinalityKeyValue("gen_ai.request.temperature", "0.7")
                .hasHighCardinalityKeyValue("gen_ai.usage.input_tokens", "10")
                .hasHighCardinalityKeyValue("gen_ai.usage.output_tokens", "20")
                .hasHighCardinalityKeyValue("gen_ai.response.model", "gpt-4o-2024")
                .hasHighCardinalityKeyValue("gen_ai.response.id", "resp-1")
                .hasHighCardinalityKeyValue("gen_ai.response.finish_reasons", "stop")
                // existing attributes still present
                .hasLowCardinalityKeyValue("provider", "OpenAI")
                .hasLowCardinalityKeyValue("model", "gpt-4o")
                .hasHighCardinalityKeyValue("input_tokens", "10")
                .hasHighCardinalityKeyValue("output_tokens", "20");
    }

    @Test
    void stream_emitsGenAiRequestAttributes() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("anthropic");
        when(provider.supports(any())).thenReturn(true);
        when(provider.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("id").model("claude-3").delta("Hi").done(false).build()).iterator());

        Iterator<SseChunk> result = dispatcher(provider).streamChat(chatRequest("claude-3", 512, null));
        assertThat(result.hasNext()).isTrue();

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("gateway.provider.stream")
                .that()
                .hasLowCardinalityKeyValue("gen_ai.system", "anthropic")
                .hasLowCardinalityKeyValue("gen_ai.request.model", "claude-3")
                .hasLowCardinalityKeyValue("gen_ai.operation.name", "chat")
                .hasHighCardinalityKeyValue("gen_ai.request.max_tokens", "512")
                .hasLowCardinalityKeyValue("provider", "anthropic");
    }

    @Test
    void embed_emitsGenAiRequestAttributesWithEmbeddingsOperation() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("OpenAI");
        when(provider.supportsEmbedding("text-embedding-3-small")).thenReturn(true);
        when(provider.embed(any())).thenReturn(
                EmbeddingResponse.builder().model("text-embedding-3-small").data(List.of()).build());

        dispatcher(provider).embed(EmbeddingRequest.builder().model("text-embedding-3-small").input("hi").build());

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("gateway.provider.embed")
                .that()
                .hasLowCardinalityKeyValue("gen_ai.system", "openai")
                .hasLowCardinalityKeyValue("gen_ai.request.model", "text-embedding-3-small")
                .hasLowCardinalityKeyValue("gen_ai.operation.name", "embeddings")
                .hasLowCardinalityKeyValue("provider", "OpenAI");
    }

    @Test
    void genAiSystem_mapsSemconvEnumAndFallsBackToBrandSlug() {
        // bedrock -> aws.bedrock (enumerated semconv value)
        assertSystem("bedrock", "aws.bedrock");
        // DeepSeek -> deepseek (case-insensitive enum)
        assertSystem("DeepSeek", "deepseek");
        // Grok -> xai (enum)
        assertSystem("Grok", "xai");
        // Qwen -> qwen (no enum; lower-cased brand-slug fallback)
        assertSystem("Qwen", "qwen");
    }

    @Test
    void flagOff_suppressesGenAiButKeepsExistingAttributes() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("OpenAI");
        when(provider.supports(any())).thenReturn(true);
        when(provider.chat(any())).thenReturn(chatResponse("resp-1", "gpt-4o", "stop"));

        ProviderDispatcher dispatcher = dispatcher(provider);
        ReflectionTestUtils.setField(dispatcher, "genaiSemconvEnabled", false);

        dispatcher.chat(chatRequest("gpt-4o", 256, 0.7));

        TestObservationRegistryAssert.assertThat(observationRegistry)
                .hasObservationWithNameEqualTo("gateway.provider.chat")
                .that()
                // gen_ai.* suppressed
                .doesNotHaveLowCardinalityKeyValueWithKey("gen_ai.system")
                .doesNotHaveLowCardinalityKeyValueWithKey("gen_ai.operation.name")
                .doesNotHaveHighCardinalityKeyValueWithKey("gen_ai.usage.input_tokens")
                // legacy attributes still emitted
                .hasLowCardinalityKeyValue("provider", "OpenAI")
                .hasLowCardinalityKeyValue("model", "gpt-4o")
                .hasHighCardinalityKeyValue("input_tokens", "10");
    }

    private void assertSystem(String providerName, String expectedSystem) {
        TestObservationRegistry reg = TestObservationRegistry.create();
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn(providerName);
        when(provider.supports(any())).thenReturn(true);
        when(provider.chat(any())).thenReturn(chatResponse("r", "m", "stop"));
        ProviderDispatcher d = new ProviderDispatcher(
                List.of(provider), DEFAULT_STRATEGY, ALWAYS_HEALTHY, DEFAULT_FALLBACK,
                TEST_METRICS, TEST_REGION, TEST_RESIDENCY, reg, TEST_LATENCY_TRACKER,
                TEST_CANARY_COLLECTOR, TestProviders.of(TEST_COST_ESTIMATOR), TEST_SHADOW_DISPATCHER);
        d.chat(chatRequest("m", null, null));
        TestObservationRegistryAssert.assertThat(reg)
                .hasObservationWithNameEqualTo("gateway.provider.chat")
                .that()
                .hasLowCardinalityKeyValue("gen_ai.system", expectedSystem);
    }

    private static ChatRequest chatRequest(String model, Integer maxTokens, Double temperature) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .maxTokens(maxTokens)
                .temperature(temperature)
                .build();
    }

    private static ChatResponse chatResponse(String id, String model, String finishReason) {
        return ChatResponse.builder()
                .id(id)
                .model(model)
                .usage(ChatResponse.Usage.builder().promptTokens(10).completionTokens(20).totalTokens(30).build())
                .choices(List.of(ChatResponse.Choice.builder().index(0).finishReason(finishReason).build()))
                .build();
    }
}