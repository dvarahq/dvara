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
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.provider.RoutingStrategy;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.resilience.PrimaryTargetGuard;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.routing.CanaryMetricsCollector;
import com.dvarahq.core.routing.LatencyTracker;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RequestContext;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.server.TestProviders;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A route that sends another model than the caller named is checked on the model it sends: the first target is
 * asked of every {@link PrimaryTargetGuard} with the model actually sent.
 */
class PrimaryTargetGuardTest {

    private static final RegionContext REGION = () -> java.util.Optional.empty();
    private static final ProviderHealthRegistry HEALTHY = name -> ProviderHealthStatus.HEALTHY;
    private static final FallbackResolver SAME_MODEL = (request, failed, all) -> all.stream()
            .filter(p -> p.supports(request)).filter(p -> !p.name().equals(failed.name())).collect(Collectors.toList());

    /** The model the route pins: the caller names gpt-4o and the route sends this one. */
    private static final String PINNED = "gpt-4o-2024-08-06";

    private static LlmProvider provider(String name, String modelPrefix) {
        LlmProvider p = mock(LlmProvider.class);
        when(p.name()).thenReturn(name);
        when(p.supports(any())).thenAnswer(inv -> {
            ChatRequest r = inv.getArgument(0);
            return r.getModel() != null && r.getModel().startsWith(modelPrefix);
        });
        when(p.capabilities()).thenReturn(new ProviderCapabilities(true, true, true, true, true, 128_000));
        return p;
    }

    private static ChatRequest request(String model) {
        return ChatRequest.builder().model(model).messages(List.of(MultimodalMessage.user("Reply with ready."))).build();
    }

    private static ChatResponse response(String model) {
        return ChatResponse.builder().id("r").object("chat.completion").created(0L).model(model).choices(List.of()).build();
    }

    /** Routes gpt-4o to the first provider that serves it, and sends the pinned version. */
    private static final RoutingStrategy PIN = new RoutingStrategy() {
        @Override
        public LlmProvider route(ChatRequest request, List<LlmProvider> providers) {
            return new ModelPrefixRoutingStrategy().route(request, providers);
        }

        @Override
        public LlmProvider route(ChatRequest request, List<LlmProvider> providers, RequestContext ctx) {
            ctx.setResolvedModel(PINNED);
            return route(request, providers);
        }
    };

    private static RoutingEngine pinningRoute(RouteConfig.FallbackTarget... chain) {
        RouteConfig route = RouteConfig.builder().id("pinned").modelPattern("gpt-4o")
                .strategy(RouteConfig.Strategy.MODEL_PREFIX).providers(List.of())
                .fallbacks(List.of(chain)).build();
        return new RoutingEngine(new ModelPrefixRoutingStrategy(), List.of(new RoutingEngine.ResolvedRoute(route, PIN)));
    }

    private static ProviderDispatcher dispatcher(List<LlmProvider> providers, RoutingEngine routes) {
        return new ProviderDispatcher(providers, routes, HEALTHY, SAME_MODEL,
                new GatewayMetrics(new SimpleMeterRegistry(), REGION), REGION, new PassthroughDataResidencyPolicy(),
                ObservationRegistry.NOOP, mock(LatencyTracker.class), mock(CanaryMetricsCollector.class),
                TestProviders.of(mock(CostEstimator.class)), (req, resp, latency, route) -> { });
    }

    /** Refuses the pinned model and records every model it is asked about and every refusal reported. */
    private static final class TurnedOff implements PrimaryTargetGuard {
        final List<String> asked = new ArrayList<>();
        final List<String> reported = new ArrayList<>();
        final String model;

        TurnedOff(String model) {
            this.model = model;
        }

        @Override
        public GatewayException refuse(ChatRequest request, String provider) {
            asked.add(provider + ":" + request.getModel());
            return model.equals(request.getModel())
                    ? new GatewayException("POLICY_DENIED", "Model '" + model + "' is turned off")
                    : null;
        }

        @Override
        public void refused(ChatRequest request, String provider, GatewayException error) {
            reported.add(provider + ":" + request.getModel() + ":" + error.getCode());
        }
    }

    @Test
    void withNoGuardTheRoutesModelIsSentAsBefore() {
        LlmProvider openai = provider("openai", "gpt");
        when(openai.chat(any())).thenReturn(response(PINNED));

        dispatcher(List.of(openai), pinningRoute()).chat(request("gpt-4o"));

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(openai).chat(sent.capture());
        assertThat(sent.getValue().getModel()).isEqualTo(PINNED);
    }

