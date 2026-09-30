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
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.support.HttpRequestWrapper;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * What Claude Code sends to {@code /v1/messages}, through the assembled gateway to a stub of Anthropic's API:
 * extended thinking and its signed blocks, the {@code anthropic-beta} header, request fields the gateway does
 * not model, token counting, and errors in Anthropic's envelope. The mock provider stands in for a provider
 * that is not Anthropic.
 */
@SpringBootTest(classes = GatewayServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("mocktest")
@Import(MessagesFromClaudeCodeEndToEndTest.StubAnthropicConfig.class)
class MessagesFromClaudeCodeEndToEndTest {

    @TempDir
    static Path configDir;

    static final String KEY = "dvara-e2e-claude-code-key";
    static final String BLOCK_KEY = "dvara-e2e-claude-code-block-key";
    static final String REDACT_KEY = "dvara-e2e-claude-code-redact-key";
    static final String LIMITED_MESSAGES_KEY = "dvara-e2e-claude-code-limited-messages-key";
    static final String LIMITED_CHAT_KEY = "dvara-e2e-claude-code-limited-chat-key";

    static final String BETA = "interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14";
    static final String THINKING = "{\"type\": \"enabled\", \"budget_tokens\": 1024}";
    static final ObjectMapper JSON = new ObjectMapper();

    static HttpServer stub;
    /** What the stub last received, by path: the body and the headers. */
    static final Map<String, String> bodies = new ConcurrentHashMap<>();
    static final Map<String, Map<String, String>> headers = new ConcurrentHashMap<>();

    @BeforeAll
    static void startStubAndWriteGatewayYaml() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1/messages/count_tokens", ex -> {
            record(ex);
            answer(ex, "application/json", "{\"input_tokens\": 4242}");
        });
        stub.createContext("/v1/messages", MessagesFromClaudeCodeEndToEndTest::messages);
        stub.start();

        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: mock
                  - type: anthropic
                    api_key: stub-anthropic-key
                workspaces:
                  - id: coder
                    name: Coder
                    status: ACTIVE
                  - id: blocker
                    name: Blocker
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: BLOCK
                  - id: redactor
                    name: Redactor
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: REDACT
                  - id: limited
                    name: Limited
                    status: ACTIVE
                    metadata:
                      rate-limit.requests-per-minute: 1
                api_keys:
                  - key_hash: sha256:%s
                    workspace: coder
                    name: coder-key
                  - key_hash: sha256:%s
                    workspace: blocker
                    name: block-key
                  - key_hash: sha256:%s
                    workspace: redactor
                    name: redact-key
                  - key_hash: sha256:%s
                    workspace: limited
                    name: limited-messages-key
                  - key_hash: sha256:%s
                    workspace: limited
                    name: limited-chat-key
                routes:
                  - id: claude-route
                    model: "claude*"
                    provider: anthropic
                  - id: mock-route
                    model: "mock*"
                    provider: mock
                """.formatted(h(KEY), h(BLOCK_KEY), h(REDACT_KEY), h(LIMITED_MESSAGES_KEY), h(LIMITED_CHAT_KEY)));
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
        registry.add("dvara.audit.hmac-secret", () -> "e2e-claude-code-audit-secret-for-the-file-chain");
        // The limiter is off by default; on here so one workspace's own limit can refuse a call.
        registry.add("dvara.llm-gateway.rate-limit.enabled", () -> "true");
    }

    /** The Anthropic provider, its calls sent to the stub rather than to Anthropic. */
    @TestConfiguration
    static class StubAnthropicConfig {
        @Bean
        AnthropicProvider anthropicProvider(SecretProvider secretProvider) {
            ClientHttpRequestInterceptor toStub = (request, body, execution) -> {
                URI original = request.getURI();
                URI rewritten = URI.create("http://127.0.0.1:" + stub.getAddress().getPort() + original.getRawPath()
                        + (original.getRawQuery() == null ? "" : "?" + original.getRawQuery()));
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
        bodies.clear();
        headers.clear();
    }

    // ---- thinking and anthropic-beta ------------------------------------------------------------

    @Test
    void thinkingAndTheBetaHeaderReachAnthropic_andTheThinkingComesBack() throws Exception {
        MockHttpServletResponse response = send(messages(KEY, """
                {"model": "claude-stub-1", "max_tokens": 2048, "thinking": %s, "context_management": {"edits": []},
                 "messages": [{"role": "user", "content": "What is 2+2?"}]}
                """.formatted(THINKING)));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode sent = JSON.readTree(bodies.get("/v1/messages"));
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(sent.path("thinking").path("budget_tokens").asInt()).isEqualTo(1024);
        assertThat(sent.has("context_management")).as("a field the gateway does not model goes on: " + sent).isTrue();
        assertThat(headers.get("/v1/messages").get("anthropic-beta")).isEqualTo(BETA);

        JsonNode body = JSON.readTree(response.getContentAsString());
        JsonNode content = body.path("content");
        assertThat(content.get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(content.get(0).path("thinking").asText()).isEqualTo("Two and two make four.");
        assertThat(content.get(0).path("signature").asText()).isEqualTo("sig-plain");
        assertThat(content.get(1).path("type").asText()).isEqualTo("redacted_thinking");
        assertThat(content.get(1).path("data").asText()).isEqualTo("opaque-redacted");
        assertThat(content.get(2).path("type").asText()).isEqualTo("text");
        assertThat(content.get(2).path("text").asText()).isEqualTo("4");
    }

    @Test
    void thinkingBlocksInTheHistoryAreSentBackUnchanged() throws Exception {
        MockHttpServletResponse response = send(messages(KEY, """
                {"model": "claude-stub-1", "max_tokens": 2048, "thinking": %s,
                 "messages": [
                   {"role": "user", "content": "What is 2+2?"},
                   {"role": "assistant", "content": [
                     {"type": "thinking", "thinking": "Two and two make four.", "signature": "sig-earlier"},
                     {"type": "redacted_thinking", "data": "opaque-earlier"},
                     {"type": "tool_use", "id": "toolu_1", "name": "calc", "input": {"q": "2+2"}}]},
                   {"role": "user", "content": [{"type": "tool_result", "tool_use_id": "toolu_1", "content": "4"}]}],
                 "tools": [{"name": "calc", "input_schema": {"type": "object"}}]}
                """.formatted(THINKING)));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode assistant = JSON.readTree(bodies.get("/v1/messages")).path("messages").get(1);
        assertThat(assistant.path("role").asText()).isEqualTo("assistant");
        JsonNode blocks = assistant.path("content");
        assertThat(blocks.get(0).path("type").asText()).isEqualTo("thinking");
        assertThat(blocks.get(0).path("thinking").asText()).isEqualTo("Two and two make four.");
        assertThat(blocks.get(0).path("signature").asText()).isEqualTo("sig-earlier");
        assertThat(blocks.get(1).path("type").asText()).isEqualTo("redacted_thinking");
        assertThat(blocks.get(1).path("data").asText()).isEqualTo("opaque-earlier");
        assertThat(blocks.get(2).path("type").asText()).isEqualTo("tool_use");
    }

    @Test
    void aStreamedThinkingReplyComesBackAsThinkingEvents() throws Exception {
        String events = stream(messages(KEY, """
                {"model": "claude-stub-1", "max_tokens": 2048, "stream": true, "thinking": %s,
                 "messages": [{"role": "user", "content": "What is 2+2?"}]}
                """.formatted(THINKING)));

        JsonNode sent = JSON.readTree(bodies.get("/v1/messages"));
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(headers.get("/v1/messages").get("anthropic-beta")).isEqualTo(BETA);

        List<JsonNode> data = data(events);
        JsonNode firstStart = data.stream().filter(e -> "content_block_start".equals(e.path("type").asText()))
                .findFirst().orElseThrow();
        assertThat(firstStart.path("content_block").path("type").asText()).isEqualTo("thinking");
        assertThat(events).contains("\"type\":\"thinking_delta\"").contains("\"thinking\":\"Two and \"")
                .contains("\"thinking\":\"two make four.\"")
                .contains("\"type\":\"signature_delta\"").contains("\"signature\":\"sig-stream\"")
                .contains("\"type\":\"redacted_thinking\"").contains("\"data\":\"opaque-stream\"")
                .contains("\"type\":\"text_delta\"").contains("\"text\":\"4\"")
                .contains("event:message_stop");
    }

    // ---- a provider that is not Anthropic -------------------------------------------------------

    @Test
    void thinkingOnAProviderThatIsNotAnthropicIsRefusedNamingIt() throws Exception {
        MockHttpServletResponse response = send(messages(KEY, """
                {"model": "mock/test", "max_tokens": 256, "thinking": %s,
                 "messages": [{"role": "user", "content": "What is 2+2?"}]}
                """.formatted(THINKING)));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
        JsonNode body = JSON.readTree(response.getContentAsString());
        assertThat(body.path("type").asText()).isEqualTo("error");
        assertThat(body.path("error").path("code").asText()).isEqualTo("unsupported_capability");
        assertThat(body.path("error").path("message").asText()).contains("mock");
        assertThat(bodies).as("nothing reached Anthropic").isEmpty();
    }

    @Test
    void anUnknownFieldAndTheBetaHeaderAreDroppedForAProviderThatIsNotAnthropic() throws Exception {
        MockHttpServletResponse response = send(messages(KEY, """
                {"model": "mock/test", "max_tokens": 256, "context_management": {"edits": []},
                 "messages": [{"role": "user", "content": "hello"}]}
                """));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString()).path("type").asText()).isEqualTo("message");
    }

    // ---- thinking is output, and governed as output ---------------------------------------------

    @Test
    void personalDataInThinkingIsBlockedLikeAnyOutput() throws Exception {
        MockHttpServletResponse response = send(messages(BLOCK_KEY, """
                {"model": "claude-stub-1", "max_tokens": 2048, "thinking": %s,
                 "messages": [{"role": "user", "content": "leak-in-thinking"}]}
                """.formatted(THINKING)));

        assertThat(response.getStatus()).as(response.getContentAsString()).isGreaterThanOrEqualTo(400);
        assertThat(response.getContentAsString()).doesNotContain("123-45-6789");
        assertThat(JSON.readTree(response.getContentAsString()).path("error").path("code").asText())
                .containsIgnoringCase("pii");
    }

    @Test
    void personalDataInStreamedThinkingIsRedactedLikeAnyOutput() throws Exception {
        String events = stream(messages(REDACT_KEY, """
                {"model": "claude-stub-1", "max_tokens": 2048, "stream": true, "thinking": %s,
                 "messages": [{"role": "user", "content": "leak-in-thinking"}]}
                """.formatted(THINKING)));

        assertThat(events).doesNotContain("123-45-6789").contains("[REDACTED_")
                .contains("\"type\":\"thinking_delta\"").contains("\"signature\":\"sig-stream\"");
    }

    // ---- count_tokens ---------------------------------------------------------------------------

    @Test
    void countTokensOnAnAnthropicRouteIsAnsweredByAnthropic_andBooksNothing() throws Exception {
        int rowsBefore = tokenUsageRepository.findByWorkspaceId("coder").size();

        MockHttpServletResponse response = send(anthropic(post("/v1/messages/count_tokens"), KEY, """
                {"model": "claude-stub-1", "thinking": %s,
                 "messages": [{"role": "user", "content": "How many tokens is this?"}]}
                """.formatted(THINKING)));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString()).path("input_tokens").asInt()).isEqualTo(4242);
        JsonNode sent = JSON.readTree(bodies.get("/v1/messages/count_tokens"));
        assertThat(sent.path("model").asText()).isEqualTo("claude-stub-1");
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(sent.has("max_tokens")).isFalse();
        assertThat(headers.get("/v1/messages/count_tokens").get("anthropic-beta")).isEqualTo(BETA);
        assertThat(bodies).as("no model was called").doesNotContainKey("/v1/messages");
        Thread.sleep(300);
        assertThat(tokenUsageRepository.findByWorkspaceId("coder")).hasSize(rowsBefore);
    }

    @Test
    void countTokensOnAnotherProviderIsTheGatewaysEstimate_andBooksNothing() throws Exception {
        int rowsBefore = tokenUsageRepository.findByWorkspaceId("coder").size();

        MockHttpServletResponse response = send(anthropic(post("/v1/messages/count_tokens"), KEY, """
                {"model": "mock/test", "messages": [{"role": "user", "content": "How many tokens is this sentence?"}]}
                """));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString()).path("input_tokens").asInt()).isPositive();
        assertThat(bodies).as("Anthropic is not asked about another provider's model").isEmpty();
        Thread.sleep(300);
        assertThat(tokenUsageRepository.findByWorkspaceId("coder")).hasSize(rowsBefore);
    }

    // ---- errors from the filters, in each doorway's envelope ------------------------------------

    @Test
    void aMissingKeyIsRefusedInAnthropicsEnvelopeOnMessages_andOpenAisOnChat() throws Exception {
        MockHttpServletResponse messages = send(post("/v1/messages")
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\": \"claude-stub-1\", \"max_tokens\": 10, \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"));
        MockHttpServletResponse chat = send(post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\": \"mock/test\", \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"));

        assertThat(messages.getStatus()).isEqualTo(401);
        JsonNode anthropicError = JSON.readTree(messages.getContentAsString());
        assertThat(anthropicError.path("type").asText()).isEqualTo("error");
        assertThat(anthropicError.path("error").path("type").asText()).isEqualTo("authentication_error");
        assertThat(anthropicError.path("error").path("code").asText()).isEqualTo("api_key_required");

        assertThat(chat.getStatus()).isEqualTo(401);
        JsonNode openAiError = JSON.readTree(chat.getContentAsString());
        assertThat(openAiError.has("type")).as("chat keeps the OpenAI envelope").isFalse();
        assertThat(openAiError.path("error").path("code").asText()).isEqualTo("api_key_required");
    }

    @Test
    void aRateLimitIsRefusedInAnthropicsEnvelopeOnMessages_andOpenAisOnChat() throws Exception {
        String messagesBody = "{\"model\": \"mock/test\", \"max_tokens\": 10, \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}";
        String chatBody = "{\"model\": \"mock/test\", \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}";

        send(messages(LIMITED_MESSAGES_KEY, messagesBody));
        MockHttpServletResponse messages = send(messages(LIMITED_MESSAGES_KEY, messagesBody));
        send(chat(LIMITED_CHAT_KEY, chatBody));
        MockHttpServletResponse chat = send(chat(LIMITED_CHAT_KEY, chatBody));

        assertThat(messages.getStatus()).isEqualTo(429);
        JsonNode anthropicError = JSON.readTree(messages.getContentAsString());
        assertThat(anthropicError.path("type").asText()).isEqualTo("error");
        assertThat(anthropicError.path("error").path("type").asText()).isEqualTo("rate_limit_error");
        assertThat(anthropicError.path("error").path("code").asText()).isEqualTo("rate_limit_exceeded");
        assertThat(messages.getHeader("Retry-After")).isNotBlank();

        assertThat(chat.getStatus()).isEqualTo(429);
        JsonNode openAiError = JSON.readTree(chat.getContentAsString());
        assertThat(openAiError.has("type")).isFalse();
        assertThat(openAiError.path("error").path("code").asText()).isEqualTo("rate_limit_exceeded");
    }

    // ---- the audit record -----------------------------------------------------------------------

    @Test
    void theBetaHeaderIsInTheAuditRecord() throws Exception {
        MockHttpServletResponse response = send(messages(KEY, """
                {"model": "claude-stub-1", "max_tokens": 64, "messages": [{"role": "user", "content": "audit me"}]}
                """));
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);

        Path audit = configDir.resolve("audit.log");
        long deadline = System.nanoTime() + 5_000_000_000L;
        String log = "";
        while (System.nanoTime() < deadline) {
            log = Files.exists(audit) ? Files.readString(audit) : "";
            if (log.contains(BETA)) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(log).contains("anthropic_beta").contains(BETA);
    }

    // ---- the stub -------------------------------------------------------------------------------

    private static void messages(HttpExchange ex) throws IOException {
        String body = record(ex);
        boolean leak = body.contains("leak-in-thinking");
        String thought = leak ? "The user's SSN is 123-45-6789." : "Two and two make four.";
        if (JSON.readTree(body).path("stream").asBoolean()) {
            String first = leak ? "The user's SSN is " : "Two and ";
            String second = leak ? "123-45-6789." : "two make four.";
            answer(ex, "text/event-stream", String.join("",
                    event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_stub\",\"usage\":{\"input_tokens\":12,\"output_tokens\":1}}}"),
                    event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\"}}"),
                    event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":" + JSON.writeValueAsString(first) + "}}"),
                    event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":" + JSON.writeValueAsString(second) + "}}"),
                    event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig-stream\"}}"),
                    event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}"),
                    event("content_block_start", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":\"opaque-stream\"}}"),
                    event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}"),
                    event("content_block_start", "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}"),
                    event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"text_delta\",\"text\":\"4\"}}"),
                    event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}"),
                    event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}"),
                    event("message_stop", "{\"type\":\"message_stop\"}")));
            return;
        }
        answer(ex, "application/json", """
                {"id": "msg_stub", "type": "message", "role": "assistant", "model": "claude-stub-1",
                 "content": [
                   {"type": "thinking", "thinking": %s, "signature": "sig-plain"},
                   {"type": "redacted_thinking", "data": "opaque-redacted"},
                   {"type": "text", "text": "4"}],
                 "stop_reason": "end_turn", "stop_sequence": null,
                 "usage": {"input_tokens": 12, "output_tokens": 20}}
                """.formatted(JSON.writeValueAsString(thought)));
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }

    /** Keeps what the gateway sent, by path, and returns the body. */
    private static String record(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String requestBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        bodies.put(path, requestBody);
        Map<String, String> seen = new ConcurrentHashMap<>();
        ex.getRequestHeaders().forEach((name, values) -> seen.put(name.toLowerCase(), String.join(",", values)));
        headers.put(path, seen);
        return requestBody;
    }

    private static void answer(HttpExchange ex, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    // ---- requests -------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder messages(String key, String body) {
        return anthropic(post("/v1/messages"), key, body);
    }

    /** As Claude Code sends it: the key in x-api-key, the version and the beta header. */
    private static MockHttpServletRequestBuilder anthropic(MockHttpServletRequestBuilder builder, String key, String body) {
        return builder
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", BETA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder chat(String key, String body) {
        return post("/v1/chat/completions")
                .header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String stream(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult started = mockMvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
    }

    private static List<JsonNode> data(String events) {
        return events.lines().map(String::strip).filter(l -> l.startsWith("data:"))
                .map(l -> {
                    try {
                        return JSON.readTree(l.substring(5).strip());
                    } catch (IOException e) {
                        throw new AssertionError(e);
                    }
                }).toList();
    }

    /** The file carries the key's hash; the caller sends the key itself. */
    private static String h(String plaintextKey) {
        return com.dvarahq.core.apikey.ApiKeyGenerator.hash(plaintextKey);
    }
}
