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
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A streamed tool call, end to end through the assembled application: request mapping, the
 * dispatcher and its capability filter, the streaming guard in both delivery modes, the wire chunk,
 * and the usage row. The provider is a test-only bean that streams one call whose arguments arrive
 * in two slices; the slice tests prove each layer alone, this proves they meet.
 */
@SpringBootTest(classes = GatewayServerApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("mocktest")
@Import(StreamedToolCallsEndToEndTest.ToolStreamingProviderConfig.class)
class StreamedToolCallsEndToEndTest {

    @TempDir
    static Path configDir;

    static final String LOG_KEY = "dvara-e2e-tool-log-key";
    static final String REDACT_KEY = "dvara-e2e-tool-redact-key";

    @BeforeAll
    static void writeGatewayYaml() throws IOException {
        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: mock
                workspaces:
                  - id: logger
                    name: Logger
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: LOG
                  - id: redactor
                    name: Redactor
                    status: ACTIVE
                    metadata:
                      pii.enabled: true
                      pii.action: REDACT
                api_keys:
                  - key_hash: sha256:%s
                    workspace: logger
                    name: log-key
                  - key_hash: sha256:%s
                    workspace: redactor
                    name: redact-key
                routes:
                  - id: tooltest-route
                    model: "tooltest*"
                    provider: tooltest
                  - id: mock-route
                    model: "mock*"
                    provider: mock
                """.formatted(h(LOG_KEY), h(REDACT_KEY)));
        System.setProperty("DVARA_CONFIG_FILE", file.toString());
    }

    @AfterAll
    static void clearConfigPath() {
        System.clearProperty("DVARA_CONFIG_FILE");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("dvara.llm-gateway.data-plane.require-api-key", () -> "true");
        registry.add("dvara.audit.file.path", () -> configDir.resolve("audit.log").toString());
        registry.add("dvara.audit.hmac-secret", () -> "e2e-tool-calls-audit-secret");
    }

    /** Streams what an upstream decoder produces for one call whose arguments arrive in two slices. */
    @TestConfiguration
    static class ToolStreamingProviderConfig {
        @Bean
        LlmProvider toolStreamingProvider() {
            return new LlmProvider() {
                @Override public String name() { return "tooltest"; }
                @Override public boolean supports(ChatRequest request) {
                    return request.getModel() != null && request.getModel().startsWith("tooltest/");
                }
                @Override public ChatResponse chat(ChatRequest request) {
                    throw new UnsupportedOperationException("streaming only");
                }
                @Override public ProviderCapabilities capabilities() {
                    return new ProviderCapabilities(true, false, true, false, false, false, true, 32_000);
                }
                @Override public Iterator<SseChunk> streamChat(ChatRequest request) {
                    return List.of(
                            SseChunk.builder().id("e2e").model(request.getModel()).delta("Let me check.").done(false).build(),
                            SseChunk.builder().id("e2e").model(request.getModel())
                                    .toolCalls(List.of(ToolCallDelta.open(0, "call_e2e", "send_email", null))).done(false).build(),
                            SseChunk.builder().id("e2e").model(request.getModel())
                                    .toolCalls(List.of(ToolCallDelta.arguments(0, "{\"to\":\"alice@"))).done(false).build(),
                            SseChunk.builder().id("e2e").model(request.getModel())
                                    .toolCalls(List.of(ToolCallDelta.arguments(0, "example.com\",\"subject\":\"Report\"}"))).done(false).build(),
                            SseChunk.builder().id("e2e").model(request.getModel()).finishReason("tool_calls").done(true).build()
                    ).iterator();
                }
            };
        }
    }

    @Autowired
    MockMvc mockMvc;
    @Autowired
    TokenUsageRepository tokenUsageRepository;

    static final String REQUEST = """
            {"model": "tooltest/agent", "stream": true,
             "messages": [{"role": "user", "content": "email the report to alice"}],
             "tools": [{"type": "function", "function": {"name": "send_email", "parameters": {"type": "object"}}}]}
            """;

    private String stream(String apiKey) throws Exception {
        MvcResult started = mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void underLog_theCallIsRelayedFragmentByFragment_andMetered() throws Exception {
        String body = stream(LOG_KEY);

        assertThat(body).contains("\"content\":\"Let me check.\"");
        assertThat(body).contains("\"tool_calls\":[{\"index\":0,\"id\":\"call_e2e\",\"type\":\"function\",\"function\":{\"name\":\"send_email\",\"arguments\":\"\"}}]");
        assertThat(body).contains("\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"to\\\":\\\"alice@\"}}]");
        assertThat(body).contains("\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"example.com\\\",\\\"subject\\\":\\\"Report\\\"}\"}}]");
        assertThat(body).contains("\"finish_reason\":\"tool_calls\"").contains("[DONE]");
        // Immediate means as they arrive: five chunks in the provider's order, then [DONE].
        List<String> records = dataRecords(body);
        assertThat(records).hasSize(6);
        assertThat(records.get(0)).contains("\"content\":\"Let me check.\"").doesNotContain("tool_calls");
        assertThat(records.get(1)).contains("\"id\":\"call_e2e\"");
        assertThat(records.get(2)).contains("\"arguments\":\"{\\\"to\\\":\\\"alice@\"");
        assertThat(records.get(3)).contains("\"arguments\":\"example.com\\\",");
        assertThat(records.get(4)).contains("\"finish_reason\":\"tool_calls\"");
        assertThat(records.get(5)).isEqualTo("[DONE]");

        TokenUsageRecord row = awaitRow("logger");
        assertThat(row.isEstimated()).as("no usage block from the provider: estimated").isTrue();
        assertThat(row.getOutputTokens()).as("the call's name and arguments are output").isPositive();
    }

    @Test
    void underRedact_theGuardHoldsTheCallAndDeliversItWholeWithTheValueRemoved() throws Exception {
        String body = stream(REDACT_KEY);

        assertThat(body).doesNotContain("alice@").doesNotContain("example.com");
        // Deferred means nothing before the decision: the whole response is ONE chunk — the text,
        // the complete transformed call and the finish reason together — followed only by [DONE].
        List<String> records = dataRecords(body);
        assertThat(records).as("one chunk, then [DONE]: " + records).hasSize(2);
        assertThat(records.get(1)).isEqualTo("[DONE]");
        String terminal = records.get(0);
        assertThat(terminal).contains("\"content\":\"Let me check.\"");
        assertThat(terminal).contains("\"tool_calls\":[{\"index\":0,\"id\":\"call_e2e\",\"type\":\"function\",\"function\":{\"name\":\"send_email\",\"arguments\":\"{\\\"to\\\":\\\"[REDACTED_EMAIL]\\\",\\\"subject\\\":\\\"Report\\\"}\"}}]");
        assertThat(terminal).contains("\"finish_reason\":\"tool_calls\"");
        assertThat(awaitRow("redactor").getOutputTokens()).isPositive();
    }

    @Test
    void aProviderThatCannotStreamToolCallsIsNotSelected() throws Exception {
        // The mock provider declares no tool-call support, so with tools on a stream the request is
        // refused rather than served with the call dropped. The stream is opened on the emitter's
        // thread, so the refusal arrives on the async dispatch before anything is written, as the
        // same 400 a non-streaming call gets. The code is no_provider rather than no_capable_provider:
        // the capability filter leaves the test provider standing, and it is the route that then
        // finds nobody for mock/*.
        MvcResult started = mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + LOG_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST.replace("tooltest/agent", "mock/gpt")))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("no_provider"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("chat.completion.chunk");
    }

    /** The payload of every {@code data:} record, in order — what a client actually receives. */
    private static List<String> dataRecords(String body) {
        return body.lines().map(String::strip).filter(l -> l.startsWith("data:"))
                .map(l -> l.substring(5).strip()).toList();
    }

    private TokenUsageRecord awaitRow(String workspaceId) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            List<TokenUsageRecord> rows = tokenUsageRepository.findByWorkspaceId(workspaceId);
            if (!rows.isEmpty()) {
                return rows.getLast();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no usage row for " + workspaceId);
    }

    /** The file carries the key's hash; the caller sends the key itself. */
    private static String h(String plaintextKey) {
        return com.dvarahq.core.apikey.ApiKeyGenerator.hash(plaintextKey);
    }
}
