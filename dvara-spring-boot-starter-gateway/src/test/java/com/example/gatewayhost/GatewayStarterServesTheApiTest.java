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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.policy.PolicyContext;
import com.dvarahq.core.policy.PolicyEngine;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.servlet.MockMvc;

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
 * <p>Three checks. A chat request comes back with a completion from the mock provider. The models
 * endpoint answers. And the policy engine denies a model on a denylist, which shows the engines
 * behind the API are real and wired in.
 */
@SpringBootTest(classes = GatewayStarterServesTheApiTest.PlainHostApplication.class,
        properties = {
                "dvara.llm-gateway.providers.mock.enabled=true",
                "dvara.llm-gateway.providers.mock.latency-ms=0",
                "dvara.llm-gateway.providers.mock.response=Hello from the mock provider"
        })
@AutoConfigureMockMvc
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GatewayStarterServesTheApiTest {

    @SpringBootApplication
    static class PlainHostApplication {
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
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"mock/gpt","messages":[{"role":"user","content":"Hello"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("Hello from the mock provider"));
    }

    @Test
    void theModelsEndpointAnswers() throws Exception {
        mockMvc.perform(get("/v1/models")).andExpect(status().isOk());
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
