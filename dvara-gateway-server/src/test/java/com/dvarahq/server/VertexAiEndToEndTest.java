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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * Gemini on Vertex AI through the assembled gateway (gateway.yaml, filters, key, PII, metering), configured as an
 * operator would: {@code type: vertex-ai} with a project, a region and a service account key file. One local stub
 * stands in for both Google's token endpoint, which checks the signed token request with the key's public half, and
 * Vertex AI, which takes only the token it issued. The key is made up for the test.
 */
@SpringBootTest(classes = GatewayServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("mocktest")
class VertexAiEndToEndTest {

    @TempDir
    static Path configDir;

    static final String KEY = "dvara-e2e-vertex-key";
    static final String REDACT_KEY = "dvara-e2e-vertex-redact-key";
    static final String ACCESS_TOKEN = "ya29.e2e-access-token";
    static final String MODELS = "/v1/projects/e2e-project/locations/europe-west4/publishers/google/models/";
    static final ObjectMapper JSON = new ObjectMapper();

    static HttpServer stub;
    static KeyPair serviceAccountKey;
    /** Token requests Google's stub received, and whether each one's signature was the service account's. */
    static final List<Boolean> tokenRequests = new CopyOnWriteArrayList<>();
    /** What Vertex AI's stub last received, by method: the path, the Authorization header and the body. */
    static final Map<String, String> paths = new ConcurrentHashMap<>();
    static final Map<String, String> authorizations = new ConcurrentHashMap<>();
    static final Map<String, String> bodies = new ConcurrentHashMap<>();

    @BeforeAll
    static void startStubAndWriteGatewayYaml() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        serviceAccountKey = generator.generateKeyPair();

        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/token", VertexAiEndToEndTest::token);
        stub.createContext("/v1/projects/", VertexAiEndToEndTest::vertex);
        stub.start();

        Map<String, Object> keyFile = new LinkedHashMap<>();
        keyFile.put("type", "service_account");
        keyFile.put("project_id", "e2e-project");
        keyFile.put("private_key_id", "e2e-key-1");
        keyFile.put("private_key", "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(serviceAccountKey.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        keyFile.put("client_email", "gateway@e2e-project.iam.gserviceaccount.com");
        keyFile.put("token_uri", "https://oauth2.googleapis.com/token");
        Path credentials = Files.writeString(configDir.resolve("vertex-key.json"), JSON.writeValueAsString(keyFile));

        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: mock
                  - type: vertex-ai
                    project: e2e-project
                    region: europe-west4
                    base_url: http://127.0.0.1:%d
                    credentials_file: %s
                workspaces:
                  - id: vertex
                    name: Vertex
                    status: ACTIVE
                  - id: vertex-redactor
                    name: Vertex redactor
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: REDACT
                api_keys:
                  - key_hash: sha256:%s
                    workspace: vertex
                    name: vertex-key
                  - key_hash: sha256:%s
                    workspace: vertex-redactor
                    name: vertex-redact-key
                routes:
                  - id: vertex-route
                    model: "vertex/*"
                    provider: vertex-ai
                  - id: gemini-cli-on-vertex
                    model: "gemini*"
                    provider: vertex-ai
                  - id: mock-route
                    model: "mock*"
                    provider: mock
                """.formatted(stub.getAddress().getPort(), credentials, h(KEY), h(REDACT_KEY)));
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
        registry.add("dvara.llm-gateway.providers.vertex-ai.token-url",
                () -> "http://127.0.0.1:" + stub.getAddress().getPort() + "/token");
        registry.add("dvara.audit.file.path", () -> configDir.resolve("audit.log").toString());
        registry.add("dvara.audit.hmac-secret", () -> "e2e-vertex-audit-secret-for-the-file-chain");
    }

    /** Google's token endpoint: a JWT bearer grant whose assertion the service account signed. */
    private static void token(HttpExchange ex) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        try (InputStream in = ex.getRequestBody()) {
            for (String pair : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("&")) {
                int eq = pair.indexOf('=');
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        boolean signed = "urn:ietf:params:oauth:grant-type:jwt-bearer".equals(form.get("grant_type"))
                && signedByTheServiceAccount(form.get("assertion"));
        tokenRequests.add(signed);
        if (!signed) {
            answer(ex, 400, "application/json", "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}");
            return;
        }
        answer(ex, 200, "application/json",
                "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"expires_in\":3599,\"token_type\":\"Bearer\"}");
    }

    private static boolean signedByTheServiceAccount(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            Signature rs256 = Signature.getInstance("SHA256withRSA");
            rs256.initVerify(serviceAccountKey.getPublic());
            rs256.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return rs256.verify(Base64.getUrlDecoder().decode(parts[2]))
                    && "https://www.googleapis.com/auth/cloud-platform".equals(claims.path("scope").asText());
        } catch (Exception e) {
            return false;
        }
    }

    /** Vertex AI: only the token Google's stub issued is accepted. */
    private static void vertex(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String method = path.substring(path.lastIndexOf(':') + 1);
        try (InputStream in = ex.getRequestBody()) {
            bodies.put(method, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        paths.put(method, path + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery()));
        String authorization = ex.getRequestHeaders().getFirst("Authorization");
        authorizations.put(method, authorization == null ? "" : authorization);
        if (!("Bearer " + ACCESS_TOKEN).equals(authorization)) {
            answer(ex, 401, "application/json",
                    "{\"error\":{\"code\":401,\"message\":\"Request had invalid authentication credentials.\",\"status\":\"UNAUTHENTICATED\"}}");
            return;
        }
        switch (method) {
            case "streamGenerateContent" -> answer(ex, 200, "text/event-stream", """
                    data: {"candidates":[{"content":{"parts":[{"text":"Bonjour"}],"role":"model"}}],"modelVersion":"gemini-2.5-flash"}

                    data: {"candidates":[{"content":{"parts":[{"text":" from Europe."}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":21,"candidatesTokenCount":4,"thoughtsTokenCount":6,"cachedContentTokenCount":10,"totalTokenCount":31},"modelVersion":"gemini-2.5-flash"}

                    """);
            case "predict" -> answer(ex, 200, "application/json", """
                    {"predictions": [{"embeddings": {"values": [0.25, -0.5, 0.75], "statistics": {"token_count": 4, "truncated": false}}}]}
                    """);
            case "countTokens" -> answer(ex, 200, "application/json", "{\"totalTokens\": 9, \"totalBillableCharacters\": 30}");
            default -> answer(ex, 200, "application/json", """
                    {"candidates": [{"content": {"role": "model", "parts": [{"text": "Hello from europe-west4."}]},
                                     "finishReason": "STOP"}],
                     "usageMetadata": {"promptTokenCount": 15, "candidatesTokenCount": 5, "thoughtsTokenCount": 2,
                                       "cachedContentTokenCount": 6, "totalTokenCount": 22},
                     "modelVersion": "gemini-2.5-flash", "responseId": "vertex-whole-1"}
                    """);
        }
    }

    private static void answer(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    @Autowired MockMvc mockMvc;
    @Autowired TokenUsageRepository tokenUsageRepository;

    @BeforeEach
    void clearStub() {
        paths.clear();
        authorizations.clear();
        bodies.clear();
    }

    @Test
    void aChatCompletionReachesTheRegionWithTheMintedToken_andIsMetered() throws Exception {
        int rowsBefore = tokenUsageRepository.findByWorkspaceId("vertex").size();

        MockHttpServletResponse response = send(chat(KEY, "vertex/gemini-2.5-flash", "Say hello.", false));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode answer = JSON.readTree(response.getContentAsString());
        assertThat(answer.at("/choices/0/message/content").asText()).isEqualTo("Hello from europe-west4.");
        assertThat(answer.path("model").asText()).isEqualTo("vertex/gemini-2.5-flash");
        assertThat(paths.get("generateContent")).isEqualTo(MODELS + "gemini-2.5-flash:generateContent");
        assertThat(authorizations.get("generateContent")).isEqualTo("Bearer " + ACCESS_TOKEN);
        assertThat(JSON.readTree(bodies.get("generateContent")).at("/contents/0/parts/0/text").asText()).isEqualTo("Say hello.");

        TokenUsageRecord row = awaitRow("vertex", rowsBefore);
        assertThat(row.getProvider()).isEqualTo("vertex-ai");
        assertThat(row.getInputTokens()).isEqualTo(15);
        assertThat(row.getOutputTokens()).as("the answer and the thinking").isEqualTo(5 + 2);
        assertThat(row.getCachedInputTokens()).isEqualTo(6);
        assertThat(row.isEstimated()).isFalse();
    }

    @Test
    void aStreamedChatCompletionComesFromVertexsSseStream_andIsMetered() throws Exception {
        int rowsBefore = tokenUsageRepository.findByWorkspaceId("vertex").size();

        String events = stream(chat(KEY, "vertex/gemini-2.5-flash", "Greet me.", true));

        assertThat(paths.get("streamGenerateContent")).isEqualTo(MODELS + "gemini-2.5-flash:streamGenerateContent?alt=sse");
        assertThat(events).contains("Bonjour").contains(" from Europe.").contains("[DONE]");
        TokenUsageRecord row = awaitRow("vertex", rowsBefore);
        assertThat(row.getInputTokens()).isEqualTo(21);
        assertThat(row.getOutputTokens()).isEqualTo(4 + 6);
        assertThat(row.getCachedInputTokens()).isEqualTo(10);
    }

    @Test
    void embeddingsGoToVertexsPredict() throws Exception {
        MockHttpServletResponse response = send(post("/v1/embeddings").header("Authorization", "Bearer " + KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\": \"vertex/text-embedding-005\", \"input\": \"embed me\", \"dimensions\": 3}"));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        JsonNode answer = JSON.readTree(response.getContentAsString());
        assertThat(answer.at("/data/0/embedding").toString()).isEqualTo("[0.25,-0.5,0.75]");
        assertThat(answer.at("/usage/prompt_tokens").asInt()).isEqualTo(4);
        assertThat(paths.get("predict")).isEqualTo(MODELS + "text-embedding-005:predict");
        JsonNode sent = JSON.readTree(bodies.get("predict"));
        assertThat(sent.at("/instances/0/content").asText()).isEqualTo("embed me");
        assertThat(sent.at("/parameters/outputDimensionality").asInt()).isEqualTo(3);
    }

    /** Governance applies as on any provider: the address never reaches Google. */
    @Test
    void personalDataIsRedactedBeforeItReachesVertex() throws Exception {
        MockHttpServletResponse response = send(chat(REDACT_KEY, "vertex/gemini-2.5-flash",
                "Write to jane.doe@example.com about the invoice.", false));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        String sent = JSON.readTree(bodies.get("generateContent")).at("/contents/0/parts/0/text").asText();
        assertThat(sent).doesNotContain("jane.doe@example.com").contains("about the invoice.");
    }

    /** Gemini CLI pointed at the gateway, on a route to Vertex AI: the body goes on as the CLI sent it. */
    @Test
    void aGeminiApiClientIsServedByVertex_onARouteThatNamesIt() throws Exception {
        MockHttpServletResponse response = send(post("/v1/gemini/v1beta/models/gemini-2.5-flash:generateContent")
                .header("x-goog-api-key", KEY).contentType(MediaType.APPLICATION_JSON).content("""
                        {"contents": [{"role": "user", "parts": [{"text": "hello"}]}],
                         "generationConfig": {"thinkingConfig": {"includeThoughts": true}, "maxOutputTokens": 100}}
                        """));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(paths.get("generateContent")).isEqualTo(MODELS + "gemini-2.5-flash:generateContent");
        assertThat(JSON.readTree(bodies.get("generateContent")).at("/generationConfig/thinkingConfig/includeThoughts")
                .asBoolean()).isTrue();
        JsonNode answer = JSON.readTree(response.getContentAsString());
        assertThat(answer.path("responseId").asText()).as("Vertex's own response, as it came").isEqualTo("vertex-whole-1");
        assertThat(answer.at("/candidates/0/content/parts/0/text").asText()).isEqualTo("Hello from europe-west4.");
    }

    @Test
    void countTokensIsCountedByVertex() throws Exception {
        MockHttpServletResponse response = send(post("/v1/gemini/v1beta/models/gemini-2.5-flash:countTokens")
                .header("x-goog-api-key", KEY).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contents\": [{\"role\": \"user\", \"parts\": [{\"text\": \"How many tokens is this?\"}]}]}"));

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString()).path("totalTokens").asInt()).isEqualTo(9);
        assertThat(JSON.readTree(bodies.get("countTokens")).has("generateContentRequest")).isFalse();
    }

    /** One token serves every call: Google was asked once, with a request the service account signed. */
    @Test
    void theTokenIsMintedOnce_fromASignedRequest() throws Exception {
        send(chat(KEY, "vertex/gemini-2.5-flash", "one", false));
        send(chat(KEY, "vertex/gemini-2.5-flash", "two", false));

        assertThat(tokenRequests).containsExactly(true);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static MockHttpServletRequestBuilder chat(String key, String model, String text, boolean stream)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", text)));
        if (stream) {
            body.put("stream", true);
        }
        return post("/v1/chat/completions").header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body));
    }

    private MockHttpServletResponse send(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private String stream(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult started = mockMvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();
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

    private static String h(String plaintextKey) {
        return com.dvarahq.core.apikey.ApiKeyGenerator.hash(plaintextKey);
    }
}
