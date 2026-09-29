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
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.routing.CanaryMetricsCollector;
import com.dvarahq.core.routing.LatencyTracker;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.anthropic.AnthropicProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import com.dvarahq.server.TestProviders;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #7 AC-CPF-01/02 (UC-CPF-A), with the real adapters: the real {@link OpenAiProvider} fails against a
 * controlled upstream answering 503, and the real {@link AnthropicProvider} serves the request against
 * another, with the route's mapped model and its own credential on the wire. No mock stands in for either.
 */
class CrossProviderFailoverIntegrationTest {

    private HttpServer openaiUpstream;
    private HttpServer anthropicUpstream;
    private final AtomicInteger openaiCalls = new AtomicInteger();
    private final List<String> anthropicBodies = new CopyOnWriteArrayList<>();
    private final List<String> anthropicKeys = new CopyOnWriteArrayList<>();
    private final List<String> anthropicBearer = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startUpstreams() throws Exception {
        openaiUpstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        openaiUpstream.createContext("/v1/chat/completions", x -> {
            openaiCalls.incrementAndGet();
            x.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"overloaded\"}}".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(503, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        openaiUpstream.start();

        anthropicUpstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        anthropicUpstream.createContext("/v1/messages", x -> {
            anthropicBodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            anthropicKeys.add(String.valueOf(x.getRequestHeaders().getFirst("x-api-key")));
            anthropicBearer.add(String.valueOf(x.getRequestHeaders().getFirst("Authorization")));
            byte[] body = ("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test-backup\","
                    + "\"content\":[{\"type\":\"text\",\"text\":\"ready\"}],\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        anthropicUpstream.start();
    }

    @AfterEach
    void stopUpstreams() {
        openaiUpstream.stop(0);
        anthropicUpstream.stop(0);
    }

    /** Each provider's own key, as the installation would hold them. */
    private static final SecretProvider SECRETS = key -> Optional.ofNullable(Map.of(
            "provider.openai.api-key", "sk-openai-test",
            "provider.anthropic.api-key", "sk-ant-test").get(key));

    /** Sends the real Anthropic client, which targets api.anthropic.com, to the controlled upstream instead. */
    private ClientHttpRequestInterceptor toAnthropicUpstream() {
        int port = anthropicUpstream.getAddress().getPort();
        return (request, body, execution) -> execution.execute(new HttpRequestWrapper(request) {
            @Override
            public URI getURI() {
                URI u = request.getURI();
                return URI.create("http://127.0.0.1:" + port + u.getRawPath() + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()));
            }
        }, body);
    }

    @Test
    void aGptOutageIsServedByClaude_withTheMappedModel_andNoOtherProviderIsCalled() {
        OpenAiProvider openai = new OpenAiProvider(SECRETS,
                "http://127.0.0.1:" + openaiUpstream.getAddress().getPort() + "/v1", RestClient.builder());
        AnthropicProvider anthropic = new AnthropicProvider(SECRETS, RestClient.builder().requestInterceptor(toAnthropicUpstream()));
        // Serves gpt-4o too, so the old registry-wide fallback would have picked it; it is not on the route.
        LlmProvider unrelated = mock(LlmProvider.class);
        when(unrelated.name()).thenReturn("azure-openai");
        when(unrelated.supports(any())).thenAnswer(inv -> ((ChatRequest) inv.getArgument(0)).getModel().startsWith("gpt"));

        RouteConfig route = RouteConfig.builder().id("support-assistant").modelPattern("gpt-4o")
                .strategy(RouteConfig.Strategy.MODEL_PREFIX).providers(List.of())
                .fallbacks(List.of(new RouteConfig.FallbackTarget("anthropic", "claude-test-backup"))).build();
        RegionContext region = () -> Optional.empty();
        ProviderDispatcher dispatcher = new ProviderDispatcher(List.of(openai, unrelated, anthropic),
                new RoutingEngine(new ModelPrefixRoutingStrategy(),
                        List.of(new RoutingEngine.ResolvedRoute(route, new ModelPrefixRoutingStrategy()))),
                name -> ProviderHealthStatus.HEALTHY,
                (request, failed, all) -> all.stream().filter(p -> p.supports(request) && !p.name().equals(failed.name())).toList(),
                new GatewayMetrics(new SimpleMeterRegistry(), region), region, new PassthroughDataResidencyPolicy(),
                ObservationRegistry.NOOP, mock(LatencyTracker.class), mock(CanaryMetricsCollector.class),
                TestProviders.of(mock(CostEstimator.class)), (req, resp, latency, r) -> { });

        ChatResponse response = dispatcher.chat(ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("Reply with the word ready."))).build());

        assertThat(openaiCalls.get()).isGreaterThanOrEqualTo(1);                    // the primary really failed
        assertThat(anthropicBodies).hasSize(1);
        assertThat(anthropicBodies.get(0)).contains("\"model\":\"claude-test-backup\"")
                .contains("Reply with the word ready.");
        assertThat(anthropicKeys.get(0)).isEqualTo("sk-ant-test");                  // its own credential
        assertThat(anthropicBearer.get(0)).doesNotContain("sk-openai-test");        // never OpenAI's
        assertThat(response.getChoices().get(0).getMessage().getContent().get(0))
                .isEqualTo(new ContentBlock.TextBlock("ready"));
        verify(unrelated, never()).chat(any());
    }
}
