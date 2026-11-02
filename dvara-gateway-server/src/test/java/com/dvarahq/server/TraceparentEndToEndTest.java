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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * A caller that sends a W3C {@code traceparent} and no {@code X-Trace-ID} gets that trace id back on
 * {@code /v1}, and the audit record carries it too. So a model call can be joined with the caller's other
 * calls in the same trace. A caller's own {@code X-Trace-ID} still wins.
 */
@SpringBootTest(classes = GatewayServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("mocktest")
class TraceparentEndToEndTest {

    @TempDir
    static Path configDir;

    static final String KEY = "dvara-e2e-traceparent-key";
    static final String AUDIT_SECRET = "e2e-traceparent-audit-secret-for-the-file-chain";
    static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void writeGatewayYaml() throws IOException {
        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: mock
                workspaces:
                  - id: tp
                    name: Traceparent
                    status: ACTIVE
                api_keys:
                  - key_hash: sha256:%s
                    workspace: tp
                    name: tp-key
                routes:
                  - id: mock-route
                    model: "mock*"
                    provider: mock
                """.formatted(com.dvarahq.core.apikey.ApiKeyGenerator.hash(KEY)));
        System.setProperty("DVARA_CONFIG_FILE", file.toString());
    }

    @AfterAll
    static void clearConfigPath() {
        System.clearProperty("DVARA_CONFIG_FILE");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dvara.audit.file.path", () -> configDir.resolve("audit.log").toString());
        registry.add("dvara.audit.hmac-secret", () -> AUDIT_SECRET);
    }

    @Test
    void aModelCallAnswersWithTheTraceIdOfTheCallersTraceparent_andTheAuditRecordCarriesIt() throws Exception {
        String trace = newTraceId();

        MockHttpServletResponse response = mockMvc.perform(chat(false).header("traceparent", traceparent(trace)))
                .andReturn().getResponse();

        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        assertThat(response.getHeader("X-Trace-ID")).isEqualTo(trace);
        assertThat(auditedResponseTraceIds()).contains(trace);
    }

    @Test
    void aStreamedModelCallAnswersWithItToo() throws Exception {
        String trace = newTraceId();

        MvcResult started = mockMvc.perform(chat(true).header("traceparent", traceparent(trace)))
                .andExpect(request().asyncStarted()).andReturn();
        MockHttpServletResponse response = mockMvc.perform(asyncDispatch(started)).andReturn().getResponse();

        assertThat(response.getContentAsString()).contains("[DONE]");
        assertThat(response.getHeader("X-Trace-ID")).isEqualTo(trace);
        assertThat(auditedResponseTraceIds()).contains(trace);
    }

    @Test
    void aCallersXTraceIdStillWinsOverTheTraceparent() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(chat(false)
                        .header("traceparent", traceparent(newTraceId()))
                        .header("X-Trace-ID", "caller-trace-7"))
                .andReturn().getResponse();

        assertThat(response.getHeader("X-Trace-ID")).isEqualTo("caller-trace-7");
        assertThat(auditedResponseTraceIds()).contains("caller-trace-7");
    }

    /** With no traceparent the request starts a trace of its own, and its id is still a 32-hex trace id. */
    @Test
    void withNoTraceparentTheAnswerStillCarriesATraceId() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(chat(false)).andReturn().getResponse();

        assertThat(response.getHeader("X-Trace-ID")).matches("[0-9a-f]{32}");
        assertThat(auditedResponseTraceIds()).contains(response.getHeader("X-Trace-ID"));
    }

    private static MockHttpServletRequestBuilder chat(boolean stream) {
        return post("/v1/chat/completions")
                .header("Authorization", "Bearer " + KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"model":"mock/gpt","stream":%s,"messages":[{"role":"user","content":"trace me"}]}
                        """.formatted(stream));
    }

    /** The trace ids on every response record in the audit file so far. */
    private static java.util.List<String> auditedResponseTraceIds() throws IOException {
        Path log = configDir.resolve("audit.log");
        if (!Files.exists(log)) {
            return java.util.List.of();
        }
        return Files.readAllLines(log).stream()
                .filter(line -> !line.isBlank())
                .map(TraceparentEndToEndTest::parse)
                .filter(node -> "GATEWAY_RESPONSE".equals(node.path("eventType").asText()))
                .map(node -> Optional.ofNullable(node.path("payload").get("trace_id")).map(JsonNode::asText))
                .flatMap(Optional::stream)
                .toList();
    }

    private static JsonNode parse(String line) {
        try {
            return JSON.readTree(line);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String traceparent(String traceId) {
        byte[] span = new byte[8];
        ThreadLocalRandom.current().nextBytes(span);
        return "00-" + traceId + "-" + HexFormat.of().formatHex(span) + "-01";
    }

    private static String newTraceId() {
        byte[] id = new byte[16];
        ThreadLocalRandom.current().nextBytes(id);
        return HexFormat.of().formatHex(id);
    }
}
