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

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.autoconfigure.region.PassthroughDataResidencyPolicy;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.routing.CanaryMetricsCollector;
import com.dvarahq.core.routing.LatencyTracker;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.server.TestProviders;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** #7: a route fails over along its own ordered chain of provider and model, and nowhere else. */
class RouteFallbackChainTest {

    private static final RegionContext REGION = () -> java.util.Optional.empty();
    private static final ProviderHealthRegistry HEALTHY = name -> ProviderHealthStatus.HEALTHY;
    /** The registry-wide, same-model resolver a request with no route still uses. */
    private static final FallbackResolver SAME_MODEL = (request, failed, all) -> all.stream()
            .filter(p -> p.supports(request)).filter(p -> !p.name().equals(failed.name())).collect(Collectors.toList());

    private static LlmProvider provider(String name, String modelPrefix, ProviderCapabilities caps) {
        LlmProvider p = mock(LlmProvider.class);
        when(p.name()).thenReturn(name);
        when(p.supports(any())).thenAnswer(inv -> {
            ChatRequest r = inv.getArgument(0);
            return r.getModel() != null && r.getModel().startsWith(modelPrefix);
        });
        when(p.capabilities()).thenReturn(caps);
        return p;
    }

    private static LlmProvider provider(String name, String modelPrefix) {
        return provider(name, modelPrefix, new ProviderCapabilities(true, true, true, true, true, 128_000));
    }

    private static ChatRequest request(String model) {
        return ChatRequest.builder().model(model).messages(List.of(MultimodalMessage.user("Reply with the word ready."))).build();
    }

    private static ChatResponse response(String id, String model) {
        return ChatResponse.builder().id(id).object("chat.completion").created(0L).model(model).choices(List.of()).build();
    }

    private static RoutingEngine routes(RouteConfig.FallbackTarget... chain) {
        RouteConfig route = RouteConfig.builder().id("support-assistant").modelPattern("gpt-4o")
                .strategy(RouteConfig.Strategy.MODEL_PREFIX).providers(List.of())
                .fallbacks(List.of(chain)).build();
        return new RoutingEngine(new ModelPrefixRoutingStrategy(),
                List.of(new RoutingEngine.ResolvedRoute(route, new ModelPrefixRoutingStrategy())));
    }

    private static ProviderDispatcher dispatcher(List<LlmProvider> providers, RoutingEngine routes) {
        ProviderDispatcher d = new ProviderDispatcher(providers, routes, HEALTHY, SAME_MODEL,
                new GatewayMetrics(new SimpleMeterRegistry(), REGION), REGION, new PassthroughDataResidencyPolicy(),
                ObservationRegistry.NOOP, mock(LatencyTracker.class), mock(CanaryMetricsCollector.class),
                TestProviders.of(mock(CostEstimator.class)), (req, resp, latency, route) -> { });
        return d;
    }

    private static GatewayProperties settings(boolean enabled, int maxAttempts, Duration deadline) {
        GatewayProperties p = new GatewayProperties();
        p.getResilience().getFallback().setEnabled(enabled);
        p.getResilience().getFallback().setMaxAttempts(maxAttempts);
        p.getResilience().getFallback().setDeadline(deadline);
        return p;
    }

    private static final GatewayException OUTAGE = GatewayException.upstream(503, "OpenAI API error 503");

    // ── UC-CPF-A / AC-CPF-01, 02, 06 ────────────────────────────────────────────────────────

    @Test
    void thePrimaryFails_theChainsMappedModelServesIt_andNoOtherProviderIsCalled() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider azure = provider("azure-openai", "gpt");         // serves the same model; not on the chain
        LlmProvider anthropic = provider("anthropic", "claude");
        when(openai.chat(any())).thenThrow(OUTAGE);
        when(anthropic.chat(any())).thenReturn(response("r-1", "claude-test-backup"));
        ChatRequest caller = request("gpt-4o");

