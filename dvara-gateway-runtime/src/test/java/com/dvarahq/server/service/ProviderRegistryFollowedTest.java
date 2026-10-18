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

import com.dvarahq.autoconfigure.region.PassthroughDataResidencyPolicy;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderRegistry;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.routing.CanaryMetricsCollector;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.server.TestProviders;
import com.dvarahq.server.actuator.GatewayReadinessIndicator;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A provider added to or removed from the registry is used, and shown, on the next request; no restart. */
class ProviderRegistryFollowedTest {

    private final List<LlmProvider> live = new CopyOnWriteArrayList<>();
    private final ProviderRegistry registry = () -> List.copyOf(live);
    private final RegionContext region = java.util.Optional::empty;

    private ProviderDispatcher dispatcher() {
        return new ProviderDispatcher(registry, new ModelPrefixRoutingStrategy(), name -> ProviderHealthStatus.HEALTHY,
                (request, failed, all) -> List.of(), new GatewayMetrics(new SimpleMeterRegistry(), region), ObservationRegistry.NOOP, null,
                mock(CanaryMetricsCollector.class), TestProviders.of(mock(CostEstimator.class)), null);
    }

    private static LlmProvider openAi() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("openai");
        when(provider.supports(any())).thenReturn(true);
        when(provider.chat(any())).thenReturn(ChatResponse.builder().id("r1").model("gpt-4o").build());
        return provider;
    }

    @Test
    void theDispatcherAndReadinessFollowTheRegistry() {
        ProviderDispatcher dispatcher = dispatcher();
        GatewayReadinessIndicator readiness = new GatewayReadinessIndicator(registry, region);
        ChatRequest request = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("hi"))).build();

        assertThat(dispatcher.allProviders()).isEmpty();
        assertThatThrownBy(() -> dispatcher.chat(request)).isInstanceOf(GatewayException.class);

        live.add(openAi());
        assertThat(dispatcher.allProviders()).extracting(LlmProvider::name).containsExactly("openai");
        assertThat(dispatcher.chat(request).getId()).isEqualTo("r1");
        assertThat(readiness.health().getDetails()).containsEntry("providerCount", 1);

        live.clear();
        assertThat(dispatcher.allProviders()).isEmpty();
        assertThat(readiness.health().getDetails()).containsEntry("providerCount", 0);
    }

    @Test
    void theStartupRegistryReadsTheProvidersOnce() {
        int[] reads = {0};
        ProviderRegistry lazy = ProviderRegistry.lazily(() -> {
            reads[0]++;
            return List.of(openAi());
        });
        assertThat(reads[0]).isZero();
        lazy.current();
        lazy.current();
        assertThat(reads[0]).isEqualTo(1);
    }
}
