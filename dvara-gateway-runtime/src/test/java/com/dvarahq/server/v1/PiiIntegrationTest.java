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

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatCompletionController.class)
@Import(TestMetricsConfig.class)
class PiiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    ProviderDispatcher dispatcher;

    @MockitoBean
    RequestPipeline requestPipeline;

    @MockitoBean
    ResponseCache responseCache;

    @MockitoBean
    com.dvarahq.core.metering.TokenUsageRepository tokenUsageRepository;

    @MockitoBean
    CostCalculationService costCalculationService;

    @MockitoBean
    CostEstimator costEstimator;

    @MockitoBean
    PiiEnforcer piiEnforcer;

    @MockitoBean
    RateLimiter rateLimiter;

    @MockitoBean
    StreamingResponseEnforcer streamingResponseEnforcer;

    @MockitoBean
    AuditWriter auditWriter;

    @MockitoBean
    PriorityAdmissionController priorityAdmissionController;

    @MockitoBean
    com.dvarahq.core.metering.WorkspaceUsageListener usageListener;

    // The outcome listener is a bean the slice does not carry; what it is told is checked by
    // StreamingCallOutcomeTest.
    @MockitoBean com.dvarahq.core.metering.CallOutcomeListener outcomeListener;

    @BeforeEach
    void setUp() {
        when(responseCache.get(any())).thenReturn(Optional.empty());
        // By default the pipeline passes the request and response through unchanged.
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(requestPipeline.postDispatch(any(ChatRequest.class), any(ChatResponse.class), any(FilterContext.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(piiEnforcer.stripForCache(any(ChatRequest.class), any())).thenAnswer(i -> i.getArgument(0));
        when(piiEnforcer.detokenizeResponse(any(ChatResponse.class), any(ChatRequest.class), any())).thenAnswer(i -> i.getArgument(0));
        when(streamingResponseEnforcer.wrap(any(), any())).thenAnswer(i -> i.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // PII detected with LOG action: the request passes through
    // -------------------------------------------------------------------------

    @Test
    void piiDetected_logAction_requestPassesThrough() throws Exception {
        // With the LOG action the pipeline hands the request back unchanged.
        when(dispatcher.chat(any())).thenReturn(chatResponse("pii-log-1", "gpt-4o-mini", "Hello!"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o-mini",
                                  "messages": [{"role": "user", "content": "My email is user@example.com"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("pii-log-1"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Hello!"));
    }

    // -------------------------------------------------------------------------
    // PII detected with BLOCK action: 400
    // -------------------------------------------------------------------------

    @Test
    void piiDetected_blockAction_returns400() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("PII_DETECTED",
                        "PII detected in request: EMAIL found"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o-mini",
                                  "messages": [{"role": "user", "content": "My email is user@example.com"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("pii_detected"))
                .andExpect(jsonPath("$.error.type").value("pii_violation"));

        verify(dispatcher, never()).chat(any());
    }

    // -------------------------------------------------------------------------
    // PII detected with REDACT action: the redacted request reaches the provider
    // -------------------------------------------------------------------------

    @Test
    void piiDetected_redactAction_returnsRedactedResponse() throws Exception {
        // The pipeline returns a rewritten request with the PII redacted.
        ChatRequest redactedRequest = ChatRequest.builder()
                .model("gpt-4o-mini")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock("My email is [REDACTED_EMAIL]")))
                        .build()))
                .build();
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenReturn(redactedRequest);

        when(dispatcher.chat(any())).thenReturn(chatResponse("pii-redact-1", "gpt-4o-mini", "Got it, your email is noted."));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o-mini",
                                  "messages": [{"role": "user", "content": "My email is user@example.com"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("pii-redact-1"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Got it, your email is noted."));

        verify(dispatcher).chat(argThat(req ->
                req.getMessages().get(0).getContent().get(0) instanceof ContentBlock.TextBlock tb
                        && tb.text().contains("[REDACTED_EMAIL]")));
    }

    // -------------------------------------------------------------------------
    // PII in the response: the response is redacted
    // -------------------------------------------------------------------------

    @Test
    void piiInResponse_detected_responseRedacted() throws Exception {
        when(dispatcher.chat(any())).thenReturn(
                chatResponse("pii-resp-1", "gpt-4o-mini", "The email is user@example.com"));

        // postDispatch returns a rewritten response with the PII redacted.
        when(requestPipeline.postDispatch(any(ChatRequest.class), any(ChatResponse.class), any(FilterContext.class)))
                .thenReturn(ChatResponse.builder()
                        .id("pii-resp-1")
                        .model("gpt-4o-mini")
                        .object("chat.completion")
                        .created(1704067200L)
                        .choices(List.of(ChatResponse.Choice.builder()
                                .index(0)
                                .message(MultimodalMessage.assistant("The email is [REDACTED_EMAIL]"))
                                .finishReason("stop")
                                .build()))
                        .usage(ChatResponse.Usage.builder()
                                .promptTokens(10).completionTokens(5).totalTokens(15).build())
                        .build());

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o-mini",
                                  "messages": [{"role": "user", "content": "My email is user@example.com"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.content").value("The email is [REDACTED_EMAIL]"));
    }

    // -------------------------------------------------------------------------
    // No PII: the request passes normally
    // -------------------------------------------------------------------------

    @Test
    void noPii_requestPassesNormally() throws Exception {
        when(dispatcher.chat(any())).thenReturn(
                chatResponse("no-pii-1", "gpt-4o-mini", "The capital of France is Paris."));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o-mini",
                                  "messages": [{"role": "user", "content": "What is the capital of France?"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("no-pii-1"))
                .andExpect(jsonPath("$.model").value("gpt-4o-mini"))
                .andExpect(jsonPath("$.choices[0].message.content").value("The capital of France is Paris."));

        verify(requestPipeline).preDispatch(any(ChatRequest.class), any(FilterContext.class));
        verify(requestPipeline).postDispatch(any(ChatRequest.class), any(ChatResponse.class), any(FilterContext.class));
        verify(dispatcher).chat(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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