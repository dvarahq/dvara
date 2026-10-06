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

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A model whose own context window is larger than the one its provider declares is served up to that
 * window. The mock provider declares 128,000 tokens for every model; {@code model_limits} in the file
 * gives one of its models a million. The same request to a model with no entry is still refused, so
 * the provider's window keeps applying where nothing more is known.
 */
@SpringBootTest(classes = GatewayServerApplication.class)
@AutoConfigureMockMvc
class ModelContextLimitEndToEndTest {

    @TempDir
    static Path configDir;

    static final String KEY = "dvara-model-limit-key";

    /**
     * About 152,000 tokens: over the provider's 128,000, well inside the model's million. Spread over
     * nineteen messages of 8,000 tokens, because the guardrail's own size limits (100 messages, 50,000
     * characters each) apply before the context check and are not what this test is about.
     */
    static final String LONG_MESSAGES = java.util.stream.IntStream.range(0, 19)
            .mapToObj(i -> "{\"role\":\"user\",\"content\":\"" + "hello ".repeat(8_000) + "\"}")
            .collect(java.util.stream.Collectors.joining(","));

    @BeforeAll
    static void writeGatewayYaml() throws IOException {
        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: mock
                api_keys:
                  - key_hash: sha256:%s
                    workspace: default
                    name: limit-key
                routes:
                  - id: mock-route
                    model: "mock*"
                    provider: mock
                model_limits:
                  - model: mock/long-context
                    provider: mock
                    context_tokens: 1000000
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
        registry.add("dvara.audit.hmac-secret", () -> "model-limit-test-audit-secret-for-the-file-chain");
        registry.add("dvara.llm-gateway.providers.mock.latency-ms", () -> "0");
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void aRequestOverTheProviderWindowButInsideTheModelWindowIsServed() throws Exception {
        mockMvc.perform(chat("mock/long-context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").isNotEmpty());
    }

    @Test
    void theSameRequestToAModelWithNoEntryIsRefusedAtTheProviderWindow() throws Exception {
        mockMvc.perform(chat("mock/other"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("context_window_exceeded"))
                .andExpect(jsonPath("$.error.message").value(
                        org.hamcrest.Matchers.containsString("max 128000")));
    }

    private static MockHttpServletRequestBuilder chat(String model) {
        return post("/v1/chat/completions")
                .header("Authorization", "Bearer " + KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"model":"%s","messages":[%s]}
                        """.formatted(model, LONG_MESSAGES));
    }
}