        ChatResponse served = dispatcher(List.of(openai, azure, anthropic),
                routes(new RouteConfig.FallbackTarget("anthropic", "claude-test-backup"))).chat(caller);

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(anthropic).chat(sent.capture());
        assertThat(sent.getValue().getModel()).isEqualTo("claude-test-backup");
        assertThat(sent.getValue().getMessages()).isEqualTo(caller.getMessages());
        assertThat(served.getModel()).isEqualTo("claude-test-backup");
        assertThat(caller.getModel()).isEqualTo("gpt-4o");            // the caller's request is untouched
        verify(azure, never()).chat(any());
    }

    @Test
    void theChainIsTriedInOrder() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider mistral = provider("mistral", "mistral");
        LlmProvider anthropic = provider("anthropic", "claude");
        when(openai.chat(any())).thenThrow(OUTAGE);
        when(mistral.chat(any())).thenThrow(GatewayException.upstream(502, "Mistral error 502"));
        when(anthropic.chat(any())).thenReturn(response("r-2", "claude-b"));

        dispatcher(List.of(openai, mistral, anthropic), routes(
                new RouteConfig.FallbackTarget("mistral", "mistral-large"),
                new RouteConfig.FallbackTarget("anthropic", "claude-b"))).chat(request("gpt-4o"));

        InOrder order = inOrder(openai, mistral, anthropic);
        order.verify(openai).chat(any());
        order.verify(mistral).chat(any());
        order.verify(anthropic).chat(any());
    }

    @Test
    void aTargetWithNoModelSendsTheRequestsOwn() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider azure = provider("azure-openai", "gpt");
        when(openai.chat(any())).thenThrow(OUTAGE);
        when(azure.chat(any())).thenReturn(response("r-3", "gpt-4o"));

        dispatcher(List.of(openai, azure), routes(new RouteConfig.FallbackTarget("azure-openai", null))).chat(request("gpt-4o"));

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(azure).chat(sent.capture());
        assertThat(sent.getValue().getModel()).isEqualTo("gpt-4o");
    }

    // ── AC-CPF-02, 11: route boundary and compatibility ─────────────────────────────────────

    @Test
    void aRouteWithNoChainDoesNotFailOver_evenToAProviderServingTheSameModel() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider azure = provider("azure-openai", "gpt");
        when(openai.chat(any())).thenThrow(OUTAGE);

        assertThatThrownBy(() -> dispatcher(List.of(openai, azure), routes()).chat(request("gpt-4o"))).isSameAs(OUTAGE);
        verify(azure, never()).chat(any());
    }

    @Test
    void aRequestMatchingNoRoute_keepsTheSameModelFallback() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider azure = provider("azure-openai", "gpt");
        when(openai.chat(any())).thenThrow(OUTAGE);
        when(azure.chat(any())).thenReturn(response("r-4", "gpt-4o-mini"));

        dispatcher(List.of(openai, azure), routes(new RouteConfig.FallbackTarget("anthropic", "claude-x")))
                .chat(request("gpt-4o-mini"));   // the route is for gpt-4o only

        verify(azure).chat(any());
    }

    @Test
    void fallbackOff_nothingFailsOver() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider anthropic = provider("anthropic", "claude");
        when(openai.chat(any())).thenThrow(OUTAGE);
        ProviderDispatcher d = dispatcher(List.of(openai, anthropic), routes(new RouteConfig.FallbackTarget("anthropic", "claude-x")));
        d.setFallbackSettings(settings(false, 3, Duration.ofSeconds(60)));

        assertThatThrownBy(() -> d.chat(request("gpt-4o"))).isSameAs(OUTAGE);
        verify(anthropic, never()).chat(any());
    }

    // ── AC-CPF-07: bounded ──────────────────────────────────────────────────────────────────

    @Test
    void noMoreTargetsThanMaxAttempts_andNoneAfterTheDeadline() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider a = provider("a", "m");
        LlmProvider b = provider("b", "m");
        LlmProvider c = provider("c", "m");
        when(openai.chat(any())).thenThrow(OUTAGE);
        for (LlmProvider p : List.of(a, b, c)) {
            when(p.chat(any())).thenThrow(GatewayException.upstream(503, "down"));
        }
        RoutingEngine chain = routes(new RouteConfig.FallbackTarget("a", "m1"), new RouteConfig.FallbackTarget("b", "m1"),
                new RouteConfig.FallbackTarget("c", "m1"));

        ProviderDispatcher two = dispatcher(List.of(openai, a, b, c), chain);
        two.setFallbackSettings(settings(true, 2, Duration.ofSeconds(60)));
        assertThatThrownBy(() -> two.chat(request("gpt-4o"))).isSameAs(OUTAGE);
        verify(c, never()).chat(any());

        LlmProvider d = provider("d", "m");
        ProviderDispatcher late = dispatcher(List.of(openai, d), routes(new RouteConfig.FallbackTarget("d", "m1")));
        late.setFallbackSettings(settings(true, 3, Duration.ZERO));
        assertThatThrownBy(() -> late.chat(request("gpt-4o"))).isSameAs(OUTAGE);
        verify(d, never()).chat(any());
    }

    // ── AC-CPF-04, 05: capability and governance per destination ────────────────────────────

    @Test
    void aTargetThatCannotTakeTheRequest_orThatAGuardRefuses_isSkipped() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider noTools = provider("notools", "n", new ProviderCapabilities(true, false, false, false, false, 32_000));
        LlmProvider denied = provider("denied", "d");
        LlmProvider anthropic = provider("anthropic", "claude");
        when(openai.chat(any())).thenThrow(OUTAGE);
        when(anthropic.chat(any())).thenReturn(response("r-5", "claude-ok"));
        ProviderDispatcher d = dispatcher(List.of(openai, noTools, denied, anthropic), routes(
                new RouteConfig.FallbackTarget("notools", "n1"), new RouteConfig.FallbackTarget("denied", "d1"),
                new RouteConfig.FallbackTarget("anthropic", "claude-ok")));
        d.setFallbackGuards(List.of((r, provider) -> "denied".equals(provider) ? "model d1 is not allowed" : null));
        ChatRequest withTools = request("gpt-4o").toBuilder()
                .tools(List.of(ToolDefinition.builder().name("get_rate").build())).build();

        d.chat(withTools);

        verify(noTools, never()).chat(any());
        verify(denied, never()).chat(any());
        verify(anthropic).chat(any());
    }

    @Test
    void whenNoTargetCanTakeTheRequest_theFailoverIsReportedAsBlocked() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider noTools = provider("notools", "n", new ProviderCapabilities(true, false, false, false, false, 32_000));
        when(openai.chat(any())).thenThrow(OUTAGE);
        ChatRequest withTools = request("gpt-4o").toBuilder()
                .tools(List.of(ToolDefinition.builder().name("get_rate").build())).build();

        assertThatThrownBy(() -> dispatcher(List.of(openai, noTools), routes(new RouteConfig.FallbackTarget("notools", "n1")))
                .chat(withTools))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("FAILOVER_CAPABILITY_MISMATCH"));
    }

    @Test
    void aTargetNotServingItsMappedModel_orNotConfigured_isSkipped() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider anthropic = provider("anthropic", "claude");
        when(openai.chat(any())).thenThrow(OUTAGE);

        assertThatThrownBy(() -> dispatcher(List.of(openai, anthropic), routes(
                new RouteConfig.FallbackTarget("anthropic", "gpt-4o"),     // anthropic does not serve gpt
                new RouteConfig.FallbackTarget("nowhere", "x"))).chat(request("gpt-4o"))).isSameAs(OUTAGE);
        verify(anthropic, never()).chat(any());
    }

    // ── AC-CPF-08: a denial is not an outage ────────────────────────────────────────────────

    @Test
    void aRejectedRequestDoesNotFailOver() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider anthropic = provider("anthropic", "claude");
        GatewayException rejected = GatewayException.upstream(400, "OpenAI API error 400");
        when(openai.chat(any())).thenThrow(rejected);

        assertThatThrownBy(() -> dispatcher(List.of(openai, anthropic), routes(new RouteConfig.FallbackTarget("anthropic", "claude-x")))
                .chat(request("gpt-4o"))).isSameAs(rejected);
        verify(anthropic, never()).chat(any());
    }

    // ── UC-CPF-C / AC-CPF-09: streams ───────────────────────────────────────────────────────

    @Test
    void aStreamThatFailsToOpen_failsOver_withTheMappedModel() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider anthropic = provider("anthropic", "claude");
        when(openai.streamChat(any())).thenThrow(OUTAGE);
        Iterator<SseChunk> backup = List.<SseChunk>of().iterator();
        when(anthropic.streamChat(any())).thenReturn(backup);

        Iterator<SseChunk> served = dispatcher(List.of(openai, anthropic),
                routes(new RouteConfig.FallbackTarget("anthropic", "claude-s"))).streamChat(request("gpt-4o"));

        assertThat(served).isSameAs(backup);
        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(anthropic).streamChat(sent.capture());
        assertThat(sent.getValue().getModel()).isEqualTo("claude-s");
    }

    @Test
    void aStreamThatFailsAfterOpening_doesNotFailOver() {
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider anthropic = provider("anthropic", "claude");
        Iterator<SseChunk> broken = new Iterator<>() {
            @Override public boolean hasNext() { return true; }
            @Override public SseChunk next() { throw OUTAGE; }
        };
        when(openai.streamChat(any())).thenReturn(broken);

        Iterator<SseChunk> served = dispatcher(List.of(openai, anthropic),
                routes(new RouteConfig.FallbackTarget("anthropic", "claude-s"))).streamChat(request("gpt-4o"));

        assertThatThrownBy(served::next).isSameAs(OUTAGE);
        verify(anthropic, never()).streamChat(any());
    }

    // ── AC-CPF-03: validation ───────────────────────────────────────────────────────────────

    @Test
    void aBadChainIsNamedWithWhatIsWrong() {
        java.util.function.Function<List<RouteConfig.FallbackTarget>, String> check = chain ->
                RouteConfig.builder().id("r").fallbacks(chain).build().invalidFallbacks();
        assertThat(check.apply(List.of())).isNull();
        assertThat(check.apply(List.of(new RouteConfig.FallbackTarget("anthropic", "c")))).isNull();
        assertThat(check.apply(List.of(new RouteConfig.FallbackTarget(null, "c")))).contains("no provider");
        assertThat(check.apply(List.of(new RouteConfig.FallbackTarget("anthropic", " ")))).contains("blank model");
        assertThat(check.apply(List.of(new RouteConfig.FallbackTarget("a", "c"), new RouteConfig.FallbackTarget("a", "c"))))
                .contains("twice");
        assertThat(check.apply(java.util.stream.IntStream.range(0, 6)
                .mapToObj(i -> new RouteConfig.FallbackTarget("p" + i, null)).toList())).contains("at most 5");
    }
}
