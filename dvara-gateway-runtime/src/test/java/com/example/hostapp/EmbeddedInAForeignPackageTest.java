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
package com.example.hostapp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An application in someone else's package serves {@code /v1}.
 *
 * <p>{@link HostApplication} is in {@code com.example.hostapp}, so its component scan covers that
 * package and nothing else. Every DVARA bean arrives through {@code GatewayRuntimeAutoConfiguration},
 * which Spring Boot finds regardless of where the host's own scan points. The test lives in
 * {@code com.example} on purpose: in {@code com.dvarahq.server} it would pass through the scan it is
 * meant to prove unnecessary.
 */
@SpringBootTest(classes = EmbeddedInAForeignPackageTest.HostApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("mocktest")
class EmbeddedInAForeignPackageTest {

    /** Someone else's application. It knows nothing about DVARA except the dependency. */
    @SpringBootApplication
    static class HostApplication {
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void theChatEndpointIsServedFromTheHostApplication() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"mock/gpt","messages":[{"role":"user","content":"Hello"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").exists());
    }

    @Test
    void andSoAreTheOtherRoutes() throws Exception {
        mockMvc.perform(get("/v1/models")).andExpect(status().isOk());
    }
}