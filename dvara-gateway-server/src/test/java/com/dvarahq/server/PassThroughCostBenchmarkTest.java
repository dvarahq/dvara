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
package com.dvarahq.server;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the gateway adds to one request when no rule reads its body, timed on one thread: the request
 * through the assembled gateway to an upstream on this machine, against the same request sent straight
 * to that upstream. Run by hand ({@code PASS_THROUGH_BENCH=true}); the figures go in a change's
 * description, not in a pass/fail bound, since a shared machine moves them.
 */
@EnabledIfEnvironmentVariable(named = "PASS_THROUGH_BENCH", matches = "true")
@SpringBootTest(classes = GatewayServerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PassThroughCostBenchmarkTest {

    static final String KEY = "gw_passthrough_bench_key_000000000000000";
    static final String REPLY = "{\"id\":\"chatcmpl-bench\",\"object\":\"chat.completion\",\"created\":1760000000,"
            + "\"model\":\"gpt-4o-mini\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"This is a fixed reply from the benchmark mock upstream.\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":12,\"total_tokens\":32}}";

    @TempDir
    static Path configDir;
    static HttpServer upstream;

    @LocalServerPort
    int port;

    @BeforeAll
    static void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        upstream.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
            }
            byte[] body = REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: openai
                    api_key: sk-bench
                    base_url: http://127.0.0.1:%d
                workspaces:
                  - id: bench
                    metadata:
                      pii.enabled: false
                      guardrail.enabled: false
                routes:
                  - id: bench
                    model: "gpt-4o-mini"
                    provider: openai
                api_keys:
                  - key_hash: sha256:%s
                    workspace: bench
                    name: bench
                """.formatted(upstream.getAddress().getPort(), sha256(KEY)));
        System.setProperty("DVARA_CONFIG_FILE", file.toString());
    }

    @AfterAll
    static void stop() {
        System.clearProperty("DVARA_CONFIG_FILE");
        upstream.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("management.tracing.sampling.probability", () -> "0.0");
        registry.add("logging.level.com.dvarahq", () -> "WARN");
    }

    @Test
    void timeOneRequestThroughTheGatewayAgainstStraightToTheUpstream() throws Exception {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        URI direct = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1/chat/completions");
        URI gateway = URI.create("http://127.0.0.1:" + port + "/v1/chat/completions");
        int warm = Integer.getInteger("bench.warm", 3000);
        int runs = Integer.getInteger("bench.runs", 3000);
        StringBuilder report = new StringBuilder();
        for (String payload : new String[] {"small", "10kb", "50kb"}) {
            byte[] body = readPayload(payload);
            assertThat(send(client, gateway, body).statusCode()).isEqualTo(200);
            double[] d = time(client, direct, body, warm, runs);
            double[] g = time(client, gateway, body, warm, runs);
            report.append(String.format("%s: direct p50 %.0f us mean %.0f us | gateway p50 %.0f us mean %.0f us | added p50 %.0f us mean %.0f us%n",
                    payload, d[0], d[1], g[0], g[1], g[0] - d[0], g[1] - d[1]));
        }
        System.out.println("PASS-THROUGH-BENCH\n" + report);
    }

    private static double[] time(HttpClient client, URI uri, byte[] body, int warm, int runs) throws Exception {
        for (int i = 0; i < warm; i++) {
            send(client, uri, body);
        }
        long[] nanos = new long[runs];
        long total = 0;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            HttpResponse<byte[]> response = send(client, uri, body);
            nanos[i] = System.nanoTime() - start;
            total += nanos[i];
            if (response.statusCode() != 200) {
                throw new IllegalStateException("status " + response.statusCode() + ": "
                        + new String(response.body(), StandardCharsets.UTF_8));
            }
        }
        Arrays.sort(nanos);
        return new double[] {nanos[runs / 2] / 1_000.0, total / (double) runs / 1_000.0};
    }

    private static HttpResponse<byte[]> send(HttpClient client, URI uri, byte[] body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + KEY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] readPayload(String name) throws IOException {
        try (InputStream in = PassThroughCostBenchmarkTest.class.getResourceAsStream("/bench/" + name + ".json")) {
            return in.readAllBytes();
        }
    }

    private static String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
