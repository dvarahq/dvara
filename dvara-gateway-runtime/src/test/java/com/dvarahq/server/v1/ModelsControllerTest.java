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
import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ModelsController.class)
@Import(TestMetricsConfig.class)
class ModelsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RateLimiter rateLimiter;

    @MockitoBean
    ProviderDispatcher dispatcher;

    @Test
    void getModels_returns200WithExpectedStructure() throws Exception {
        LlmProvider openai = openaiProvider();
        when(dispatcher.allProviders()).thenReturn(List.of(openai));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("list"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getModels_returnsModelsFromProviderListModels() throws Exception {
        LlmProvider openai = openaiProvider();
        LlmProvider anthropic = anthropicProvider();
        when(dispatcher.allProviders()).thenReturn(List.of(openai, anthropic));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5)); // 3 openai + 2 anthropic
    }

    @Test
    void getModels_containsExpectedOpenAiModels() throws Exception {
        LlmProvider openai = openaiProvider();
        when(dispatcher.allProviders()).thenReturn(List.of(openai));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("gpt-4o"))
                .andExpect(jsonPath("$.data[0].owned_by").value("openai"))
                .andExpect(jsonPath("$.data[0].object").value("model"));
    }

    @Test
    void getModels_containsAnthropicModel() throws Exception {
        LlmProvider anthropic = anthropicProvider();
        when(dispatcher.allProviders()).thenReturn(List.of(anthropic));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='claude-sonnet-4-5')].owned_by").value("anthropic"));
    }

    @Test
    void getModels_includesCapabilitiesPerModel() throws Exception {
        LlmProvider openai = openaiProvider();
        when(dispatcher.allProviders()).thenReturn(List.of(openai));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].capabilities.supports_streaming").value(true))
                .andExpect(jsonPath("$.data[0].capabilities.supports_vision").value(true))
                .andExpect(jsonPath("$.data[0].capabilities.supports_tool_calls").value(true))
                .andExpect(jsonPath("$.data[0].capabilities.supports_structured_outputs").value(true))
                .andExpect(jsonPath("$.data[0].capabilities.supports_json_mode").value(true))
                .andExpect(jsonPath("$.data[0].capabilities.max_context_tokens").value(128000));
    }

    @Test
    void getModels_xTraceIdHeaderIsPresent() throws Exception {
        LlmProvider openai = openaiProvider();
        when(dispatcher.allProviders()).thenReturn(List.of(openai));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-ID"));
    }

    @Test
    void getModels_incomingTraceIdIsEchoed() throws Exception {
        LlmProvider openai = openaiProvider();
        when(dispatcher.allProviders()).thenReturn(List.of(openai));

        mockMvc.perform(get("/v1/models")
                        .header("X-Trace-ID", "test-trace-abc"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-ID", "test-trace-abc"));
    }

    @Test
    void getModels_noProvidersRegistered_returnsEmptyList() throws Exception {
        when(dispatcher.allProviders()).thenReturn(List.of());

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getModels_providerReturnsEmptyModelList() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("empty-provider");
        when(provider.capabilities()).thenReturn(
                new ProviderCapabilities(true, false, false, false, false, 64_000));
        when(provider.listModels()).thenReturn(List.of());
        when(dispatcher.allProviders()).thenReturn(List.of(provider));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void getModels_ollamaCapabilitiesShowFalseValues() throws Exception {
        LlmProvider ollama = ollamaProvider();
        when(dispatcher.allProviders()).thenReturn(List.of(ollama));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("ollama/llama3.2"))
                .andExpect(jsonPath("$.data[0].capabilities.supports_vision").value(false))
                .andExpect(jsonPath("$.data[0].capabilities.supports_tool_calls").value(false))
                .andExpect(jsonPath("$.data[0].capabilities.supports_structured_outputs").value(false))
                .andExpect(jsonPath("$.data[0].capabilities.supports_json_mode").value(false))
                .andExpect(jsonPath("$.data[0].capabilities.max_context_tokens").value(32000));
    }

    @Test
    void getModels_createdTimestampIsPreserved() throws Exception {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("test");
        when(provider.capabilities()).thenReturn(
                new ProviderCapabilities(true, false, false, false, false, 64_000));
        when(provider.listModels()).thenReturn(List.of(
                new ModelInfo("test-model", "test-org", 1700000000L)));
        when(dispatcher.allProviders()).thenReturn(List.of(provider));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].created").value(1700000000));
    }

    @Test
    void getModels_oneProviderFails_othersStillReturned() throws Exception {
        LlmProvider openai = openaiProvider();

        LlmProvider failing = mock(LlmProvider.class);
        when(failing.name()).thenReturn("broken");
        when(failing.capabilities()).thenReturn(
                new ProviderCapabilities(true, false, false, false, false, 64_000));
        when(failing.listModels()).thenThrow(new RuntimeException("API unreachable"));

        LlmProvider anthropic = anthropicProvider();

        when(dispatcher.allProviders()).thenReturn(List.of(openai, failing, anthropic));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5)); // 3 openai + 0 broken + 2 anthropic
    }

    @Test
    void getModels_allProvidersFail_returnsEmptyList() throws Exception {
        LlmProvider failing = mock(LlmProvider.class);
        when(failing.name()).thenReturn("broken");
        when(failing.capabilities()).thenReturn(
                new ProviderCapabilities(true, false, false, false, false, 64_000));
        when(failing.listModels()).thenThrow(new RuntimeException("Connection refused"));

        when(dispatcher.allProviders()).thenReturn(List.of(failing));

        mockMvc.perform(get("/v1/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private static LlmProvider ollamaProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("ollama");
        when(provider.capabilities()).thenReturn(
                new ProviderCapabilities(true, false, false, false, false, 32_000));
        when(provider.listModels()).thenReturn(List.of(
                new ModelInfo("ollama/llama3.2", "ollama")));
        return provider;
    }

    private static LlmProvider openaiProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("openai");
        when(provider.capabilities()).thenReturn(
                new ProviderCapabilities(true, true, true, true, true, 128_000));
        when(provider.listModels()).thenReturn(List.of(
                new ModelInfo("gpt-4o", "openai", 1700000000L),
                new ModelInfo("gpt-4o-mini", "openai", 1700000000L),
                new ModelInfo("gpt-3.5-turbo", "openai", 1700000000L)));
        return provider;
    }

    private static LlmProvider anthropicProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn("anthropic");
        when(provider.capabilities()).thenReturn(
                new ProviderCapabilities(true, true, true, true, true, 200_000));
        when(provider.listModels()).thenReturn(List.of(
                new ModelInfo("claude-sonnet-4-5", "anthropic"),
                new ModelInfo("claude-3-5-haiku-20241022", "anthropic")));
        return provider;
    }
}