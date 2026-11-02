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

import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.deepseek.DeepSeekProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * OpenAI's {@code prompt_cache_key} sent on {@code /v1/chat/completions}, through the assembled gateway (key,
 * filters, PII, routing) to stubs of OpenAI's and DeepSeek's APIs: OpenAI receives the key on a whole answer, a
 * stream and a call the gateway sends to the Responses API; DeepSeek, which has no such field, does not.
 */
@SpringBootTest(classes = GatewayServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("mocktest")
@Import(ChatPromptCacheKeyEndToEndTest.StubProvidersConfig.class)
class ChatPromptCacheKeyEndToEndTest {

    @TempDir
    static Path configDir;

    static final String KEY = "dvara-e2e-prompt-cache-key";
    static final String REDACT_KEY = "dvara-e2e-prompt-cache-redact-key";
    static final String AUDIT_SECRET = "e2e-prompt-cache-audit-secret-for-the-file-chain";
    static final ObjectMapper JSON = new ObjectMapper();

    static final String CHAT_ANSWER = """
            {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"%s",
             "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":2000,"completion_tokens":2,"total_tokens":2002,
                      "prompt_tokens_details":{"cached_tokens":1920}}}
            """;

    static final String CHAT_STREAM = """
            data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1,"model":"gpt-5.4-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"ok"},"finish_reason":null}]}

            data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1,"model":"gpt-5.4-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":2000,"completion_tokens":1,"total_tokens":2001}}

            data: [DONE]

            """;

    static final String RESPONSES_ANSWER = """
            {"id":"resp_1","object":"response","created_at":1,"status":"completed","model":"gpt-5.4-mini",
             "output":[{"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup",
                        "arguments":"{\\"q\\":\\"x\\"}","status":"completed"}],
             "usage":{"input_tokens":3000,"input_tokens_details":{"cached_tokens":2800},
                      "output_tokens":10,"output_tokens_details":{"reasoning_tokens":4},"total_tokens":3010}}
            """;

    static HttpServer stub;
    /** What the stub last received, by path. */
    static final Map<String, String> bodies = new ConcurrentHashMap<>();

    @BeforeAll
    static void startStubAndWriteGatewayYaml() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", ChatPromptCacheKeyEndToEndTest::handle);
        stub.start();

        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: mock
                  - type: openai
                    api_key: stub-openai-key
                  - type: deepseek
                    api_key: stub-deepseek-key
                workspaces:
                  - id: pck
                    name: Prompt cache
                    status: ACTIVE
                  - id: pck-redactor
                    name: Prompt cache redactor
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: REDACT
                api_keys:
                  - key_hash: sha256:%s
                    workspace: pck
                    name: pck-key
                  - key_hash: sha256:%s
                    workspace: pck-redactor
                    name: pck-redact-key
                routes:
                  - id: openai-route
                    model: "gpt*"
                    provider: openai
                  - id: deepseek-route
                    model: "deepseek*"
                    provider: deepseek
                  - id: mock-route
                    model: "mock*"
                    provider: mock
                """.formatted(h(KEY), h(REDACT_KEY)));
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
        registry.add("dvara.audit.hmac-secret", () -> AUDIT_SECRET);
    }

    /** The OpenAI and DeepSeek providers, their calls sent to the stub. */
    @TestConfiguration
    static class StubProvidersConfig {
        @Bean
        OpenAiProvider openAiProvider(SecretProvider secretProvider) {
            return new OpenAiProvider(secretProvider, "http://127.0.0.1:" + stub.getAddress().getPort() + "/openai",
                    RestClient.builder());
        }

        @Bean
        DeepSeekProvider deepSeekProvider(SecretProvider secretProvider) {
            return new DeepSeekProvider(secretProvider, "http://127.0.0.1:" + stub.getAddress().getPort() + "/deepseek",
                    RestClient.builder());
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String body;
        try (InputStream in = ex.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        bodies.put(path, body);
        JsonNode sent = JSON.readTree(body);
        if (path.endsWith("/responses")) {
            answer(ex, "application/json", RESPONSES_ANSWER);
        } else if (sent.path("stream").asBoolean(false)) {
            answer(ex, "text/event-stream", CHAT_STREAM);
        } else {
            answer(ex, "application/json", CHAT_ANSWER.formatted(sent.path("model").asText()));
        }
    }

    private static void answer(HttpExchange ex, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Autowired MockMvc mockMvc;

    @BeforeEach
    void clearStub() {
        bodies.clear();
    }

    @Test
    void openAiReceivesTheKey_onAWholeAnswer() throws Exception {
        MockHttpServletResponse response = send(KEY, """
                {"model": "gpt-5.4-mini", "prompt_cache_key": "thread-42",
                 "messages": [{"role": "user", "content": "%s"}]}
                """.formatted(unique()));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode sent = sent("/openai/chat/completions");
        assertThat(sent.path("prompt_cache_key").asText()).isEqualTo("thread-42");
        assertThat(JSON.readTree(response.getContentAsString()).at("/choices/0/message/content").asText())
                .as("the key changes no answer").isEqualTo("ok");
    }

    @Test
    void openAiReceivesTheKey_onAStream() throws Exception {
        MvcResult started = mockMvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + KEY)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"model": "gpt-5.4-mini", "stream": true, "prompt_cache_key": "thread-42",
                                 "messages": [{"role": "user", "content": "%s"}]}
                                """.formatted(unique())))
                .andExpect(request().asyncStarted()).andReturn();
        String events = mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();

        assertThat(events).contains("\"ok\"").contains("[DONE]");
        assertThat(sent("/openai/chat/completions").path("prompt_cache_key").asText()).isEqualTo("thread-42");
    }

    /** Tools with a reasoning effort go to OpenAI's Responses API; the key goes with them. */
    @Test
    void openAiReceivesTheKey_whenTheCallGoesToTheResponsesApi() throws Exception {
        MockHttpServletResponse response = send(KEY, """
                {"model": "gpt-5.4-mini", "reasoning_effort": "low", "prompt_cache_key": "thread-42",
                 "tools": [{"type": "function", "function": {"name": "lookup",
                            "parameters": {"type": "object", "properties": {"q": {"type": "string"}}}}}],
                 "messages": [{"role": "user", "content": "%s"}]}
                """.formatted(unique()));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(bodies).as("nothing went to chat completions").doesNotContainKey("/openai/chat/completions");
        assertThat(sent("/openai/responses").path("prompt_cache_key").asText()).isEqualTo("thread-42");
    }

    /** Redaction rewrites the prompt; the key is not prompt text and goes on unchanged. */
    @Test
    void theKeySurvivesRedaction() throws Exception {
        MockHttpServletResponse response = send(REDACT_KEY, """
                {"model": "gpt-5.4-mini", "prompt_cache_key": "thread-42",
                 "messages": [{"role": "user", "content": "Email jane.doe@example.com about %s"}]}
                """.formatted(unique()));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode sent = sent("/openai/chat/completions");
        assertThat(sent.at("/messages/0/content").toString()).doesNotContain("jane.doe@example.com");
        assertThat(sent.path("prompt_cache_key").asText()).isEqualTo("thread-42");
    }

    @Test
    void aProviderWithoutTheField_isNotSentIt() throws Exception {
        MockHttpServletResponse response = send(KEY, """
                {"model": "deepseek-chat", "prompt_cache_key": "thread-42",
                 "messages": [{"role": "user", "content": "%s"}]}
                """.formatted(unique()));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(sent("/deepseek/chat/completions").has("prompt_cache_key")).isFalse();
    }

    @Test
    void aBlankKey_isNotSent() throws Exception {
        MockHttpServletResponse response = send(KEY, """
                {"model": "gpt-5.4-mini", "prompt_cache_key": "",
                 "messages": [{"role": "user", "content": "%s"}]}
                """.formatted(unique()));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(sent("/openai/chat/completions").has("prompt_cache_key")).isFalse();
    }

    @Test
    void noKey_noField() throws Exception {
        MockHttpServletResponse response = send(KEY, """
                {"model": "gpt-5.4-mini", "messages": [{"role": "user", "content": "%s"}]}
                """.formatted(unique()));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(sent("/openai/chat/completions").has("prompt_cache_key")).isFalse();
    }

    private MockHttpServletResponse send(String key, String body) throws Exception {
        return mockMvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private static JsonNode sent(String path) throws IOException {
        assertThat(bodies).as("the stub received a call on " + path).containsKey(path);
        return JSON.readTree(bodies.get(path));
    }

    /** Each prompt is new, so no answer comes from a response cache instead of the stub. */
    private static String unique() {
        return "hello " + UUID.randomUUID();
    }

    /** The file carries the key's hash; the caller sends the key itself. */
    private static String h(String plaintextKey) {
        return com.dvarahq.core.apikey.ApiKeyGenerator.hash(plaintextKey);
    }
}
