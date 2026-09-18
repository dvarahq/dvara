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

import com.dvarahq.server.TestApiKey;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /v1/completions} is a doorway onto the same execution line as chat. These tests check the
 * doorway's translation and that the shared line is entered; the line itself is checked by
 * {@code ChatCompletionControllerTest}.
 */
@WebMvcTest(LegacyCompletionController.class)
@Import(TestMetricsConfig.class)
class LegacyCompletionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean ProviderDispatcher dispatcher;
    @MockitoBean RequestPipeline requestPipeline;
    @MockitoBean ResponseCache responseCache;
    @MockitoBean com.dvarahq.core.metering.TokenUsageRepository tokenUsageRepository;
    @MockitoBean CostCalculationService costCalculationService;
    @MockitoBean CostEstimator costEstimator;
    @MockitoBean PiiEnforcer piiEnforcer;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean StreamingResponseEnforcer streamingResponseEnforcer;
    @MockitoBean AuditWriter auditWriter;
    @MockitoBean PriorityAdmissionController priorityAdmissionController;
    @MockitoBean com.dvarahq.core.metering.WorkspaceUsageListener usageListener;
    @MockitoBean com.dvarahq.core.metering.CallOutcomeListener outcomeListener;

    @BeforeEach
    void setUp() {
        when(responseCache.get(any())).thenReturn(Optional.empty());
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(requestPipeline.postDispatch(any(ChatRequest.class), any(ChatResponse.class), any(FilterContext.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(piiEnforcer.stripForCache(any(ChatRequest.class), any())).thenAnswer(i -> i.getArgument(0));
        when(piiEnforcer.detokenizeResponse(any(ChatResponse.class), any(ChatRequest.class), any())).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void happyPath_returns200WithTextCompletionFormat() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("cmpl-test", "gpt-3.5-turbo-instruct", "This is a test."));

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-3.5-turbo-instruct", "prompt": "Say this is a test", "max_tokens": 7}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("cmpl-test"))
                .andExpect(jsonPath("$.object").value("text_completion"))
                .andExpect(jsonPath("$.model").value("gpt-3.5-turbo-instruct"))
                .andExpect(jsonPath("$.choices[0].text").value("This is a test."))
                .andExpect(jsonPath("$.choices[0].index").value(0))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(10))
                .andExpect(jsonPath("$.usage.completion_tokens").value(3))
                .andExpect(jsonPath("$.usage.total_tokens").value(13))
                .andExpect(header().string("X-Cache", "MISS"))
                .andExpect(header().exists("X-Trace-ID"));
    }

    @Test
    void promptIsWrappedAsUserMessage_andTopPTravels() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-3.5-turbo-instruct", "ok"));

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-3.5-turbo-instruct", "prompt": "Hello", "temperature": 0.2, "top_p": 0.9}
                                """))
                .andExpect(status().isOk());

        verify(dispatcher).chat(argThat(r -> r.getMessages().size() == 1
                && "user".equals(r.getMessages().get(0).getRole())
                && r.getTopP() != null && r.getTopP() == 0.9
                && r.getTemperature() == 0.2));
    }

    // -------------------------------------------------------------------------
    // the doorway enters the shared execution line
    // -------------------------------------------------------------------------

    @Test
    void runsTheGovernancePipeline_withTheWorkspaceAndKeyOnTheContext() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "m", "ok"));

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<FilterContext> ctx = ArgumentCaptor.forClass(FilterContext.class);
        verify(requestPipeline).preDispatch(any(ChatRequest.class), ctx.capture());
        assertThat(ctx.getValue().getWorkspaceId()).isEqualTo(TestApiKey.WORKSPACE);
        assertThat(ctx.getValue().getApiKey()).isEqualTo(TestApiKey.ID);
        verify(requestPipeline).postDispatch(any(ChatRequest.class), any(ChatResponse.class), any(FilterContext.class));
    }

    @Test
    void metersUsageAndCost_likeEveryOtherDoorway() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "m", "ok"));

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> saved =
                ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository).save(saved.capture());
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(TestApiKey.WORKSPACE);
        assertThat(saved.getValue().getApiKey()).isEqualTo(TestApiKey.ID);
        assertThat(saved.getValue().getTotalTokens()).isEqualTo(13);
        verify(costCalculationService).calculateAndPersist(any(), any(), eq(TestApiKey.WORKSPACE), eq(TestApiKey.ID), any());
    }

    @Test
    void policyDenial_isRefusedBeforeTheProviderIsCalled() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("POLICY_DENIED", "denied by policy"));

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("policy_denied"));

        verify(dispatcher, never()).chat(any());
    }

    @Test
    void aHeaderAFilterAskedFor_reachesTheWire() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenAnswer(inv -> {
                    FilterContext ctx = inv.getArgument(1);
                    ctx.setResponseHeader("X-Budget-Remaining-Pct", "75");
                    return inv.getArgument(0);
                });
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "m", "ok"));

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Budget-Remaining-Pct", "75"));
    }

    @Test
    void cacheHit_servesFromCache_withXCacheHit() throws Exception {
        when(responseCache.get(any())).thenReturn(Optional.of(chatResponse("cached", "m", "from cache")));

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "HIT"))
                .andExpect(jsonPath("$.choices[0].text").value("from cache"));

        verify(dispatcher, never()).chat(any());
    }

    // -------------------------------------------------------------------------
    // What the legacy shape cannot carry is refused, never dropped
    // -------------------------------------------------------------------------

    @Test
    void streamTrue_isRefused_notServedAsJson() throws Exception {
        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello", "stream": true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
        verify(dispatcher, never()).chat(any());
    }

    @Test
    void nAboveOne_andBestOf_areRefused() throws Exception {
        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello", "n": 3}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello", "best_of": 2}
                                """))
                .andExpect(status().isBadRequest());
        // n=1 and best_of=1 are the defaults and pass.
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "m", "ok"));
        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello", "n": 1, "best_of": 1}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    void arrayPrompts_andTokenArrays_areRefused_notFlattened() throws Exception {
        // The legacy shape allows several prompts or encoded input; this doorway returns one choice
        // for one message, so flattening the array into a string would answer a fabricated prompt.
        for (String prompt : new String[]{"[\"one\", \"two\"]", "[[1, 2, 3]]"}) {
            mockMvc.perform(post("/v1/completions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\": \"m\", \"prompt\": " + prompt + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
        }
        verify(dispatcher, never()).chat(any());
    }

    @Test
    void zeroOrNegativeMultiplicity_isRefused_notTreatedAsOne() throws Exception {
        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello", "n": 0}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello", "best_of": -1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void modelAndStreamAttributes_areSetBeforeAnyRefusal() throws Exception {
        // The access log, the response audit and the request metrics read these; a refused
        // stream request is still attributed to its model and its requested mode.
        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-3.5-turbo-instruct", "prompt": "Hello", "stream": true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                        .attribute(com.dvarahq.server.web.AccessLogFilter.ATTR_MODEL, "gpt-3.5-turbo-instruct"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                        .attribute(com.dvarahq.server.web.AccessLogFilter.ATTR_STREAM, "true"));
    }

    @Test
    void anAdmittedRequestRefusedLaterInThePipeline_releasesItsPrioritySlot() throws Exception {
        // PriorityAdmissionFilter admits at 600; ContextWindowFilter can still refuse at 900, and
        // the slot taken at admission has to be released on that path too.
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenAnswer(inv -> {
                    FilterContext ctx = inv.getArgument(1);
                    ctx.setPriorityTier("PREMIUM");
                    throw new GatewayException("CONTEXT_WINDOW_EXCEEDED", "too long");
                });

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello"}
                                """))
                .andExpect(status().isBadRequest());

        verify(priorityAdmissionController).release(com.dvarahq.core.routing.PriorityTier.PREMIUM);
        verify(dispatcher, never()).chat(any());
    }

    @Test
    void aRequestRefusedAtAdmission_releasesNothing() throws Exception {
        // The mirror image of the test above: a refusal at admission holds no slot, and the
        // context carries no tier, so the release on the failure path must not fire.
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("PRIORITY_THROTTLED", "over capacity"));

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello"}
                                """))
                .andExpect(status().isTooManyRequests());

        verify(priorityAdmissionController, never()).release(any());
    }

    // -------------------------------------------------------------------------
    // Validation and errors
    // -------------------------------------------------------------------------

    @Test
    void missingModel_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"prompt": "Hello"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingPrompt_returns400ValidationError() throws Exception {
        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noProvider_returns400() throws Exception {
        when(dispatcher.chat(any()))
                .thenThrow(new GatewayException("NO_PROVIDER", "No provider configured for model: m"));

        mockMvc.perform(post("/v1/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("no_provider"));
    }

    @Test
    void incomingTraceId_isEchoedInResponseHeader() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "m", "ok"));

        mockMvc.perform(post("/v1/completions")
                        .header("X-Trace-ID", "trace-abc-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "m", "prompt": "Hello"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-ID", "trace-abc-123"));
    }

    private static ChatResponse chatResponse(String id, String model, String text) {
        return ChatResponse.builder()
                .id(id)
                .model(model)
                .object("chat.completion")
                .created(1704067200L)
                .choices(List.of(
                        ChatResponse.Choice.builder()
                                .index(0)
                                .message(MultimodalMessage.assistant(text))
                                .finishReason("stop")
                                .build()
                ))
                .usage(ChatResponse.Usage.builder()
                        .promptTokens(10)
                        .completionTokens(3)
                        .totalTokens(13)
                        .build())
                .build();
    }
}
