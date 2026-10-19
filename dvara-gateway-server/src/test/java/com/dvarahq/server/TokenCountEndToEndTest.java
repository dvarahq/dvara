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

import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.anthropic.AnthropicProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code POST /v1/tokens/count} through the whole gateway: a chat body counted for one model or several, routed as a
 * call would be, with no model called and nothing booked. Anthropic and OpenAI are a local stub: Anthropic's count comes
 * back from its {@code count_tokens}, and OpenAI, which has no count endpoint, is counted by the gateway.
 */
@SpringBootTest(classes = GatewayServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("mocktest")
@Import(TokenCountEndToEndTest.StubAnthropicConfig.class)
class TokenCountEndToEndTest {

    @TempDir
    static Path configDir;

    static final String KEY = "dvara-e2e-token-count-key";
    static final String BLOCK_KEY = "dvara-e2e-token-count-block-key";
    static final String EMBED_ONLY_KEY = "dvara-e2e-token-count-embed-key";
    static final String LIMITED_KEY = "dvara-e2e-token-count-limited-key";
    static final ObjectMapper JSON = new ObjectMapper();

    static HttpServer stub;
    /** Every path the stub was asked for, in order. */
    static final List<String> calls = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void startStubAndWriteGatewayYaml() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", ex -> {
            calls.add(ex.getRequestURI().getPath());
            ex.getRequestBody().readAllBytes();
            answer(ex, "{\"input_tokens\": 4242}");
        });
        stub.start();

        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: openai
                    api_key: stub-openai-key
                    base_url: http://127.0.0.1:%d/v1
                  - type: anthropic
                    api_key: stub-anthropic-key
                workspaces:
                  - id: counter
                    name: Counter
                    status: ACTIVE
                    metadata:
                      guardrail.context.warning-threshold-pct: 60
                      guardrail.context.hard-threshold-pct: 80
                  - id: blocker
                    name: Blocker
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: BLOCK
                  - id: limited
                    name: Limited
                    status: ACTIVE
                    metadata:
                      rate-limit.requests-per-minute: 1
                api_keys:
                  - key_hash: sha256:%s
                    workspace: counter
                    name: counter-key
                  - key_hash: sha256:%s
                    workspace: blocker
                    name: block-key
                  - key_hash: sha256:%s
                    workspace: counter
                    name: embed-only-key
                    scopes: [embeddings:write]
                  - key_hash: sha256:%s
                    workspace: limited
                    name: limited-key
                routes:
                  - id: claude-route
                    model: "claude*"
                    provider: anthropic
                  - id: gpt-route
                    model: "gpt*"
                    provider: openai
                """.formatted(stub.getAddress().getPort(), h(KEY), h(BLOCK_KEY), h(EMBED_ONLY_KEY), h(LIMITED_KEY)));
        System.setProperty("DVARA_CONFIG_FILE", file.toString());
    }

    @AfterAll
    static void stopStub() {
        System.clearProperty("DVARA_CONFIG_FILE");
        if (stub != null) {
            stub.stop(0);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dvara.audit.file.path", () -> configDir.resolve("audit.log").toString());
        registry.add("dvara.audit.hmac-secret", () -> "e2e-token-count-audit-secret-for-the-file-chain");
        registry.add("dvara.llm-gateway.rate-limit.enabled", () -> "true");
    }

    /** The Anthropic provider, its calls sent to the stub rather than to Anthropic. */
    @TestConfiguration
    static class StubAnthropicConfig {
        @Bean
        AnthropicProvider anthropicProvider(SecretProvider secretProvider) {
            ClientHttpRequestInterceptor toStub = (request, body, execution) -> {
                URI original = request.getURI();
                URI rewritten = URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + original.getRawPath());
                return execution.execute(new HttpRequestWrapper(request) {
                    @Override public URI getURI() { return rewritten; }
                }, body);
            };
            return new AnthropicProvider(secretProvider, RestClient.builder().requestInterceptor(toStub));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired TokenUsageRepository tokenUsageRepository;

    @BeforeEach
    void clearStub() {
        calls.clear();
    }

    static final String CHAT = """
            {"model": "%s", "max_tokens": 500,
             "messages": [
               {"role": "system", "content": "You are a careful assistant who answers briefly."},
               {"role": "user", "content": "What is the weather in Paris?"},
               {"role": "assistant", "content": null,
                "tool_calls": [{"id": "c1", "type": "function",
                  "function": {"name": "weather", "arguments": "{\\"city\\": \\"Paris\\"}"}}]},
               {"role": "tool", "tool_call_id": "c1", "content": "Sunny, 21 degrees, light wind from the west."}],
             "tools": [{"type": "function", "function": {"name": "weather", "description": "Current weather",
               "parameters": {"type": "object", "properties": {"city": {"type": "string"}}}}}]}
            """;

    @Test
    void aModelWithNoCountEndpointIsCountedByTheGateway_partByPart_andNothingIsBooked() throws Exception {
        int rowsBefore = tokenUsageRepository.findByWorkspaceId("counter").size();

        MockHttpServletResponse response = count(KEY, CHAT.formatted("gpt-4o"));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.path("object").asText()).isEqualTo("token_count");
        JsonNode c = body.path("counts").get(0);
        assertThat(c.path("model").asText()).isEqualTo("gpt-4o");
        assertThat(c.path("provider").asText()).isEqualTo("openai");
        assertThat(c.path("counted_by").asText()).isEqualTo("gateway");
        int input = c.path("input_tokens").asInt();
        assertThat(input).isPositive();

        JsonNode parts = c.path("breakdown");
        assertThat(parts.path("total").asInt()).as("the parts add up to the count").isEqualTo(input);
        int sum = 0;
        for (String part : List.of("system", "user", "assistant", "tools", "tool_results", "images", "overhead")) {
            sum += parts.path(part).asInt();
        }
        assertThat(sum).isEqualTo(input);
        assertThat(parts.path("system").asInt()).isPositive();
        assertThat(parts.path("user").asInt()).isPositive();
        assertThat(parts.path("assistant").asInt()).as("the tool call the assistant made").isPositive();
        assertThat(parts.path("tool_results").asInt()).isPositive();
        assertThat(parts.path("tools").asInt()).isPositive();
        assertThat(parts.path("images").asInt()).isZero();
        assertThat(parts.path("per_message")).hasSize(4);
        assertThat(c.path("tokenizer").path("encoding").asText()).isEqualTo("o200k_base");
        assertThat(c.path("tokenizer").path("exact").asBoolean()).isTrue();

        JsonNode context = c.path("context");
        assertThat(context.path("window_tokens").asInt()).isPositive();
        assertThat(context.path("warning_threshold_pct").asInt()).as("the workspace's own").isEqualTo(60);
        assertThat(context.path("hard_threshold_pct").asInt()).isEqualTo(80);
        assertThat(context.path("overflows").asBoolean()).isFalse();
        assertThat(c.path("max_tokens").asInt()).isEqualTo(500);

        assertThat(c.path("pricing_known").asBoolean()).as("this build has no prices").isFalse();
        assertThat(c.has("input_cost_usd")).as("no price is never a cost of zero").isFalse();

        assertThat(calls).as("no provider was called").isEmpty();
        Thread.sleep(300);
        assertThat(tokenUsageRepository.findByWorkspaceId("counter")).hasSize(rowsBefore);
    }

    @Test
    void onAnAnthropicRouteAnthropicCounts_andNoModelIsCalled() throws Exception {
        MockHttpServletResponse response = count(KEY, CHAT.formatted("claude-stub-1"));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode c = JSON.readTree(response.getContentAsString()).path("counts").get(0);
        assertThat(c.path("input_tokens").asInt()).isEqualTo(4242);
        assertThat(c.path("counted_by").asText()).isEqualTo("provider");
        assertThat(c.path("provider").asText()).isEqualTo("anthropic");
        assertThat(calls).containsExactly("/v1/messages/count_tokens");
    }

    @Test
    void severalModelsAreComparedInOneCall_andOneThatCannotBeRoutedAnswersForItself() throws Exception {
        String body = CHAT.formatted("gpt-4o").replaceFirst("\\{",
                "{\"models\": [\"gpt-4o\", \"claude-stub-1\", \"gpt-4o\", \"no-such-model-xyz\"], ");

        MockHttpServletResponse response = count(KEY, body);

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode counts = JSON.readTree(response.getContentAsString()).path("counts");
        assertThat(counts).as("a repeated model is counted once").hasSize(3);
        assertThat(counts.get(0).path("model").asText()).isEqualTo("gpt-4o");
        assertThat(counts.get(1).path("input_tokens").asInt()).isEqualTo(4242);
        assertThat(counts.get(2).path("model").asText()).isEqualTo("no-such-model-xyz");
        assertThat(counts.get(2).path("error").path("code").asText()).isNotBlank();
        assertThat(counts.get(2).has("input_tokens")).isFalse();
    }

    @Test
    void aReservedOutputThatDoesNotFitIsReportedAsAnOverflow() throws Exception {
        MockHttpServletResponse response = count(KEY, CHAT.formatted("gpt-4o")
                .replace("\"max_tokens\": 500", "\"max_tokens\": 100000000"));

        JsonNode c = JSON.readTree(response.getContentAsString()).path("counts").get(0);
        assertThat(c.path("context").path("overflows").asBoolean()).isTrue();
        assertThat(c.path("context").path("over_hard_threshold").asBoolean())
                .as("the input alone is well inside the window").isFalse();
    }

    @Test
    void theChecksThatGovernWhatARequestSaysStillRun() throws Exception {
        String body = """
                {"model": "gpt-4o", "messages": [{"role": "user", "content": "My SSN is 123-45-6789"}]}
                """;
        MockHttpServletResponse response = count(BLOCK_KEY, body);
        MockHttpServletResponse chat = mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + BLOCK_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();

        assertThat(JSON.readTree(response.getContentAsString()).path("error").path("code").asText())
                .isEqualTo("pii_detected");
        assertThat(response.getStatus()).as("the status a call gets").isEqualTo(chat.getStatus());
        assertThat(calls).isEmpty();
    }

    @Test
    void aKeyWithoutTheChatScopeIsRefused() throws Exception {
        MockHttpServletResponse response = count(EMBED_ONLY_KEY, CHAT.formatted("gpt-4o"));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(403);
    }

    @Test
    void aCountIsRateLimitedLikeAnyOtherCall() throws Exception {
        String body = CHAT.formatted("gpt-4o");
        assertThat(count(LIMITED_KEY, body).getStatus()).isEqualTo(200);
        assertThat(count(LIMITED_KEY, body).getStatus()).isEqualTo(429);
    }

    @Test
    void aBodyItCannotCountIsRefusedWithTheReason() throws Exception {
        assertThat(count(KEY, "{\"model\": \"gpt-4o\", \"messages\": []}").getStatus()).isEqualTo(400);
        assertThat(count(KEY, "{\"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}").getStatus())
                .isEqualTo(400);
        assertThat(count(KEY, "[1, 2]").getStatus()).isEqualTo(400);
        String eleven = "\"m1\",\"m2\",\"m3\",\"m4\",\"m5\",\"m6\",\"m7\",\"m8\",\"m9\",\"m10\",\"m11\"";
        MockHttpServletResponse tooMany = count(KEY,
                "{\"models\": [" + eleven + "], \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}");
        assertThat(tooMany.getStatus()).isEqualTo(400);
        assertThat(tooMany.getContentAsString()).contains("at most 10");
    }

    private MockHttpServletResponse count(String key, String body) throws Exception {
        return mockMvc.perform(post("/v1/tokens/count")
                        .header("Authorization", "Bearer " + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse();
    }

    private static void answer(HttpExchange ex, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String h(String plaintextKey) {
        return com.dvarahq.core.apikey.ApiKeyGenerator.hash(plaintextKey);
    }
}
