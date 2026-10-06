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
import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyEngine;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.provider.RoutingStrategy;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.routing.CanaryMetricsCollector;
import com.dvarahq.core.routing.LatencyTracker;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RequestContext;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.security.SecurityContext;
import com.dvarahq.server.TestProviders;
import com.dvarahq.server.filter.PolicyEnforcementFilter;
import com.dvarahq.server.filter.PolicyFallbackTargetGuard;
import com.dvarahq.server.filter.PolicyPrimaryTargetGuard;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A policy's model allowlist applies to the model a route actually sends: the request passes the policy filter with
 * the model the caller named, and the dispatcher checks the model it sends against the same policy.
 */
class PolicyPrimaryTargetGuardTest {

    private static final RegionContext REGION = Optional::empty;
    private static final ProviderHealthRegistry HEALTHY = name -> ProviderHealthStatus.HEALTHY;
    private static final FallbackResolver SAME_MODEL = (request, failed, all) -> all.stream()
            .filter(p -> p.supports(request)).filter(p -> !p.name().equals(failed.name())).collect(Collectors.toList());

    /** The model the route pins: the caller names gpt-4o and the route sends this one. */
    private static final String PINNED = "gpt-4o-2024-08-06";

    /** Records every audit event written, by the filter and by the guard. */
    private final List<AuditEvent> audited = new ArrayList<>();
    private final AuditWriter auditWriter = audited::add;

    @BeforeEach
    void inARequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    /** A policy that allows these models and nothing else. */
    private static PolicyEngine allowOnly(String... models) {
        Set<String> allowed = Set.of(models);
        return (ctx, request) -> allowed.contains(request.getModel())
                ? PolicyDecision.ALLOW
                : PolicyDecision.deny("model " + request.getModel() + " is not approved", "models", "allowlist");
    }

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

    /** The dispatcher as the application wires it: both policy guards registered. */
    private ProviderDispatcher dispatcher(PolicyEngine policy, List<LlmProvider> providers, RoutingEngine routes) {
        ProviderDispatcher d = new ProviderDispatcher(providers, routes, HEALTHY, SAME_MODEL,
                new GatewayMetrics(new SimpleMeterRegistry(), REGION), REGION, new PassthroughDataResidencyPolicy(),
                ObservationRegistry.NOOP, mock(LatencyTracker.class), mock(CanaryMetricsCollector.class),
                TestProviders.of(mock(CostEstimator.class)), (req, resp, latency, route) -> { });
        d.setPrimaryGuards(List.of(new PolicyPrimaryTargetGuard(policy, auditWriter)));
        d.setFallbackGuards(List.of(new PolicyFallbackTargetGuard(policy)));
        return d;
    }

    /** Runs the policy filter on the caller's request, as the pipeline does before dispatch. */
    private ChatRequest passPolicyFilter(PolicyEngine policy, ChatRequest request) {
        SecurityContext security = mock(SecurityContext.class);
        when(security.currentUserId()).thenReturn(Optional.empty());
        return new PolicyEnforcementFilter(policy, auditWriter, security, REGION, Optional.empty())
                .preDispatch(request, FilterContext.builder().workspaceId("ws-1").apiKey("key-1").build());
    }

    private void assertOneDenialFor(String model) {
        assertThat(audited).hasSize(1);
        AuditEvent event = audited.get(0);
        assertThat(event.eventType()).isEqualTo("POLICY_DENIED");
        assertThat(event.payload())
                .containsEntry("model", model)
                .containsEntry("status", 403)
                .containsEntry("policy_id", "models")
                .containsEntry("rule_id", "allowlist")
                .containsEntry("workspace_id", "ws-1")
                .containsEntry("api_key", "key-1");
    }

    @Test
    void aPinnedVersionThePolicyDoesNotAllow_isRefusedWithThePolicysError_andAuditedOnce() {
        PolicyEngine policy = allowOnly("gpt-4o");
        LlmProvider openai = provider("openai", "gpt");
        ProviderDispatcher d = dispatcher(policy, List.of(openai), pinningRoute());

        ChatRequest req = passPolicyFilter(policy, request("gpt-4o"));

        assertThatThrownBy(() -> d.chat(req))
                .isInstanceOf(GatewayException.class)
                .hasMessage("model " + PINNED + " is not approved")
                .extracting(e -> ((GatewayException) e).getCode()).isEqualTo("POLICY_DENIED");
        verify(openai, never()).chat(any());
        assertOneDenialFor(PINNED);
    }

