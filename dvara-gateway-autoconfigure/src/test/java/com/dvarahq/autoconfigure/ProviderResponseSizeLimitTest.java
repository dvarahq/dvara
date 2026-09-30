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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A provider response larger than the configured limit is refused while it is read, with a stable
 * code, instead of being buffered and parsed whole. Checked against a real HTTP upstream through the
 * provider bean the auto-configuration builds.
 */
class ProviderResponseSizeLimitTest {

    private HttpServer server;
    /** How many characters of content the upstream answers with. */
    private volatile int contentChars;
    /** Whether the upstream states a Content-Length or sends the body chunked. */
    private volatile boolean statesLength;

    @BeforeEach
    void startUpstream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] body;
            if (request.contains("\"stream\":true")) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                StringBuilder sse = new StringBuilder();
                for (int sent = 0; sent < contentChars; sent += 100) {
                    sse.append("data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"")
                            .append("x".repeat(100)).append("\"},\"finish_reason\":null}]}\n\n");
                }
                sse.append("data: {\"id\":\"c\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n");
                sse.append("data: [DONE]\n\n");
                body = sse.toString().getBytes(StandardCharsets.UTF_8);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                body = ("{\"id\":\"c\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"" + "x".repeat(contentChars) + "\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(200, statesLength ? body.length : 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (Exception clientStoppedReading) {
                // expected when the gateway refuses the body part-way
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ApplicationContextRunner runner(String... limits) {
        SecretProvider secrets = key -> Optional.of("test-key");
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ProviderAutoConfiguration.class))
                .withBean(SecretProvider.class, () -> secrets)
                .withBean(RestClient.Builder.class, RestClient::builder)
                .withPropertyValues(
                        "dvara.llm-gateway.providers.openai.api-key=test-key",
                        "dvara.llm-gateway.providers.openai.base-url=http://127.0.0.1:" + server.getAddress().getPort() + "/v1")
                .withPropertyValues(limits);
    }

    private static ChatRequest request() {
        return ChatRequest.builder().model("gpt-4o").messages(List.of(MultimodalMessage.user("hi"))).build();
    }

    @Test
    void aChunkedBodyOverTheLimitIsRefusedWithAStableCode() {
        contentChars = 50_000;
        runner("dvara.llm-gateway.response-limits.max-body-bytes=10000").run(context -> {
            OpenAiProvider provider = context.getBean(OpenAiProvider.class);

            assertThatThrownBy(() -> provider.chat(request()))
                    .isInstanceOf(GatewayException.class)
                    .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_RESPONSE_TOO_LARGE"))
                    .hasMessageContaining("10000");
        });
    }

    @Test
    void aBodyWhoseStatedLengthIsOverTheLimitIsRefused() {
        contentChars = 50_000;
        statesLength = true;
        runner("dvara.llm-gateway.response-limits.max-body-bytes=10000").run(context -> {
            OpenAiProvider provider = context.getBean(OpenAiProvider.class);

            assertThatThrownBy(() -> provider.chat(request()))
                    .isInstanceOf(GatewayException.class)
                    .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_RESPONSE_TOO_LARGE"));
        });
    }

    @Test
    void aBodyUnderTheLimitIsServed() {
        contentChars = 5_000;
        runner("dvara.llm-gateway.response-limits.max-body-bytes=10000").run(context -> {
            ChatResponse response = context.getBean(OpenAiProvider.class).chat(request());

            assertThat(response.getChoices().get(0).getMessage().textContent()).hasSize(5_000);
        });
    }

    @Test
    void aStreamOverItsLimitIsCutOffWithAStableCode() {
        contentChars = 50_000;
        runner("dvara.llm-gateway.response-limits.max-stream-bytes=20000").run(context -> {
            Iterator<SseChunk> chunks = context.getBean(OpenAiProvider.class).streamChat(request());

            assertThatThrownBy(() -> {
                while (chunks.hasNext()) {
                    chunks.next();
                }
            }).isInstanceOf(GatewayException.class)
                    .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_RESPONSE_TOO_LARGE"));
        });
    }

    @Test
    void theBodyLimitDoesNotCutAStreamThatIsWithinTheStreamLimit() {
        contentChars = 50_000;
        runner("dvara.llm-gateway.response-limits.max-body-bytes=10000",
                "dvara.llm-gateway.response-limits.max-stream-bytes=1000000").run(context -> {
            Iterator<SseChunk> chunks = context.getBean(OpenAiProvider.class).streamChat(request());
            int chars = 0;
            while (chunks.hasNext()) {
                String delta = chunks.next().getDelta();
                chars += delta == null ? 0 : delta.length();
            }

            assertThat(chars).isEqualTo(50_000);
        });
    }

    @Test
    void zeroTurnsTheLimitOff() {
        contentChars = 50_000;
        runner("dvara.llm-gateway.response-limits.max-body-bytes=0").run(context -> {
            ChatResponse response = context.getBean(OpenAiProvider.class).chat(request());

            assertThat(response.getChoices().get(0).getMessage().textContent()).hasSize(50_000);
        });
    }
}
