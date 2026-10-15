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
package com.dvarahq.autoconfigure;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every provider is called through one JDK HTTP client chosen by the gateway, not through whichever
 * client the application's {@code RestClient.Builder} would pick from the classpath.
 */
class UpstreamHttpClientTest {

    private static final byte[] COMPLETION = ("{\"id\":\"c\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,"
            + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
            .getBytes(StandardCharsets.UTF_8);

    private HttpServer server;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger mostInFlight = new AtomicInteger();

    @BeforeEach
    void startUpstream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            mostInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, COMPLETION.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(COMPLETION);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    /** A builder whose own client fails every call: a provider that used it would fail too. */
    private ApplicationContextRunner runner() {
        SecretProvider secrets = key -> Optional.of("test-key");
        ClientHttpRequestFactory classpathClient = (uri, method) -> {
            throw new IllegalStateException("the application's own client was used for a provider call");
        };
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProviderAutoConfiguration.class))
                .withBean(SecretProvider.class, () -> secrets)
                .withBean(RestClient.Builder.class, () -> RestClient.builder().requestFactory(classpathClient))
                .withPropertyValues(
                        "dvara.llm-gateway.providers.openai.api-key=test-key",
                        "dvara.llm-gateway.providers.openai.base-url=http://127.0.0.1:"
                                + server.getAddress().getPort() + "/v1");
    }

    private static ChatRequest request() {
        return ChatRequest.builder().model("gpt-4o").messages(List.of(MultimodalMessage.user("hi"))).build();
    }

    @Test
    void providersCallThroughTheGatewaysClientNotTheApplicationsBuilderClient() {
        runner().run(context -> {
            OpenAiProvider provider = context.getBean(OpenAiProvider.class);

            assertThat(provider.chat(request()).getChoices()).hasSize(1);
        });
    }

    @Test
    void manyCallsToOneProviderRunAtOnceRatherThanQueueingForAFewConnections() {
        runner().run(context -> {
            OpenAiProvider provider = context.getBean(OpenAiProvider.class);
            int calls = 24;
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> done = new java.util.ArrayList<>();
                for (int i = 0; i < calls; i++) {
                    done.add(pool.submit(() -> provider.chat(request())));
                }
                for (Future<?> f : done) {
                    f.get();
                }
            }
            // Apache HttpComponents' default pool allows 5 connections per host; the JDK client has no such cap.
            assertThat(mostInFlight.get()).isGreaterThan(5);
        });
    }

    @Test
    void withVirtualThreadsOnTheClientRunsItsWorkOnVirtualThreads() {
        runner().withPropertyValues("spring.threads.virtual.enabled=true").run(context -> {
            HttpClient client = client(context.getBean(ProviderAutoConfiguration.class).upstreamRequestFactory());

            // Without an executor the JDK client uses its own cached pool of platform threads.
            assertThat(client.executor()).isPresent();
            java.util.concurrent.CompletableFuture<Boolean> virtual = new java.util.concurrent.CompletableFuture<>();
            client.executor().get().execute(() -> virtual.complete(Thread.currentThread().isVirtual()));
            assertThat(virtual.get(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(context.getBean(OpenAiProvider.class).chat(request()).getChoices()).hasSize(1);
        });
    }

    @Test
    void withVirtualThreadsOffTheClientKeepsTheJdkDefault() {
        runner().run(context -> {
            HttpClient client = client(context.getBean(ProviderAutoConfiguration.class).upstreamRequestFactory());

            assertThat(client.executor()).isEmpty();
        });
    }

    @Test
    void aGatewaySettingWinsOverTheApplicationsAndTheVersionIsApplied() {
        GatewayProperties.UpstreamHttpConfig config = new GatewayProperties.UpstreamHttpConfig();
        config.setConnectTimeout(Duration.ofSeconds(3));
        config.setVersion(HttpClient.Version.HTTP_1_1);
        HttpClientSettings application = HttpClientSettings.defaults()
                .withTimeouts(Duration.ofSeconds(7), Duration.ofSeconds(9));

        ClientHttpRequestFactory factory = ProviderAutoConfiguration.upstreamRequestFactory(config, application);

        HttpClient client = client(factory);
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(3));
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        // No gateway read timeout, so the application's applies.
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isEqualTo(Duration.ofSeconds(9));
    }

    @Test
    void withNothingSetTheConnectTimeoutIsTenSecondsAndThereIsNoReadTimeout() {
        ClientHttpRequestFactory factory = ProviderAutoConfiguration.upstreamRequestFactory(
                new GatewayProperties.UpstreamHttpConfig(), null);

        HttpClient client = client(factory);
        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(10));
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(ReflectionTestUtils.getField(factory, "readTimeout")).isNull();
    }

    @Test
    void emptyValuesFromUnsetEnvironmentVariablesBindAsUnset() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(
                        "dvara.llm-gateway.upstream-http.version=",
                        "dvara.llm-gateway.upstream-http.connect-timeout=",
                        "dvara.llm-gateway.upstream-http.read-timeout=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GatewayProperties.UpstreamHttpConfig config =
                            context.getBean(GatewayProperties.class).getUpstreamHttp();
                    assertThat(config.getVersion()).isNull();
                    assertThat(config.getConnectTimeout()).isNull();
                    assertThat(config.getReadTimeout()).isNull();
                });
    }

    @Test
    void setValuesBind() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesOnly.class)
                .withPropertyValues(
                        "dvara.llm-gateway.upstream-http.version=HTTP_1_1",
                        "dvara.llm-gateway.upstream-http.connect-timeout=2s",
                        "dvara.llm-gateway.upstream-http.read-timeout=90s")
                .run(context -> {
                    GatewayProperties.UpstreamHttpConfig config =
                            context.getBean(GatewayProperties.class).getUpstreamHttp();
                    assertThat(config.getVersion()).isEqualTo(HttpClient.Version.HTTP_1_1);
                    assertThat(config.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(config.getReadTimeout()).isEqualTo(Duration.ofSeconds(90));
                });
    }

    private static HttpClient client(ClientHttpRequestFactory factory) {
        assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);
        return (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(GatewayProperties.class)
    static class PropertiesOnly {
    }
}
