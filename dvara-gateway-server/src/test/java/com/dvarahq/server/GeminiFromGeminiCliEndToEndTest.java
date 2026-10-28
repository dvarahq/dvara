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

import com.dvarahq.core.metering.TokenUsageRecord;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.gemini.GeminiProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * What Gemini CLI sends with {@code GOOGLE_GEMINI_BASE_URL} set to the gateway's {@code /v1/gemini}, through the
 * assembled gateway (filters, key, scopes, PII, metering, audit) to a stub of Gemini's API. The request is one
 * captured from Gemini CLI 0.63.0 ({@code gemini-cli/gemini-cli-0.63.0-tool-turn.json}), and every stream is
 * read the way the Google GenAI SDK reads it; the mock provider stands in for a provider that is not Gemini.
 */
@SpringBootTest(classes = GatewayServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("mocktest")
@Import(GeminiFromGeminiCliEndToEndTest.StubGeminiConfig.class)
class GeminiFromGeminiCliEndToEndTest {

    @TempDir
    static Path configDir;

    static final String KEY = "dvara-e2e-gemini-cli-key";
    static final String REDACT_KEY = "dvara-e2e-gemini-cli-redact-key";
    static final String EMBEDDINGS_ONLY_KEY = "dvara-e2e-gemini-cli-embeddings-key";
    static final String AUDIT_SECRET = "e2e-gemini-cli-audit-secret-for-the-file-chain";
    static final ObjectMapper JSON = new ObjectMapper();

    /** Gemini's stream for the turn: a thought with its signature, then the answer, with usage. */
    static final List<String> STREAM_EVENTS = List.of(
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"**Reading the listing**\",\"thought\":true,"
                    + "\"thoughtSignature\":\"c2lnLXN0cmVhbQ==\"},"
                    + "{\"executableCode\":{\"language\":\"PYTHON\",\"code\":\"print(len(files))\"}}],\"role\":\"model\"},\"index\":0}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":1200,\"totalTokenCount\":1200},\"modelVersion\":\"gemini-3.8-flash\","
                    + "\"responseId\":\"stream-1\"}",
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"The project has a README, a package.json and src/. "
                    + "Write to jane.doe@example.com for access.\"}],\"role\":\"model\"},\"finishReason\":\"STOP\",\"index\":0}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":1200,\"candidatesTokenCount\":25,\"thoughtsTokenCount\":40,"
                    + "\"cachedContentTokenCount\":800,\"totalTokenCount\":1265},\"modelVersion\":\"gemini-3.8-flash\","
                    + "\"responseId\":\"stream-1\"}");

    static final String WHOLE = """
            {"candidates": [{"content": {"role": "model", "parts": [
                {"text": "**Reading the listing**", "thought": true, "thoughtSignature": "c2lnLXdob2xl"},
                {"functionCall": {"name": "read_file", "args": {"file_path": "/home/dev/project/README.md"}},
                 "thoughtSignature": "c2lnLWNhbGw="}]},
              "finishReason": "STOP", "index": 0}],
             "usageMetadata": {"promptTokenCount": 1000, "candidatesTokenCount": 20, "thoughtsTokenCount": 30,
                               "cachedContentTokenCount": 600, "totalTokenCount": 1050},
             "modelVersion": "gemini-3.8-flash", "responseId": "whole-1"}
            """;

    static HttpServer stub;
    /** What the stub last received, by method: the body and the query. */
    static final Map<String, String> bodies = new ConcurrentHashMap<>();
    static final Map<String, String> queries = new ConcurrentHashMap<>();
    static final Map<String, String> keyHeaders = new ConcurrentHashMap<>();

    @BeforeAll
    static void startStubAndWriteGatewayYaml() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/v1beta/models/", GeminiFromGeminiCliEndToEndTest::models);
        stub.start();

        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: mock
                  - type: gemini
                    api_key: stub-gemini-key
                workspaces:
                  - id: cli
                    name: CLI
                    status: ACTIVE
                  - id: redactor
                    name: Redactor
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: REDACT
                api_keys:
                  - key_hash: sha256:%s
                    workspace: cli
                    name: cli-key
                  - key_hash: sha256:%s
                    workspace: redactor
                    name: redact-key
                  - key_hash: sha256:%s
                    workspace: cli
                    name: embeddings-key
                    scopes: [embeddings:write]
                routes:
                  - id: gemini-route
                    model: "gemini*"
                    provider: gemini
                  - id: mock-route
                    model: "mock*"
                    provider: mock
                """.formatted(h(KEY), h(REDACT_KEY), h(EMBEDDINGS_ONLY_KEY)));
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

    /** The Gemini provider, its calls sent to the stub rather than to Google. */
    @TestConfiguration
    static class StubGeminiConfig {
        @Bean
        GeminiProvider geminiProvider(SecretProvider secretProvider) {
            return new GeminiProvider(secretProvider, "http://127.0.0.1:" + stub.getAddress().getPort(),
                    RestClient.builder());
        }
    }

    private static void models(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String method = path.substring(path.lastIndexOf(':') + 1);
        try (InputStream in = ex.getRequestBody()) {
            bodies.put(method, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        queries.put(method, ex.getRequestURI().getRawQuery() == null ? "" : ex.getRequestURI().getRawQuery());
        String keyHeader = ex.getRequestHeaders().getFirst("x-goog-api-key");
        keyHeaders.put(method, keyHeader == null ? "" : keyHeader);
        if (path.contains("/gemini-2.5-flash:")) {
            byte[] gone = ("{\"error\":{\"code\":404,\"message\":\"This model models/gemini-2.5-flash is no longer "
                    + "available to new users.\",\"status\":\"NOT_FOUND\"}}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(404, gone.length);
            ex.getResponseBody().write(gone);
            ex.close();
            return;
        }
        switch (method) {
            case "streamGenerateContent" -> {
                StringBuilder sse = new StringBuilder();
                STREAM_EVENTS.forEach(e -> sse.append("data: ").append(e).append("\r\n\r\n"));
                answer(ex, "text/event-stream", sse.toString());
            }
            case "countTokens" -> answer(ex, "application/json", "{\"totalTokens\": 1234}");
            default -> answer(ex, "application/json", WHOLE);
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
    @Autowired TokenUsageRepository tokenUsageRepository;

    @BeforeEach
    void clearStub() {
        bodies.clear();
        queries.clear();
        keyHeaders.clear();
    }

    // ---- on a route to Gemini, the CLI's request and Gemini's answer go through as they were sent ----------

    @Test
    void theCliTurnReachesGeminiAsItWasSent_andItsStreamComesBackAsSent_andIsMetered() throws Exception {
        ObjectNode body = fixtureBody();
        int rowsBefore = tokenUsageRepository.findByWorkspaceId("cli").size();

        String events = stream(cli(body, KEY));

        assertThat(JSON.readTree(bodies.get("streamGenerateContent"))).as("the body Gemini received")
                .isEqualTo(capped(body));
        assertThat(keyHeaders.get("streamGenerateContent")).as("the provider's own key, not the caller's")
                .isEqualTo("stub-gemini-key");
        assertThat(queries.get("streamGenerateContent")).as("no key in the URL, where proxies log it")
                .isEqualTo("alt=sse");
        assertThat(data(events)).as("the events the CLI received").containsExactlyElementsOf(STREAM_EVENTS);

        TokenUsageRecord row = awaitRow("cli", rowsBefore);
        assertThat(row.getInputTokens()).isEqualTo(1200);
        assertThat(row.getOutputTokens()).as("the answer and the thinking").isEqualTo(25 + 40);
        assertThat(row.getCachedInputTokens()).isEqualTo(800);
        assertThat(row.isEstimated()).isFalse();
    }

    @Test
    void aWholeResponseComesBackAsSent_withItsSignaturesAndFunctionCall() throws Exception {
        ObjectNode body = fixtureBody();
        int rowsBefore = tokenUsageRepository.findByWorkspaceId("cli").size();

        MockHttpServletResponse response = send(post("/v1/gemini/v1beta/models/gemini-3.8-flash:generateContent")
                .header("x-goog-api-key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body)));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(bodies.get("generateContent"))).isEqualTo(capped(body));
        assertThat(JSON.readTree(response.getContentAsString())).isEqualTo(JSON.readTree(WHOLE));
        TokenUsageRecord row = awaitRow("cli", rowsBefore);
        assertThat(row.getInputTokens()).isEqualTo(1000);
        assertThat(row.getOutputTokens()).isEqualTo(20 + 30);
        assertThat(row.getCachedInputTokens()).isEqualTo(600);
    }

    /** A key in the URL is not read: a URL ends up in logs. */
    @Test
    void aKeyInTheQueryIsNotAKey() throws Exception {
        MockHttpServletResponse response = send(post("/v1/gemini/v1beta/models/gemini-3.8-flash:generateContent")
                .queryParam("key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(fixtureBody())));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(bodies).as("nothing reached Gemini").isEmpty();
    }

    /**
     * Google refuses before its stream starts: the CLI gets an error status, the one any doorway gives a request
     * the provider refused, and Google's own reason, as JSON, not a 200 stream that ends without a finish.
     */
    @Test
    void aModelGoogleRefusesGivesAnErrorStatusAndGooglesReason_notAStream() throws Exception {
        MockHttpServletResponse response = send(post("/v1/gemini/v1beta/models/gemini-2.5-flash:streamGenerateContent")
                .queryParam("alt", "sse").header("x-goog-api-key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(fixtureBody())));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode error = JSON.readTree(response.getContentAsString()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(400);
        assertThat(error.path("message").asText()).contains("404").contains("no longer available to new users");
    }

    // ---- governance --------------------------------------------------------------------------------------

    /** Redaction rewrites only the governed text; the rest of the CLI's body, signatures included, goes on. */
    @Test
    void personalDataIsRedactedInPlace_bothWays() throws Exception {
        ObjectNode body = fixtureBody();
        ((ObjectNode) body.at("/contents/0/parts/1")).put("text", "Email the list to jane.doe@example.com");

        String events = stream(cli(body, REDACT_KEY));

        JsonNode sent = JSON.readTree(bodies.get("streamGenerateContent"));
        assertThat(sent.at("/contents/0/parts/1/text").asText()).doesNotContain("jane.doe@example.com");
        assertThat(sent.at("/contents/1/parts/0/thoughtSignature").asText()).isEqualTo("skip_thought_signature_validator");
        assertThat(sent.at("/contents/1/parts/0/functionCall")).isEqualTo(body.at("/contents/1/parts/0/functionCall"));
        assertThat(sent.at("/contents/2/parts/0/functionResponse/id").asText()).isEqualTo("call_567176");
        assertThat(sent.at("/generationConfig")).isEqualTo(capped(body).at("/generationConfig"));
        // The answer is held, governed, and delivered in Gemini's shape without the address; the code part the
        // gateway does not read is still delivered.
        assertThat(events).doesNotContain("jane.doe@example.com").contains("The project has a README")
                .contains("print(len(files))");
        JsonNode last = data(events).stream().map(GeminiFromGeminiCliEndToEndTest::read).toList().getLast();
        assertThat(last.at("/candidates/0/finishReason").asText()).isEqualTo("STOP");
    }

    @Test
    void aKeyWithoutTheCompletionsScopeIsRefused_inGooglesShape() throws Exception {
        MockHttpServletResponse response = send(cli(fixtureBody(), EMBEDDINGS_ONLY_KEY));

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode error = JSON.readTree(response.getContentAsString()).path("error");
        assertThat(error.path("code").asInt()).isEqualTo(403);
        assertThat(error.path("status").asText()).isEqualTo("PERMISSION_DENIED");
        assertThat(bodies).as("nothing reached Gemini").isEmpty();
    }

    @Test
    void noKeyIsRefused_inGooglesShape() throws Exception {
        MockHttpServletResponse response = send(post("/v1/gemini/v1beta/models/gemini-3.8-flash:generateContent")
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(fixtureBody())));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(JSON.readTree(response.getContentAsString()).at("/error/status").asText()).isEqualTo("UNAUTHENTICATED");
    }

    // ---- a provider that is not Gemini --------------------------------------------------------------------

    @Test
    void aProviderThatIsNotGeminiAnswersInGeminisShape() throws Exception {
        MockHttpServletResponse response = send(post("/v1/gemini/v1beta/models/mock/test:generateContent")
                .header("x-goog-api-key", KEY).contentType(MediaType.APPLICATION_JSON).content("""
                        {"contents": [{"role": "user", "parts": [{"text": "hello"}]}],
                         "systemInstruction": {"parts": [{"text": "Be brief."}]},
                         "generationConfig": {"thinkingConfig": {"includeThoughts": true}}}
                        """));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode answer = JSON.readTree(response.getContentAsString());
        assertThat(answer.at("/candidates/0/content/role").asText()).isEqualTo("model");
        assertThat(answer.at("/candidates/0/content/parts/0/text").asText()).isEqualTo("Mock integration test response");
        assertThat(answer.at("/candidates/0/finishReason").asText()).isEqualTo("STOP");
        assertThat(bodies).as("nothing reached Gemini").isEmpty();
    }

    /**
     * Gemini CLI's web search asks Google to search. Refused on a Gemini route too, before the stream opens, as
     * a JSON error in Google's shape that names the setting turning the CLI's search and fetch tools off.
     */
    @Test
    void googleSearchIsRefusedEvenOnAGeminiRoute_asJsonBeforeTheStream() throws Exception {
        ObjectNode body = fixtureBody();
        body.withArray("tools").addObject().putObject("googleSearch");

        MockHttpServletResponse response = send(cli(body, KEY).accept(MediaType.TEXT_EVENT_STREAM));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith("application/json");
        JsonNode error = JSON.readTree(response.getContentAsString()).path("error");
        assertThat(error.path("status").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(error.path("message").asText()).contains("googleSearch").contains("google_web_search");
        assertThat(bodies).as("nothing reached Gemini").isEmpty();
    }

    // ---- counting, and the audit chain --------------------------------------------------------------------

    @Test
    void countTokensIsCountedByGemini() throws Exception {
        MockHttpServletResponse response = send(post("/v1/gemini/v1beta/models/gemini-3.8-flash:countTokens")
                .header("x-goog-api-key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contents\": [{\"role\": \"user\", \"parts\": [{\"text\": \"How many tokens is this?\"}]}]}"));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString()).path("totalTokens").asInt()).isEqualTo(1234);
        assertThat(JSON.readTree(bodies.get("countTokens")).at("/generateContentRequest/model").asText())
                .isEqualTo("models/gemini-3.8-flash");
    }

    @Test
    void theAuditChainVerifiesOverGeminiAndChatRecords() throws Exception {
        stream(cli(fixtureBody(), KEY));
        send(post("/v1/chat/completions").header("Authorization", "Bearer " + KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\": \"mock/test\", \"messages\": [{\"role\": \"user\", \"content\": \"hi\"}]}"));

        Path log = configDir.resolve("audit.log");
        String text = Files.readString(log);
        assertThat(text).contains("gemini-3.8-flash").contains("mock/test");
        var result = com.dvarahq.autoconfigure.audit.FileAuditChainVerifier.verify(log, AUDIT_SECRET);
        assertThat(result.valid()).as(result::describe).isTrue();
        assertThat(result.linesChecked()).isGreaterThan(1);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static JsonNode fixture() throws IOException {
        try (var in = GeminiFromGeminiCliEndToEndTest.class.getResourceAsStream("/gemini-cli/gemini-cli-0.63.0-tool-turn.json")) {
            return JSON.readTree(in);
        }
    }

    /**
     * The body as the gateway sends it on: as it came, with the one governed edit a request with no output
     * limit gets, the gateway's default response cap.
     */
    private static ObjectNode capped(ObjectNode body) {
        ObjectNode out = body.deepCopy();
        ((ObjectNode) out.path("generationConfig")).put("maxOutputTokens", 4096);
        return out;
    }

    private static ObjectNode fixtureBody() throws IOException {
        return (ObjectNode) fixture().path("body").deepCopy();
    }

    /** As the CLI sends it: its path, its headers, the key in x-goog-api-key, the body given. */
    private static MockHttpServletRequestBuilder cli(ObjectNode body, String key) throws IOException {
        JsonNode captured = fixture();
        String path = captured.path("path").asText();
        String query = path.substring(path.indexOf('?') + 1);
        MockHttpServletRequestBuilder builder = post(path.substring(0, path.indexOf('?')))
                .queryParam(query.substring(0, query.indexOf('=')), query.substring(query.indexOf('=') + 1))
                .header("x-goog-api-key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
        captured.path("headers").fields().forEachRemaining(h -> {
            if (!"content-type".equalsIgnoreCase(h.getKey())) {
                builder.header(h.getKey(), h.getValue().asText());
            }
        });
        return builder;
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String stream(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult started = mockMvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
    }

    /**
     * Each event's data, read the way the Google GenAI SDK 1.30.0 reads a stream: {@code /^\s*data: (.*)(?:\n\n|
     * \r\r|\r\n\r\n)/} at the start of the buffer, and nothing but blanks left over.
     */
    private static List<String> data(String events) {
        java.util.regex.Pattern line = java.util.regex.Pattern.compile("^\\s*data: (.*)(?:\n\n|\r\r|\r\n\r\n)");
        List<String> out = new java.util.ArrayList<>();
        String buffer = events;
        java.util.regex.Matcher m = line.matcher(buffer);
        while (m.find()) {
            out.add(m.group(1));
            buffer = buffer.substring(m.end());
            m = line.matcher(buffer);
        }
        assertThat(buffer.trim()).as("what the SDK could not read: Incomplete JSON segment").isEmpty();
        return out;
    }

    private static JsonNode read(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The usage row written after {@code before} rows, waited for: rows are written after the response. */
    private TokenUsageRecord awaitRow(String workspace, int before) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            var rows = tokenUsageRepository.findByWorkspaceId(workspace);
            if (rows.size() > before) {
                return rows.stream().max(Comparator.comparing(TokenUsageRecord::getTimestamp)).orElseThrow();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no usage row was written for " + workspace);
    }

    /** The file carries the key's hash; the caller sends the key itself. */
    private static String h(String plaintextKey) {
        return com.dvarahq.core.apikey.ApiKeyGenerator.hash(plaintextKey);
    }
}
