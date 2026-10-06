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
package com.dvarahq.server.v1;

import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ModelContextLimits;
import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ModelListFilter;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.OptionalInt;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An application's {@link ModelListFilter} and {@link ModelContextLimits} beans reach the model list. */
@WebMvcTest(ModelsController.class)
@Import({TestMetricsConfig.class, ModelsControllerWiringTest.Beans.class})
class ModelsControllerWiringTest {

    /** The workspace the slice's test authentication puts on every request. */
    static final String WORKSPACE = com.dvarahq.server.TestApiKey.WORKSPACE;

    @TestConfiguration
    static class Beans {
        /** The test workspace may not use gpt-4o or claude-opus-4-1. */
        @Bean
        ModelListFilter turnedOff() {
            return (workspace, models) -> WORKSPACE.equals(workspace)
                    ? models.stream().filter(m -> !List.of("gpt-4o", "claude-opus-4-1").contains(m.id())).toList()
                    : models;
        }

        @Bean
        ModelContextLimits catalogue() {
            return (provider, model) -> "gpt-4.1".equals(model) ? OptionalInt.of(1_047_576) : OptionalInt.empty();
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RateLimiter rateLimiter;

    @MockitoBean
    ProviderDispatcher dispatcher;

    private void providers() {
        LlmProvider openai = mock(LlmProvider.class);
        when(openai.name()).thenReturn("openai");
        when(openai.capabilities()).thenReturn(new ProviderCapabilities(true, true, true, true, true, 128_000));
        when(openai.listModels()).thenReturn(List.of(
                new ModelInfo("gpt-4o", "openai", 1L), new ModelInfo("gpt-4.1", "openai", 2L)));
        LlmProvider anthropic = mock(LlmProvider.class);
        when(anthropic.name()).thenReturn("anthropic");
        when(anthropic.capabilities()).thenReturn(new ProviderCapabilities(true, true, true, true, true, 200_000));
        when(anthropic.listModels()).thenReturn(List.of(
                new ModelInfo("claude-sonnet-4-5", "anthropic", 0L), new ModelInfo("claude-opus-4-1", "anthropic", 0L)));
        when(dispatcher.allProviders()).thenReturn(List.of(openai, anthropic));
    }

    @Test
    void theListLeavesOutWhatTheWorkspaceMayNotUseAndShowsEachModelsWindow() throws Exception {
        providers();

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value("gpt-4.1"))
                .andExpect(jsonPath("$.data[0].capabilities.max_context_tokens").value(1047576))
                .andExpect(jsonPath("$.data[1].id").value("claude-sonnet-4-5"))
                .andExpect(jsonPath("$.data[1].capabilities.max_context_tokens").value(200000));
    }

    @Test
    void anAnthropicClientGetsAnthropicsShape() throws Exception {
        providers();

        mockMvc.perform(get("/v1/models").header("anthropic-version", "2023-06-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").doesNotExist())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].type").value("model"))
                .andExpect(jsonPath("$.data[0].id").value("claude-sonnet-4-5"))
                .andExpect(jsonPath("$.data[0].display_name").value("claude-sonnet-4-5"))
                .andExpect(jsonPath("$.data[0].created_at").value("1970-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.has_more").value(false))
                .andExpect(jsonPath("$.first_id").value("claude-sonnet-4-5"))
                .andExpect(jsonPath("$.last_id").value("claude-sonnet-4-5"));
    }
}