    @Test
    void theGuardIsAskedAboutTheModelTheRouteSends_notTheOneTheCallerNamed() {
        LlmProvider openai = provider("openai", "gpt");
        when(openai.chat(any())).thenReturn(response(PINNED));
        TurnedOff guard = new TurnedOff("some-other-model");
        ProviderDispatcher d = dispatcher(List.of(openai), pinningRoute());
        d.setPrimaryGuards(List.of(guard));

        d.chat(request("gpt-4o"));

        assertThat(guard.asked).containsExactly("openai:" + PINNED);
        assertThat(guard.reported).isEmpty();
        verify(openai).chat(any());
    }

    @Test
    void aTurnedOffModelTheRouteSendsIsRefused_theProviderIsNeverCalled_andTheRefusalReportedOnce() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider azure = provider("azure-openai", "gpt");   // serves the same model; must not be tried
        TurnedOff guard = new TurnedOff(PINNED);
        ProviderDispatcher d = dispatcher(List.of(openai, azure), pinningRoute());
        d.setPrimaryGuards(List.of(guard));

        assertThatThrownBy(() -> d.chat(request("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("'" + PINNED + "' is turned off")
                .extracting(e -> ((GatewayException) e).getCode()).isEqualTo("POLICY_DENIED");

        verify(openai, never()).chat(any());
        verify(azure, never()).chat(any());
        assertThat(guard.reported).containsExactly("openai:" + PINNED + ":POLICY_DENIED");
    }

    @Test
    void aRefusedFirstTargetFallsBackAlongTheRoutesChain_andNothingIsReported() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider anthropic = provider("anthropic", "claude");
        when(anthropic.chat(any())).thenReturn(response("claude-backup"));
        TurnedOff guard = new TurnedOff(PINNED);
        ProviderDispatcher d = dispatcher(List.of(openai, anthropic),
                pinningRoute(new RouteConfig.FallbackTarget("anthropic", "claude-backup")));
        d.setPrimaryGuards(List.of(guard));

        ChatResponse served = d.chat(request("gpt-4o"));

        assertThat(served.getModel()).isEqualTo("claude-backup");
        verify(openai, never()).chat(any());
        assertThat(guard.reported).isEmpty();
    }

    @Test
    void whenTheChainCannotServeIt_theCallerGetsTheRefusal_notTheChainsError() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider anthropic = provider("anthropic", "claude");
        TurnedOff guard = new TurnedOff(PINNED);
        ProviderDispatcher d = dispatcher(List.of(openai, anthropic),
                pinningRoute(new RouteConfig.FallbackTarget("anthropic", "claude-backup")));
        d.setPrimaryGuards(List.of(guard));
        d.setFallbackGuards(List.of((r, provider) -> "claude-backup is turned off too"));

        assertThatThrownBy(() -> d.chat(request("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("'" + PINNED + "' is turned off");

        verify(openai, never()).chat(any());
        verify(anthropic, never()).chat(any());
        assertThat(guard.reported).hasSize(1);
    }

    @Test
    void aStreamIsNotOpenedOnARefusedTarget() {
        LlmProvider openai = provider("openai", "gpt");
        TurnedOff guard = new TurnedOff(PINNED);
        ProviderDispatcher d = dispatcher(List.of(openai), pinningRoute());
        d.setPrimaryGuards(List.of(guard));

        assertThatThrownBy(() -> d.streamChat(request("gpt-4o").toBuilder().stream(true).build()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("'" + PINNED + "' is turned off");

        verify(openai, never()).streamChat(any());
        assertThat(guard.reported).hasSize(1);
    }

    @Test
    void aTokenCountIsNotSentToARefusedTarget() {
        LlmProvider openai = provider("openai", "gpt");
        when(openai.countInputTokens(any())).thenReturn(OptionalInt.of(7));
        TurnedOff guard = new TurnedOff(PINNED);
        ProviderDispatcher d = dispatcher(List.of(openai), pinningRoute());
        d.setPrimaryGuards(List.of(guard));

        assertThatThrownBy(() -> d.countInputTokens(request("gpt-4o")))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("'" + PINNED + "' is turned off");

        verify(openai, never()).countInputTokens(any());
    }

    @Test
    void theFirstGuardThatRefusesDecides_andOnlyItIsTold() {
        LlmProvider openai = provider("openai", "gpt");
        TurnedOff first = new TurnedOff(PINNED);
        TurnedOff second = new TurnedOff(PINNED);
        ProviderDispatcher d = dispatcher(List.of(openai), pinningRoute());
        d.setPrimaryGuards(List.of(first, second));

        assertThatThrownBy(() -> d.chat(request("gpt-4o"))).isInstanceOf(GatewayException.class);

        assertThat(first.reported).hasSize(1);
        assertThat(second.asked).isEmpty();
        assertThat(second.reported).isEmpty();
    }
}
