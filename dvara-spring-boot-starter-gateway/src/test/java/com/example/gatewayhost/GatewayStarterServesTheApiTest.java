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
package com.example.gatewayhost;

import com.dvarahq.core.apikey.ApiKeyGenerator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.policy.PolicyContext;
import com.dvarahq.core.policy.PolicyEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Add the gateway starter to a plain Spring Boot application and it serves the API.
 *
 * <p>The host application lives in {@code com.example.gatewayhost}, outside {@code com.dvarahq},
 * so its component scan finds nothing of DVARA's. Every bean and every {@code /v1} route has to
 * arrive through auto-configuration, which is what a real host relies on. A host in
 * {@code com.dvarahq} would pass even with the auto-configuration broken.
 *
 * <p>Every request under {@code /v1} carries an API key, in a host application as in the standalone
 * gateway, so the host does what an operator does: a {@code gateway.yaml} names the key's hash, and
 * the caller sends the key. A request without one is refused, which the last check shows.
 *
 * <p>Four checks. A chat request comes back with a completion from the mock provider. The models
 * endpoint answers. The policy engine denies a model on a denylist, which shows the engines behind
 * the API are real and wired in. And a request with no key is refused.
 */
@SpringBootTest(classes = GatewayStarterServesTheApiTest.PlainHostApplication.class,
        properties = {
                "dvara.llm-gateway.providers.mock.latency-ms=0",
                "dvara.llm-gateway.providers.mock.response=Hello from the mock provider"
        })
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GatewayStarterServesTheApiTest {

    @SpringBootApplication
    static class PlainHostApplication {
    }

    static final String KEY = "dvara-gateway-starter-host-key";

    @TempDir
    static Path configDir;

    /**
     * The file is pointed at with a system property rather than a {@code @DynamicPropertySource}:
     * the environment post-processor that reads it runs before the context exists, and a dynamic
     * property source is registered too late for it to see.
     */
    @BeforeAll
    static void writeGatewayYaml() throws IOException {
        Path file = configDir.resolve("gateway.yaml");
        Files.writeString(file, """
                providers:
                  - type: mock

                routes:
                  - id: mock-route
                    model: "mock*"
                    provider: mock

                api_keys:
                  - key_hash: sha256:%s
                    name: host-key
                    workspace: default
                """.formatted(ApiKeyGenerator.hash(KEY)));
        System.setProperty("DVARA_CONFIG_FILE", file.toString());
    }

    @AfterAll
    static void forgetTheFile() {
        // Surefire reuses the JVM across classes; a stray DVARA_CONFIG_FILE would point every later
        // test at a temp file that no longer exists.
        System.clearProperty("DVARA_CONFIG_FILE");
    }

    private final MockMvc mockMvc;
    private final PolicyEngine policyEngine;

    GatewayStarterServesTheApiTest(MockMvc mockMvc, PolicyEngine policyEngine) {
        this.mockMvc = mockMvc;
        this.policyEngine = policyEngine;
    }

    @Test
    void theChatEndpointIsServedFromTheHostApplication() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .header("Authorization", "Bearer " + KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"mock/gpt","messages":[{"role":"user","content":"Hello"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("Hello from the mock provider"));
    }

    @Test
    void theModelsEndpointAnswers() throws Exception {
        mockMvc.perform(get("/v1/models").header("Authorization", "Bearer " + KEY))
                .andExpect(status().isOk());
    }

    @Test
    void aRequestWithNoKeyIsRefused_inAHostApplicationToo() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"mock/gpt","messages":[{"role":"user","content":"Hello"}]}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("api_key_required"));
    }

    @Test
    void theEnginesUnderneathAreTheRealOnes() {
        String dsl = """
                version: "1"
                rules:
                  - id: deny-old-models
                    conditions:
                      model:
                        denylist: [gpt-3.5-turbo]
                    action: DENY
                    deny_message: not approved
                """;
        var denied = policyEngine.evaluateDsl(dsl, PolicyContext.empty(),
                ChatRequest.builder().model("gpt-3.5-turbo").build());
        assertThat(denied.allowed()).as("a denylisted model must be denied").isFalse();
    }
}