    @Test
    void aStreamIsNotOpenedOnAPinnedVersionThePolicyDoesNotAllow() {
        PolicyEngine policy = allowOnly("gpt-4o");
        LlmProvider openai = provider("openai", "gpt");
        ProviderDispatcher d = dispatcher(policy, List.of(openai), pinningRoute());

        ChatRequest req = passPolicyFilter(policy, request("gpt-4o").toBuilder().stream(true).build());

        assertThatThrownBy(() -> d.streamChat(req))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getCode()).isEqualTo("POLICY_DENIED");
        verify(openai, never()).streamChat(any());
        assertOneDenialFor(PINNED);
    }

    @Test
    void aTokenCountIsNotSentForAPinnedVersionThePolicyDoesNotAllow() {
        PolicyEngine policy = allowOnly("gpt-4o");
        LlmProvider openai = provider("openai", "gpt");
        when(openai.countInputTokens(any())).thenReturn(OptionalInt.of(7));
        ProviderDispatcher d = dispatcher(policy, List.of(openai), pinningRoute());

        ChatRequest req = passPolicyFilter(policy, request("gpt-4o"));

        assertThatThrownBy(() -> d.countInputTokens(req))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getCode()).isEqualTo("POLICY_DENIED");
        verify(openai, never()).countInputTokens(any());
        assertOneDenialFor(PINNED);
    }

    @Test
    void aPinnedVersionThePolicyAllows_isSent_andNothingIsAudited() {
        PolicyEngine policy = allowOnly("gpt-4o", PINNED);
        LlmProvider openai = provider("openai", "gpt");
        when(openai.chat(any())).thenReturn(response(PINNED));
        ProviderDispatcher d = dispatcher(policy, List.of(openai), pinningRoute());

        d.chat(passPolicyFilter(policy, request("gpt-4o")));

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(openai).chat(sent.capture());
        assertThat(sent.getValue().getModel()).isEqualTo(PINNED);
        assertThat(audited).isEmpty();
    }

    @Test
    void aRouteFallbackThePolicyAllows_servesTheRequest_andNothingIsAudited() {
        PolicyEngine policy = allowOnly("gpt-4o", "claude-approved");
        LlmProvider openai = provider("openai", "gpt");
        LlmProvider anthropic = provider("anthropic", "claude");
        when(anthropic.chat(any())).thenReturn(response("claude-approved"));
        ProviderDispatcher d = dispatcher(policy, List.of(openai, anthropic),
                pinningRoute(new RouteConfig.FallbackTarget("anthropic", "claude-approved")));

        ChatResponse served = d.chat(passPolicyFilter(policy, request("gpt-4o")));

        assertThat(served.getModel()).isEqualTo("claude-approved");
        verify(openai, never()).chat(any());
        assertThat(audited).isEmpty();
    }

    @Test
    void aCallersModelThePolicyDenies_isAuditedOnceByTheFilter_andNeverReachesTheDispatcher() {
        PolicyEngine policy = allowOnly("gpt-4o");

        assertThatThrownBy(() -> passPolicyFilter(policy, request("gpt-3.5-turbo")))
                .isInstanceOf(GatewayException.class)
                .extracting(e -> ((GatewayException) e).getCode()).isEqualTo("POLICY_DENIED");
        assertOneDenialFor("gpt-3.5-turbo");
    }

    @Test
    void withNoPolicyEvaluatedForTheRequest_theRoutesModelIsSentAsBefore() {
        LlmProvider openai = provider("openai", "gpt");
        when(openai.chat(any())).thenReturn(response(PINNED));
        ProviderDispatcher d = dispatcher(allowOnly("gpt-4o"), List.of(openai), pinningRoute());

        d.chat(request("gpt-4o"));   // the policy filter did not run

        verify(openai).chat(any());
        assertThat(audited).isEmpty();
    }
}
