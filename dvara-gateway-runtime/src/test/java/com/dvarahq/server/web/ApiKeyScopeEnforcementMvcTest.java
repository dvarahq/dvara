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
package com.dvarahq.server.web;

import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyGenerator;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.core.apikey.ApiKeyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Scope enforcement through the servlet path rather than the filter's method: the filter sits in
 * front of a real handler mapping, so it is judged on every spelling of the chat path that the
 * container would route to the chat handler.
 */
class ApiKeyScopeEnforcementMvcTest {

    @RestController
    static class ChatStub {
        @PostMapping("/v1/chat/completions")
        String chat() { return "{\"ok\":true}"; }
    }

    private static final String KEY = ApiKeyGenerator.generatePlaintext();
    private ApiKeyRepository repository;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        repository = mock(ApiKeyRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new ChatStub())
                .addFilters(new ApiKeyAuthFilter(repository))
                .build();
    }

    private void keyWith(String... scopes) {
        when(repository.findByKeyHash(ApiKeyGenerator.hash(KEY))).thenReturn(Optional.of(
                ApiKey.builder().id("k").workspaceId("ws").status(ApiKeyStatus.ACTIVE).scopes(List.of(scopes)).build()));
    }

    @Test
    void anEmbeddingsKey_cannotReachChat_byTheOrdinaryPath() throws Exception {
        keyWith("embeddings:write");
        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("api_key_scope"));
    }

    @Test
    void anEmbeddingsKey_cannotReachChat_byAnEncodedOrMatrixSpelling() throws Exception {
        // Negative control: Spring resolves the decoded first segment to the governed handler.
        MockMvc routingOnly = MockMvcBuilders.standaloneSetup(new ChatStub()).build();
        routingOnly.perform(post(URI.create("/v%31/chat/completions"))).andExpect(status().isOk());

        keyWith("embeddings:write");
        mvc.perform(post(URI.create("/v%31/chat/completions")).header("Authorization", "Bearer " + KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_path"));
        mvc.perform(post(URI.create("/v1/%63hat/completions")).header("Authorization", "Bearer " + KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_path"));
        mvc.perform(post("/v1/chat;x=1/completions").header("Authorization", "Bearer " + KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_path"));
    }

    @Test
    void aCompletionsKey_reachesChat_andAnUnscopedKeyReachesEverything() throws Exception {
        keyWith("completions:write");
        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + KEY)).andExpect(status().isOk());
        when(repository.findByKeyHash(any())).thenReturn(Optional.of(
                ApiKey.builder().id("k").workspaceId("ws").status(ApiKeyStatus.ACTIVE).build()));
        mvc.perform(post("/v1/chat/completions").header("Authorization", "Bearer " + KEY)).andExpect(status().isOk());
    }
}
