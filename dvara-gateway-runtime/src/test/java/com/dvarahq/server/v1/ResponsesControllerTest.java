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
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
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
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /v1/responses} through the assembled controller, on the same
 * {@code ChatExecutionService}-backed line chat uses. These tests check that the Responses doorway
 * gets governance, metering and streaming from that line without re-testing the pipeline itself.
 */
@WebMvcTest(ResponsesController.class)
@Import(TestMetricsConfig.class)
class ResponsesControllerTest {

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
    // The outcome listener is a bean the slice does not carry; what it is told is checked by
    // StreamingCallOutcomeTest.
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
        when(streamingResponseEnforcer.wrap(any(), any())).thenAnswer(i -> i.getArgument(0));
        when(streamingResponseEnforcer.wrap(any(), any(), any())).thenAnswer(i -> i.getArgument(0));
    }

    // -------- sync --------

    @Test
    void sync_cacheHit_stillEmitsThisRequestsGovernanceHeader() throws Exception {
        // detokenize adds the X-Gateway-Pii-Unresolved signal after the cache read, so a cache hit
        // must still emit gateway headers. A cached response is older, so its tokens are the more
        // likely to have been erased, and nobody is watching that path.
        when(responseCache.get(any())).thenReturn(Optional.of(chatResponse("gpt-4o", "Paris")));
        when(piiEnforcer.detokenizeResponse(any(ChatResponse.class), any(ChatRequest.class), any()))
                .thenAnswer(i -> ((ChatResponse) i.getArgument(0)).toBuilder()
                        .gatewayHeaders(java.util.Map.of("X-Gateway-Pii-Unresolved", "1"))
                        .build());

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "Capital of France?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "HIT"))
                .andExpect(header().string("X-Gateway-Pii-Unresolved", "1"));
    }

    @Test
    void sync_returnsResponsesShape() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "Paris"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "Capital of France?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.object").value("response"))
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.output[0].type").value("message"))
                .andExpect(jsonPath("$.output[0].role").value("assistant"))
                .andExpect(jsonPath("$.output[0].content[0].type").value("output_text"))
                .andExpect(jsonPath("$.output[0].content[0].text").value("Paris"))
                .andExpect(jsonPath("$.usage.input_tokens").value(10))
                .andExpect(jsonPath("$.usage.output_tokens").value(3))
                .andExpect(jsonPath("$.usage.total_tokens").value(13));
    }

    @Test
    void sync_instructionsAndMessageArrayInput() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "ok"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "instructions": "Be terse.",
                                 "input": [{"role": "user", "content": "hi"}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.output[0].content[0].text").value("ok"));
    }

    // -------- validation / unsupported --------

    @Test
    void missingModel_returns400() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"input": "hi"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void store_true_returns400Unsupported() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi", "store": true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    @Test
    void previousResponseId_returns400Unsupported() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi", "previous_response_id": "resp_1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    @Test
    void tools_returns400Unsupported() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi",
                                 "tools": [{"type": "web_search"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    /**
     * The dispatcher is stubbed to succeed on purpose: if {@code rejectUnsupported} let this
     * request through, the test would fail on 200-vs-400 rather than on an incidental mock NPE.
     */
    @Test
    void toolChoice_returns400Unsupported() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "ok"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi", "tool_choice": "auto"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    /** Same shape as {@link #toolChoice_returns400Unsupported}. */
    @Test
    void include_returns400Unsupported() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "ok"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi",
                                 "include": ["reasoning.encrypted_content"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
    }

    /**
     * An empty include array asks for nothing, so it must not trip the gate. The {@code tools}
     * check treats an empty array the same way.
     */
    @Test
    void emptyInclude_isNotRejected() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("gpt-4o", "ok"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi", "include": []}
                                """))
                .andExpect(status().isOk());
    }

    /**
     * A request carrying both tools and tool_choice gets the tools message, which names the
     * working alternative. This fixes the order of the two checks.
     */
    @Test
    void toolsAndToolChoice_reportsTheToolsMessage() throws Exception {
        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi",
                                 "tools": [{"type": "function", "name": "f"}],
                                 "tool_choice": "auto"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"))
                .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("function calling")));
    }

    @Test
    void providerError_returns502() throws Exception {
        when(dispatcher.chat(any()))
                .thenThrow(new com.dvarahq.core.exception.GatewayException("PROVIDER_ERROR", "upstream 500"));

        mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "input": "hi"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.type").value("provider_error"));
    }

    // -------- streaming --------

    @Test
    void streaming_emitsTypedEventSequence() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("Hel").done(false).build(),
                SseChunk.builder().id("c1").model("gpt-4o").delta("lo").finishReason("stop").done(true).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("response.created");
        assertThat(body).contains("response.output_item.added");
        assertThat(body).contains("response.content_part.added");
        assertThat(body).contains("response.output_text.delta");
        assertThat(body).contains("response.output_text.done");
        assertThat(body).contains("response.completed");
        assertThat(body).contains("\"sequence_number\":0");
        assertThat(body).contains("Hel");
        assertThat(body).contains("lo");
    }

    /**
     * A cancelled Responses request records no error and still runs its tail. Unlike the chat
     * doorway's twin, this does not check that cancellation is tested before hasNext(): this
     * doorway emits two events before the loop, and on a completed emitter those fail first, so
     * the loop is not reached either way. The loop is the same code as chat's and is checked there.
     */
    @Test
    void streaming_anAlreadyCancelledRequestNeverReadsUpstream() throws Exception {
        java.util.concurrent.CountDownLatch opening = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch clientLeft = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch tailRan = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger hasNextCalls = new java.util.concurrent.atomic.AtomicInteger();
        class Upstream implements java.util.Iterator<SseChunk>, AutoCloseable {
            @Override public boolean hasNext() { hasNextCalls.incrementAndGet(); return false; }
            @Override public SseChunk next() { throw new AssertionError("read upstream after cancellation"); }
            @Override public void close() { tailRan.countDown(); }
        }
        when(dispatcher.streamChat(any())).thenAnswer(inv -> {
            opening.countDown();
            clientLeft.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return new Upstream();
        });

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(opening.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        mvcResult.getRequest().getAsyncContext().complete();
        clientLeft.countDown();

        assertThat(tailRan.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("the tail still ran").isTrue();
        assertThat(hasNextCalls.get()).as("cancellation is checked before any upstream read").isZero();
        assertThat(mvcResult.getRequest().getAttribute(com.dvarahq.server.web.AccessLogFilter.ATTR_ERROR_CODE)).isNull();
    }

    // The guard learns about cancellation only through close(), so the controller has to call it.
    @Test
    void streaming_closesTheGuardWhenTheStreamEnds() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("c1").model("gpt-4o").delta(null).finishReason("stop").done(true).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        class ClosableGuard implements java.util.Iterator<SseChunk>, AutoCloseable {
            private final java.util.Iterator<SseChunk> delegate;
            ClosableGuard(java.util.Iterator<SseChunk> delegate) { this.delegate = delegate; }
            @Override public boolean hasNext() { return delegate.hasNext(); }
            @Override public SseChunk next() { return delegate.next(); }
            @Override public void close() { closed.countDown(); }
        }
        when(streamingResponseEnforcer.wrap(any(), any(), any()))
                .thenAnswer(i -> new ClosableGuard(i.getArgument(0)));

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        // The finally block runs on the virtual thread after the emitter completes.
        assertThat(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the controller must close the guard so cancellation can be finalized")
                .isTrue();
    }

    @Test
    void streaming_persistsTokenUsageRecord() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("streaming output text").finishReason("stop").done(true).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        // Streaming Responses calls land an estimated token_usage row, as streaming chat does.
        verify(tokenUsageRepository, timeout(2000).times(1))
                .save(argThat(r -> r != null && "gpt-4o".equals(r.getModel())
                        && r.isEstimated() && r.getOutputTokens() > 0 && r.getInputTokens() > 0));
    }

    private static ChatResponse chatResponse(String model, String text) {
        return ChatResponse.builder()
                .id("chatcmpl-x").model(model).object("chat.completion").created(1704067200L)
                .choices(List.of(ChatResponse.Choice.builder().index(0)
                        .message(MultimodalMessage.assistant(text)).finishReason("stop").build()))
                .usage(ChatResponse.Usage.builder().promptTokens(10).completionTokens(3).totalTokens(13).build())
                .build();
    }

    /** A stream that ends before its finish is reported as failed, not completed. */
    @Test
    void streaming_aStreamThatEndsBeforeItsFinishIsReportedFailed() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("Hel").done(false).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/responses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "input": "hi"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(mvcResult)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("response.failed");
        assertThat(body).doesNotContain("response.completed");
    }
}
