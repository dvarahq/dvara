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
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.routing.CanaryMetricsCollector;
import com.dvarahq.core.routing.LatencyTracker;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import com.dvarahq.server.TestProviders;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A route's {@code pinned-model-version} is the model the provider is sent. The real {@link OpenAiProvider} calls a
 * controlled upstream, which records the model in each body it receives. Before this, the pin chose the provider and
 * the caller's own model went upstream.
 */
class PinnedModelVersionIntegrationTest {

    private HttpServer upstream;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/v1/chat/completions", x -> {
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            boolean stream = body.contains("\"stream\":true");
            byte[] out;
            if (status != 200) {
                out = "{\"error\":{\"message\":\"overloaded\"}}".getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().add("Content-Type", "application/json");
            } else if (stream) {
                out = ("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"created\":1,"
                        + "\"model\":\"gpt-test-version\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().add("Content-Type", "text/event-stream");
            } else {
                out = ("{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-test-version\","
                        + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                        + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1,"
                        + "\"total_tokens\":4}}").getBytes(StandardCharsets.UTF_8);
                x.getResponseHeaders().add("Content-Type", "application/json");
            }
            x.sendResponseHeaders(status, out.length);
            x.getResponseBody().write(out);
            x.close();
        });
        upstream.start();
    }

    @AfterEach
    void stop() {
        upstream.stop(0);
        RequestContextHolder.resetRequestAttributes();
    }

    private static final SecretProvider SECRETS = key -> Optional.ofNullable(
            Map.of("provider.openai.api-key", "sk-openai-test").get(key));

    private ProviderDispatcher dispatcher(RouteConfig route, LlmProvider... more) {
        OpenAiProvider openai = new OpenAiProvider(SECRETS,
                "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1", RestClient.builder());
        List<LlmProvider> providers = new ArrayList<>(List.of(openai));
        providers.addAll(List.of(more));
        RegionContext region = () -> Optional.empty();
        return new ProviderDispatcher(providers,
                new RoutingEngine(new ModelPrefixRoutingStrategy(),
                        List.of(new RoutingEngine.ResolvedRoute(route, new ModelPrefixRoutingStrategy()))),
                name -> ProviderHealthStatus.HEALTHY,
                (request, failed, all) -> all.stream().filter(p -> !p.name().equals(failed.name())).toList(),
                new GatewayMetrics(new SimpleMeterRegistry(), region), region, new PassthroughDataResidencyPolicy(),
                ObservationRegistry.NOOP, mock(LatencyTracker.class), mock(CanaryMetricsCollector.class),
                TestProviders.of(mock(CostEstimator.class)), (req, resp, latency, r) -> { });
    }

    private static RouteConfig pinned(List<RouteConfig.FallbackTarget> fallbacks) {
        return RouteConfig.builder().id("support").modelPattern("support-model")
                .strategy(RouteConfig.Strategy.MODEL_PREFIX).providers(List.of())
                .pinnedModelVersion("gpt-test-version").fallbacks(fallbacks).build();
    }

    private static ChatRequest alias() {
        return ChatRequest.builder().model("support-model")
                .messages(List.of(MultimodalMessage.user("Say ok."))).build();
    }

    private static MockHttpServletRequest http() {
        MockHttpServletRequest http = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        return http;
    }

    @Test
    void aChatOnAPinnedRouteSendsThePinnedModelAndRecordsIt() {
        MockHttpServletRequest http = http();
        ChatRequest request = alias();

        dispatcher(pinned(List.of())).chat(request);

        assertThat(bodies).singleElement().satisfies(b -> assertThat(b)
                .contains("\"model\":\"gpt-test-version\"").doesNotContain("support-model"));
        assertThat(http.getAttribute(ProviderDispatcher.SERVED_MODEL_ATTR)).as("the model the evidence names")
                .isEqualTo("gpt-test-version");
        assertThat(request.getModel()).as("the caller's request is not changed").isEqualTo("support-model");
    }

    @Test
    void aStreamOnAPinnedRouteOpensWithThePinnedModel() {
        Iterator<SseChunk> chunks = dispatcher(pinned(List.of())).streamChat(alias());
        chunks.forEachRemaining(c -> { });

        assertThat(bodies).singleElement().satisfies(b -> assertThat(b).contains("\"model\":\"gpt-test-version\""));
    }

    @Test
    void aStreamThatFailsToOpenFallsBackWithThePinnedModel() {
        status = 503;
        LlmProvider backup = mock(LlmProvider.class);
        when(backup.name()).thenReturn("backup");
        when(backup.supports(any())).thenReturn(true);
        when(backup.capabilities()).thenReturn(new com.dvarahq.core.provider.ProviderCapabilities(
                true, false, true, true, true, 128_000));
        List<String> backupModels = new CopyOnWriteArrayList<>();
        when(backup.streamChat(any())).thenAnswer(inv -> {
            backupModels.add(((ChatRequest) inv.getArgument(0)).getModel());
            return List.<SseChunk>of().iterator();
        });

        dispatcher(pinned(List.of(new RouteConfig.FallbackTarget("backup", null))), backup).streamChat(alias());

        assertThat(bodies).as("the primary was tried with the pin").isNotEmpty()
                .allSatisfy(b -> assertThat(b).contains("\"model\":\"gpt-test-version\""));
        assertThat(backupModels).containsExactly("gpt-test-version");
    }

    @Test
    void aRouteWithNoPinSendsTheCallersModel() {
        RouteConfig plain = RouteConfig.builder().id("plain").modelPattern("gpt-4o")
                .strategy(RouteConfig.Strategy.MODEL_PREFIX).providers(List.of()).build();

        dispatcher(plain).chat(ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("Say ok."))).build());

        assertThat(bodies).singleElement().satisfies(b -> assertThat(b).contains("\"model\":\"gpt-4o\""));
    }

    @Test
    void aBlankOrSpacedPinIsRefused() {
        assertThat(RouteConfig.builder().id("r").pinnedModelVersion(" ").build().invalidPin()).contains("blank");
        assertThat(RouteConfig.builder().id("r").pinnedModelVersion("gpt 4o").build().invalidPin()).contains("spaces");
        assertThat(RouteConfig.builder().id("r").pinnedModelVersion("gpt-4o-2024-08-06").build().invalidPin()).isNull();
        assertThat(RouteConfig.builder().id("r").build().invalidPin()).isNull();
    }
}
