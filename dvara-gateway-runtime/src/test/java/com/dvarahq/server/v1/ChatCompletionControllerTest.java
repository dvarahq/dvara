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

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import org.mockito.ArgumentCaptor;
import static org.mockito.ArgumentMatchers.eq;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import com.dvarahq.server.web.AccessLogFilter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatCompletionController.class)
@Import(TestMetricsConfig.class)
class ChatCompletionControllerTest {

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
        when(streamingResponseEnforcer.wrap(any(), any(), any())).thenAnswer(i -> i.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // one identifier for a key: its opaque id, never the bearer token
    // -------------------------------------------------------------------------

    @Test
    void budgetAndAttribution_useTheKeyId_neverTheBearerToken() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("chatcmpl-abc", "gpt-4o", "Paris"));

        mockMvc.perform(post("/v1/chat/completions")
                        .requestAttr("workspaceId", "ws-1")
                        .requestAttr(com.dvarahq.server.web.ApiKeyAuthFilter.API_KEY_ATTR, "gw_rawsecretvalue0123456789")
                        .requestAttr(com.dvarahq.server.web.ApiKeyAuthFilter.API_KEY_ID_ATTR, "key-id-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isOk());

        // Budget caps are keyed on ApiKey.id, so that is what every filter sees on the context.
        ArgumentCaptor<FilterContext> ctx = ArgumentCaptor.forClass(FilterContext.class);
        verify(requestPipeline).preDispatch(any(ChatRequest.class), ctx.capture());
        assertThat(ctx.getValue().getApiKey()).isEqualTo("key-id-1");

        // And the persisted row attributes to the same id — not the token, not its prefix.
        ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> saved =
                ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository).save(saved.capture());
        assertThat(saved.getValue().getApiKey()).isEqualTo("key-id-1");
        verify(costCalculationService).calculateAndPersist(any(), any(), eq("ws-1"), eq("key-id-1"), any());
    }

    @Test
    void anonymousRequest_attributesToAnonymous() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("chatcmpl-abc", "gpt-4o", "Paris"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> saved =
                ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository).save(saved.capture());
        assertThat(saved.getValue().getApiKey()).isEqualTo("anonymous");
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    void happyPath_returns200WithExpectedJson() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("chatcmpl-abc", "gpt-4o", "Paris"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o",
                                  "messages": [{"role": "user", "content": "Capital of France?"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("chatcmpl-abc"))
                .andExpect(jsonPath("$.model").value("gpt-4o"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Paris"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("stop"))
                .andExpect(jsonPath("$.usage.prompt_tokens").value(10))
                .andExpect(jsonPath("$.usage.completion_tokens").value(3))
                .andExpect(jsonPath("$.usage.total_tokens").value(13));
    }

    @Test
    void happyPath_xTraceIdHeaderIsPresent() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-ID"));
    }

    @Test
    void incomingTraceId_isEchoedInResponseHeader() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-Trace-ID", "my-custom-trace-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-ID", "my-custom-trace-001"));
    }

    // -------------------------------------------------------------------------
    // Validation errors → 400
    // -------------------------------------------------------------------------

    @Test
    void missingModel_returns400WithValidationError() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("validation_error"))
                .andExpect(jsonPath("$.error.param").value("model"))
                .andExpect(jsonPath("$.error.trace_id").isNotEmpty());
    }

    @Test
    void emptyMessages_returns400WithValidationError() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "messages": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{bad json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("invalid_json"));
    }

    // -------------------------------------------------------------------------
    // Provider errors
    // -------------------------------------------------------------------------

    @Test
    void noProvider_returns400() throws Exception {
        when(dispatcher.chat(any()))
                .thenThrow(new GatewayException("NO_PROVIDER",
                        "No provider configured for model: gpt-4o. Set OPENAI_API_KEY to register the provider."));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"))
                .andExpect(jsonPath("$.error.code").value("no_provider"));
    }

    @Test
    void providerError_returns502() throws Exception {
        when(dispatcher.chat(any()))
                .thenThrow(new GatewayException("PROVIDER_ERROR", "OpenAI API error 500"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.type").value("provider_error"))
                .andExpect(jsonPath("$.error.code").value("provider_error"));
    }

    @Test
    void unexpectedException_returns500WithGatewayError() throws Exception {
        when(dispatcher.chat(any()))
                .thenThrow(new RuntimeException("something went wrong internally"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.type").value("gateway_error"))
                .andExpect(jsonPath("$.error.code").value("internal_error"))
                .andExpect(jsonPath("$.error.trace_id").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Test
    void streaming_returnsTextEventStreamContentType() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta("Hello").done(false).build(),
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta(" world").finishReason("stop").done(true).build()
        );
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/event-stream"));
    }

    @Test
    void streaming_chunksContainDataPrefixAndDoneMarker() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta(null).finishReason("stop").done(true).build()
        );
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("data:");
        assertThat(body).contains("chat.completion.chunk");
        assertThat(body).contains("[DONE]");
    }

    // ---- the tail runs before the emitter completes, so the async dispatch sees its work ----

    /**
     * The async result exists only once the emitter completed. The token attributes the access
     * log, metrics and audit filters read on that dispatch must already be on the request by then,
     * which is only true if the tail ran before completion.
     */
    @Test
    void streaming_tokenAttributesAreOnTheRequestBeforeTheEmitterCompletes() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta(null).finishReason("stop").done(true).build()
        );
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvcResult.getAsyncResult();   // blocks until the emitter completed

        assertThat(mvcResult.getRequest().getAttribute(AccessLogFilter.ATTR_TOKENS_TOTAL)).isNotNull();
        assertThat(mvcResult.getRequest().getAttribute(AccessLogFilter.ATTR_ERROR_CODE)).isNull();
    }

    /** A mid-stream failure is recorded under an error code before the emitter is completed with it. */
    @Test
    void streaming_aMidStreamFailureSetsTheErrorCodeBeforeCompletion() throws Exception {
        java.util.Iterator<SseChunk> failing = new java.util.Iterator<>() {
            private int n;
            @Override public boolean hasNext() { return true; }
            @Override public SseChunk next() {
                if (n++ == 0) return SseChunk.builder().id("chunk-1").model("gpt-4o").delta("Hi").done(false).build();
                throw new IllegalStateException("upstream reset");
            }
        };
        when(dispatcher.streamChat(any())).thenReturn(failing);

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        Object outcome = mvcResult.getAsyncResult();

        assertThat(outcome).isInstanceOf(IllegalStateException.class);
        assertThat(mvcResult.getRequest().getAttribute(AccessLogFilter.ATTR_ERROR_CODE)).isEqualTo("STREAM_ERROR");
        assertThat(mvcResult.getRequest().getAttribute(AccessLogFilter.ATTR_TOKENS_TOTAL))
                .as("what was emitted before the failure is still metered").isNotNull();
    }

    /**
     * An already-cancelled request never reads upstream. The client leaves while the upstream is
     * still being opened; the loop checks the cancellation flag before hasNext(), so no read
     * happens at all. A read there can block on the wire, and an end of stream there would be
     * recorded as an upstream failure against nobody.
     */
    @Test
    void streaming_anAlreadyCancelledRequestNeverReadsUpstream() throws Exception {
        java.util.concurrent.CountDownLatch opening = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch clientLeft = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch tailRan = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger hasNextCalls = new java.util.concurrent.atomic.AtomicInteger();
        // AutoCloseable: the controller closes the guard in its tail, which is the signal the tail ran.
        class Upstream implements java.util.Iterator<SseChunk>, AutoCloseable {
            @Override public boolean hasNext() { hasNextCalls.incrementAndGet(); return false; }
            @Override public SseChunk next() { throw new AssertionError("read upstream after cancellation"); }
            @Override public void close() { tailRan.countDown(); }
        }
        java.util.Iterator<SseChunk> upstream = new Upstream();
        when(dispatcher.streamChat(any())).thenAnswer(inv -> {
            opening.countDown();                                  // the emit thread is opening the stream
            clientLeft.await(5, java.util.concurrent.TimeUnit.SECONDS);   // ... while the client leaves
            return upstream;
        });

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(opening.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // The client leaves: the container completes the async request, which fires onCompletion.
        mvcResult.getRequest().getAsyncContext().complete();
        clientLeft.countDown();

        assertThat(tailRan.await(5, java.util.concurrent.TimeUnit.SECONDS)).as("the tail still ran").isTrue();
        assertThat(hasNextCalls.get()).as("cancellation is checked before any upstream read").isZero();
        assertThat(mvcResult.getRequest().getAttribute(AccessLogFilter.ATTR_ERROR_CODE)).as("not an upstream failure").isNull();
    }

    /**
     * A stream whose request the container recycles before the tail runs — a disconnect or the
     * emitter timeout — is still attributed to its key, workspace and provider. The provider
     * is stamped by the dispatcher while the stream opens, so the snapshot's second phase runs after
     * openStream; here the attributes are cleared after that and before the first chunk, which is the
     * state a recycled request presents to a late reader.
     */
    @Test
    void streaming_aRecycledRequestStillGetsAnAttributedUsageRow() throws Exception {
        java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch cleared = new java.util.concurrent.CountDownLatch(1);
        java.util.Iterator<SseChunk> upstream = new java.util.Iterator<>() {
            int i = 0;
            @Override public boolean hasNext() {
                if (i == 0) {
                    opened.countDown();
                    try { cleared.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                return i < 1;
            }
            @Override public SseChunk next() {
                i++;
                return SseChunk.builder().id("c").model("gpt-4o").delta("hello").finishReason("stop").done(true)
                        .usage(ChatResponse.Usage.builder().promptTokens(8).completionTokens(4).totalTokens(12).build()).build();
            }
        };
        when(dispatcher.streamChat(any())).thenAnswer(inv -> {
            // what the real dispatcher does while opening: stamp the provider on the live request
            ((org.springframework.web.context.request.ServletRequestAttributes)
                    org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes())
                    .getRequest().setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");
            return upstream;
        });

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .requestAttr(com.dvarahq.server.web.ApiKeyAuthFilter.API_KEY_ID_ATTR, "key-77")
                        .requestAttr("workspaceId", "ws-77")
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(opened.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // A recycled request denies a late reader these attributes. (Spring keeps its own async
        // bookkeeping on the same request, so only ours are removed here.)
        for (String attr : new String[] {com.dvarahq.server.web.ApiKeyAuthFilter.API_KEY_ID_ATTR, "workspaceId",
                AccessLogFilter.ATTR_PROVIDER, com.dvarahq.providers.support.CredentialInterceptor.FINGERPRINT_ATTRIBUTE}) {
            mvcResult.getRequest().removeAttribute(attr);
        }
        cleared.countDown();
        mvcResult.getAsyncResult();

        org.mockito.ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> row =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository, org.mockito.Mockito.timeout(5000)).save(row.capture());
        assertThat(row.getValue().getWorkspaceId()).isEqualTo("ws-77");
        assertThat(row.getValue().getApiKey()).isEqualTo("key-77");
        assertThat(row.getValue().getProvider()).isEqualTo("openai");
    }

    /** tools with stream=true is served, and the call reaches the client in OpenAI's shape. */
    @Test
    void streaming_withTools_relaysTheCallOnTheWire() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c").model("gpt-4o")
                        .toolCalls(List.of(ToolCallDelta.open(0, "call_abc", "get_weather", null))).done(false).build(),
                SseChunk.builder().id("c").model("gpt-4o")
                        .toolCalls(List.of(ToolCallDelta.arguments(0, "{\"city\":"))).done(false).build(),
                SseChunk.builder().id("c").model("gpt-4o")
                        .toolCalls(List.of(ToolCallDelta.arguments(0, "\"Paris\"}"))).done(false).build(),
                SseChunk.builder().id("c").model("gpt-4o").finishReason("tool_calls").done(true).build()).iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true,
                                 "messages": [{"role": "user", "content": "weather?"}],
                                 "tools": [{"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object"}}}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // the opener: index, id, type and name, with arguments "" so an SDK can concatenate onto it
        assertThat(body).contains("\"tool_calls\":[{\"index\":0,\"id\":\"call_abc\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"\"}}]");
        // continuations: index and the slice, nothing else
        assertThat(body).contains("\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"city\\\":\"}}]");
        assertThat(body).contains("\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"Paris\\\"}\"}}]");
        assertThat(body).contains("\"finish_reason\":\"tool_calls\"").contains("[DONE]");
        verify(requestPipeline).preDispatch(any(), any());
        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).streamChat(sent.capture());
        assertThat(sent.getValue().getTools()).as("the tools travel upstream").hasSize(1);
        assertThat(sent.getValue().isStream()).isTrue();
    }

    /** A stream that returns only a tool call and no usage block is still metered: the call is output. */
    @Test
    void streaming_toolOnlyStream_persistsAnEstimatedUsageRow() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c").model("gpt-4o")
                        .toolCalls(List.of(ToolCallDelta.open(0, "call_abc", "get_weather", "{\"city\":\"Paris\"}"))).done(false).build(),
                SseChunk.builder().id("c").model("gpt-4o").finishReason("tool_calls").done(true).build()).iterator());
        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "weather?"}],
                                 "tools": [{"type": "function", "function": {"name": "get_weather", "parameters": {"type": "object"}}}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvcResult.getAsyncResult();

        org.mockito.ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> row =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository).save(row.capture());
        assertThat(row.getValue().isEstimated()).as("no usage block: estimated, and said so").isTrue();
        assertThat(row.getValue().getOutputTokens()).as("the call's name and arguments count as output").isPositive();
    }

    /** An empty tools list is not a tool request; the stream proceeds. */
    @Test
    void streaming_withEmptyTools_proceeds() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c").model("gpt-4o").delta("Hi").finishReason("stop").done(true).build()).iterator());
        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}], "tools": []}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvcResult.getAsyncResult();   // let the stream finish inside this test, not the next one
        verify(dispatcher).streamChat(any());
    }

    /** The usage on the terminal chunk is what the row is billed on, so a stream that carries one is not estimated. */
    @Test
    void streaming_billsOnTheTerminalChunksUsage_notAnEstimate() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("c").model("gpt-4o").finishReason("stop").done(true)
                        .usage(ChatResponse.Usage.builder().promptTokens(11).completionTokens(3).totalTokens(14).build())
                        .build()).iterator());
        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvcResult.getAsyncResult();

        org.mockito.ArgumentCaptor<com.dvarahq.core.metering.TokenUsageRecord> row =
                org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.metering.TokenUsageRecord.class);
        verify(tokenUsageRepository).save(row.capture());
        assertThat(row.getValue().isEstimated()).as("exact: the upstream said so").isFalse();
        assertThat(row.getValue().getTotalTokens()).isEqualTo(14);
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

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        // The finally block runs on the virtual thread after the emitter completes.
        assertThat(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the controller must close the guard so cancellation can be finalized")
                .isTrue();
    }

    // The guard is built AFTER the provider stream is open. If building it throws, the raw
    // iterator never reaches the emit loop, so openStream itself must release it.
    @Test
    void streaming_closesTheProviderIteratorWhenTheGuardCannotBeBuilt() throws Exception {
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        class ClosableProvider implements java.util.Iterator<SseChunk>, AutoCloseable {
            @Override public boolean hasNext() { return false; }
            @Override public SseChunk next() { throw new java.util.NoSuchElementException(); }
            @Override public void close() { closed.countDown(); }
        }
        when(dispatcher.streamChat(any())).thenReturn(new ClosableProvider());
        when(streamingResponseEnforcer.wrap(any(), any(), any()))
                .thenThrow(new IllegalStateException("workspace settings store unreachable"));

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult));

        assertThat(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                .as("the provider response must be released when the guard cannot be built")
                .isTrue();
    }

    @Test
    void streaming_errorFromProvider_completesWithError() throws Exception {
        when(dispatcher.streamChat(any()))
                .thenThrow(new GatewayException("PROVIDER_ERROR", "OpenAI streaming error 500"));

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        // PROVIDER_ERROR maps to 502 via GlobalExceptionHandler
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isBadGateway());
    }

    // -------------------------------------------------------------------------
    // streaming chat persists token_usage_records (estimated=true)
    // -------------------------------------------------------------------------

    @Test
    void streaming_persistsTokenUsageRecordOnCompletion() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("Hello").done(false).build(),
                SseChunk.builder().id("c1").model("gpt-4o").delta(" streaming world").finishReason("stop").done(true).build()
        );
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi from stream test"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        // Persistence runs in the virtual thread's finally block. With MockMvc's asyncDispatch the
        // response is fully materialised before assertions run, but the virtual thread may finish
        // a tick later, so poll briefly.
        verify(tokenUsageRepository, timeout(2000).times(1))
                .save(argThat(record -> record != null
                        && "gpt-4o".equals(record.getModel())
                        && record.isEstimated()
                        && record.getOutputTokens() > 0
                        && record.getInputTokens() > 0
                        && record.getTotalTokens() == record.getInputTokens() + record.getOutputTokens()));
    }

    @Test
    void streaming_notifiesTheUsageListener() throws Exception {
        // The usage listener is how workspace usage totals move; streaming requests count
        // towards them like any other.
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("hello").finishReason("stop").done(true).build()
        );
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        // The listener is notified from persistTokenUsage's tail.
        verify(usageListener, timeout(2000).atLeastOnce()).usageRecorded(any());
    }

    @Test
    void streaming_emptyOutput_skipsPersistence() throws Exception {
        // The provider returned only an empty terminator chunk. A zero-token row has nothing to
        // meter, so none is written.
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta(null).finishReason("stop").done(true).build()
        );
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        // Give the virtual thread a moment, then check that nothing was persisted.
        Thread.sleep(200);
        verify(tokenUsageRepository, never()).save(any());
        verify(usageListener, never()).usageRecorded(any());
    }

    @Test
    void streaming_persistenceFailureDoesNotBreakStream() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("body").finishReason("stop").done(true).build()
        );
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());
        // tokenUsageRepository.save throws; the stream still completes.
        doThrow(new RuntimeException("simulated DB hiccup"))
                .when(tokenUsageRepository).save(any());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        // The stream itself succeeds even when persistence fails.
        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    // Caching
    // -------------------------------------------------------------------------

    @Test
    void cacheHit_returnsCachedResponse_withXCacheHitHeader() throws Exception {
        ChatResponse cached = chatResponse("cached-id", "gpt-4o", "Cached answer");
        when(responseCache.get(any())).thenReturn(Optional.of(cached));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "HIT"))
                .andExpect(jsonPath("$.id").value("cached-id"))
                .andExpect(jsonPath("$.choices[0].message.content").value("Cached answer"));

        verify(dispatcher, never()).chat(any());
        // A cache hit made no upstream call, so it books no cost. The served volume is still
        // recorded with cache_status=HIT so monthly billing counts it.
        verify(costCalculationService, never()).calculateAndPersist(any(), any(), any(), any(), any());
        verify(tokenUsageRepository).save(argThat(r -> "HIT".equals(r.getCacheStatus())));
    }

    @Test
    void cacheMiss_callsDispatcher_withXCacheMissHeader() throws Exception {
        when(responseCache.get(any())).thenReturn(Optional.empty());
        when(dispatcher.chat(any())).thenReturn(chatResponse("new-id", "gpt-4o", "Fresh answer"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"))
                .andExpect(jsonPath("$.id").value("new-id"));

        verify(dispatcher).chat(any());
        verify(responseCache).put(any(), any());
        // A miss made a real upstream call, so it books cost and records the served volume as
        // cache_status=MISS.
        verify(costCalculationService).calculateAndPersist(any(), any(), any(), any(), any());
        verify(tokenUsageRepository).save(argThat(r -> "MISS".equals(r.getCacheStatus())));
    }

    @Test
    void noCacheHeader_bypassesCacheLookup() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("new-id", "gpt-4o", "Fresh"));

        mockMvc.perform(post("/v1/chat/completions")
                        .header("X-Cache-Control", "no-cache")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"));

        verify(responseCache, never()).get(any());
        verify(dispatcher).chat(any());
    }

    @Test
    void cacheKey_isStrippedFormOnBothGetAndPut() throws Exception {
        // The cache key is the PII-stripped request on both get and put. If get used the original
        // request and put the stripped form, requests that differ only in PII would never collapse
        // in LOG mode, and a stored entry could never be looked up.
        //
        // stripForCache is stubbed to return a recognisable request (user content rewritten to
        // "<<<STRIPPED>>>"), and both the get and the put must see that form, not the original "Hi".
        ChatRequest stripped = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock("<<<STRIPPED>>>")))
                        .build()))
                .build();
        when(piiEnforcer.stripForCache(any(ChatRequest.class), any())).thenReturn(stripped);
        when(responseCache.get(any())).thenReturn(Optional.empty());
        when(dispatcher.chat(any())).thenReturn(chatResponse("new-id", "gpt-4o", "Fresh"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Cache", "MISS"));

        ArgumentCaptor<ChatRequest> getKey = ArgumentCaptor.forClass(ChatRequest.class);
        verify(responseCache).get(getKey.capture());
        assertThat(extractText(getKey.getValue()))
                .as("cache.get is called with the PII-stripped request")
                .isEqualTo("<<<STRIPPED>>>");

        ArgumentCaptor<ChatRequest> putKey = ArgumentCaptor.forClass(ChatRequest.class);
        verify(responseCache).put(putKey.capture(), any());
        assertThat(extractText(putKey.getValue()))
                .as("the cache.put key matches the cache.get key, or the stored entry can never be looked up")
                .isEqualTo("<<<STRIPPED>>>");

        // The strip is computed once per request, not separately for get and put.
        verify(piiEnforcer, times(1)).stripForCache(any(ChatRequest.class), any());
    }

    private static String extractText(ChatRequest req) {
        var blocks = req.getMessages().get(0).getContent();
        return ((ContentBlock.TextBlock) blocks.get(0)).text();
    }

    @Test
    void streaming_bypassesCache() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta(null).finishReason("stop").done(true).build()
        );
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk());

        verify(responseCache, never()).get(any());
        verify(responseCache, never()).put(any(), any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static String validBody() {
        return """
                {"model": "gpt-4o", "messages": [{"role": "user", "content": "Hi"}]}
                """;
    }

    // -------------------------------------------------------------------------
    // response_format parsing & validation
    // -------------------------------------------------------------------------

    @Test
    void responseFormat_jsonObject_isAccepted() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("rf-1", "gpt-4o", "{\"ok\":true}"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o",
                                  "messages": [{"role": "user", "content": "Return JSON"}],
                                  "response_format": {"type": "json_object"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("rf-1"));
    }

    @Test
    void responseFormat_jsonSchema_isAccepted() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("rf-2", "gpt-4o", "{\"name\":\"John\"}"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o",
                                  "messages": [{"role": "user", "content": "Return structured"}],
                                  "response_format": {
                                    "type": "json_schema",
                                    "json_schema": {
                                      "name": "my_schema",
                                      "schema": {"type": "object", "properties": {"name": {"type": "string"}}},
                                      "strict": true
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("rf-2"));
    }

    @Test
    void responseFormat_unknownType_returns400() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o",
                                  "messages": [{"role": "user", "content": "Hi"}],
                                  "response_format": {"type": "xml"}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void responseFormat_missingType_returns400() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o",
                                  "messages": [{"role": "user", "content": "Hi"}],
                                  "response_format": {"foo": "bar"}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void responseFormat_jsonSchemaMissingSchema_returns400() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o",
                                  "messages": [{"role": "user", "content": "Hi"}],
                                  "response_format": {
                                    "type": "json_schema",
                                    "json_schema": {"name": "test"}
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void gatewayHeaders_forwardedToHttpResponse() throws Exception {
        ChatResponse resp = ChatResponse.builder()
                .id("gh-1").model("claude-sonnet-4-5").object("chat.completion").created(1704067200L)
                .choices(List.of(ChatResponse.Choice.builder()
                        .index(0).message(MultimodalMessage.assistant("ok")).finishReason("stop").build()))
                .usage(ChatResponse.Usage.builder().promptTokens(10).completionTokens(3).totalTokens(13).build())
                .gatewayHeaders(Map.of("X-Gateway-Strict-Downgraded", "true"))
                .build();
        when(dispatcher.chat(any())).thenReturn(resp);

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Gateway-Strict-Downgraded", "true"));
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

    // -------------------------------------------------------------------------
    // Capability-aware routing errors
    // -------------------------------------------------------------------------

    @Test
    void chatCompletions_noCapableProvider_returns400() throws Exception {
        when(dispatcher.chat(any())).thenThrow(
                new GatewayException("NO_CAPABLE_PROVIDER",
                        "No provider supports response_format: json_schema"));

        String body = """
                {
                  "model": "gpt-4o",
                  "messages": [{"role": "user", "content": "hi"}],
                  "response_format": {"type": "json_schema", "json_schema": {"name": "test", "schema": {"type": "object"}}}
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("no_capable_provider"))
                .andExpect(jsonPath("$.error.type").value("invalid_request_error"));
    }

    @Test
    void chatCompletions_failoverCapabilityMismatch_returns503WithHeader() throws Exception {
        when(dispatcher.chat(any())).thenThrow(
                new GatewayException("FAILOVER_CAPABILITY_MISMATCH",
                        "Failover blocked: no fallback provider supports response_format: json_schema"));

        String body = """
                {
                  "model": "gpt-4o",
                  "messages": [{"role": "user", "content": "hi"}],
                  "response_format": {"type": "json_schema", "json_schema": {"name": "test", "schema": {"type": "object"}}}
                }
                """;

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Gateway-Failover-Blocked", "capability_mismatch"))
                .andExpect(jsonPath("$.error.code").value("failover_capability_mismatch"))
                .andExpect(jsonPath("$.error.type").value("provider_unavailable"));
    }

    // -------------------------------------------------------------------------
    // Policy enforcement (via pipeline)
    // -------------------------------------------------------------------------

    @Test
    void policyDenied_returns403() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("POLICY_DENIED", "Model blocked by policy"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("policy_violation"))
                .andExpect(jsonPath("$.error.code").value("policy_denied"))
                .andExpect(jsonPath("$.error.message").value("Model blocked by policy"));

        verify(dispatcher, never()).chat(any());
    }

    @Test
    void policyAllowed_proceedsToDispatcher() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("id"));

        verify(dispatcher).chat(any());
    }

    @Test
    void policyDenied_streaming_returns403() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("POLICY_DENIED", "Streaming blocked by policy"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("policy_violation"));

        verify(dispatcher, never()).streamChat(any());
    }

    @Test
    void policyDenied_auditsEvent() throws Exception {
        // Policy denial audit is the PolicyEnforcementFilter's job inside the pipeline; here the
        // pipeline throws and the controller turns that into the error response.
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("POLICY_DENIED", "Blocked"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    // Response headers contributed by the filter pipeline
    // -------------------------------------------------------------------------

    @Test
    void aHeaderAFilterAskedFor_reachesTheWire() throws Exception {
        // The controller does not know what a budget is. A filter names a header and a value on
        // the context, and the controller's whole job is to copy that pair onto the response.
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenAnswer(inv -> {
                    FilterContext ctx = inv.getArgument(1);
                    ctx.setResponseHeader("X-Budget-Remaining-Pct", "75");
                    ctx.setResponseHeader("X-Budget-Remaining-Tokens", "500000");
                    return inv.getArgument(0);
                });
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Budget-Remaining-Pct", "75"))
                .andExpect(header().string("X-Budget-Remaining-Tokens", "500000"));
    }

    @Test
    void noHeaderIsInvented_whenNoFilterAskedForOne() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Budget-Remaining-Pct"))
                .andExpect(header().doesNotExist("X-Budget-Remaining-Tokens"));
    }

    @Test
    void budgetWarningHeader_present_whenPolicyWarns() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenAnswer(inv -> {
                    FilterContext ctx = inv.getArgument(1);
                    ctx.setPolicyDecision(PolicyDecision.allowWithWarnings(List.of(
                            new com.dvarahq.core.policy.PolicyWarning("WARN_AGENT", "Budget at 80%", "p-1", "r-1"))));
                    return inv.getArgument(0);
                });
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Budget-Warning", "true"));
    }

    // -------------------------------------------------------------------------
    // Priority admission control (via pipeline)
    // -------------------------------------------------------------------------

    @Test
    void priorityThrottled_returns429() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("PRIORITY_THROTTLED",
                        "Load at 55% exceeds BULK threshold 50%"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("priority_throttle_error"))
                .andExpect(jsonPath("$.error.code").value("priority_throttled"));

        verify(dispatcher, never()).chat(any());
    }

    @Test
    void priorityThrottled_streaming_returns429() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("PRIORITY_THROTTLED",
                        "Load at 55% exceeds BULK threshold 50%"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.type").value("priority_throttle_error"));

        verify(dispatcher, never()).streamChat(any());
    }

    // -------------------------------------------------------------------------
    // Context window warning headers
    // -------------------------------------------------------------------------

    @Test
    void contextWindowWarning_headersPresentWhenFilterSetsAttribute() throws Exception {
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenAnswer(inv -> {
                    FilterContext ctx = inv.getArgument(1);
                    ctx.setAttribute("context.warning", true);
                    ctx.setAttribute("context.utilization", 75);
                    return inv.getArgument(0);
                });
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Context-Window-Warning", "true"))
                .andExpect(header().string("X-Context-Window-Utilization", "75%"));
    }

    @Test
    void contextWindowWarning_headersAbsentWhenNoWarning() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("X-Context-Window-Warning"))
                .andExpect(header().doesNotExist("X-Context-Window-Utilization"));
    }

    // Grounding detection, which GroundingDetectionFilter does inside the pipeline

    @Test
    void grounding_blockAction_returnsError() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ungrounded"));
        when(requestPipeline.postDispatch(any(ChatRequest.class), any(ChatResponse.class), any(FilterContext.class)))
                .thenThrow(new GatewayException("HALLUCINATION_DETECTED",
                        "Response contains ungrounded claims: Ungrounded claim here"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"test"}],
                                 "metadata":{"grounding.sources":["source document text here"]}}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.type").value("guardrail_violation"))
                .andExpect(jsonPath("$.error.code").value("hallucination_detected"));
    }

    @Test
    void grounding_disabled_skipsCheck() throws Exception {
        // Grounding is a filter's job, so a pass-through pipeline means no grounding check.
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model":"gpt-4o","messages":[{"role":"user","content":"test"}],
                                 "metadata":{"grounding.sources":["source doc"]}}
                                """))
                .andExpect(status().isOk());
    }

    // injectResolvedWorkspace: the server-resolved workspace is what canary routing sees

    @Test
    void injectResolvedWorkspace_nullMetadata_createsFreshMapWithWorkspace() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        ChatCompletionController.injectResolvedWorkspace(request, "acme-corp");

        assertThat(request.getMetadata()).isNotNull();
        assertThat(request.getMetadata()).containsEntry("workspace_id", "acme-corp");
    }

    @Test
    void injectResolvedWorkspace_existingMetadata_preservesOtherKeysAndAddsWorkspace() {
        java.util.Map<String, Object> existing = new java.util.HashMap<>();
        existing.put("session_id", "session-1");
        existing.put("grounding.sources", java.util.List.of("doc"));
        ChatRequest request = ChatRequest.builder().model("gpt-4o").metadata(existing).build();

        ChatCompletionController.injectResolvedWorkspace(request, "acme-corp");

        assertThat(request.getMetadata())
                .containsEntry("workspace_id", "acme-corp")
                .containsEntry("session_id", "session-1")
                .containsEntry("grounding.sources", java.util.List.of("doc"));
    }

    @Test
    void injectResolvedWorkspace_clientSuppliedWorkspaceId_isOverwrittenByServerResolved() {
        // A malicious client claims to be workspace "acme-eu" in the request body to slip into
        // acme-eu's canary split. The workspace resolved from the API key is "attacker-corp", and
        // the injection overwrites whatever the client supplied.
        java.util.Map<String, Object> clientClaimed = new java.util.HashMap<>();
        clientClaimed.put("workspace_id", "acme-eu");
        ChatRequest request = ChatRequest.builder().model("gpt-4o").metadata(clientClaimed).build();

        ChatCompletionController.injectResolvedWorkspace(request, "attacker-corp");

        assertThat(request.getMetadata()).containsEntry("workspace_id", "attacker-corp");
    }

    @Test
    void injectResolvedWorkspace_nullWorkspace_leavesAbsentMetadataAbsent() {
        // No API key resolved and nothing in the body: anonymous requests keep working unchanged.
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();

        ChatCompletionController.injectResolvedWorkspace(request, null);

        assertThat(request.getMetadata()).isNull();
    }

    @Test
    void injectResolvedWorkspace_nullWorkspace_removesACallerSuppliedWorkspaceId() {
        // A request with no key names a real workspace in its body. Nothing resolved it, so it must not
        // reach the response cache, canary routing or telemetry, all of which read metadata.workspace_id.
        // The other keys stay, and the body's map may be immutable.
        java.util.Map<String, Object> claimed = java.util.Map.of("workspace_id", "acme-eu", "session_id", "s-1");
        ChatRequest request = ChatRequest.builder().model("gpt-4o").metadata(claimed).build();

        ChatCompletionController.injectResolvedWorkspace(request, null);

        assertThat(request.getMetadata())
                .doesNotContainKey("workspace_id")
                .containsEntry("session_id", "s-1");
    }

    @Test
    void injectResolvedWorkspace_immutableMetadata_copiesBeforeMutating() {
        // The request body deserializer can hand us an immutable map (from Map.of, say). Mutating
        // it directly would throw, so the helper copies into a fresh mutable map first.
        java.util.Map<String, Object> immutable = java.util.Map.of("session_id", "s-1");
        ChatRequest request = ChatRequest.builder().model("gpt-4o").metadata(immutable).build();

        ChatCompletionController.injectResolvedWorkspace(request, "acme-corp");

        assertThat(request.getMetadata())
                .containsEntry("workspace_id", "acme-corp")
                .containsEntry("session_id", "s-1");
        // The original immutable map is untouched.
        assertThat(immutable).doesNotContainKey("workspace_id");
    }

    // The sync chat path injects the resolved workspace before handing off to the dispatcher.
    // The unit tests above check the helper; this one checks that the controller calls it.

    @Test
    void chat_resolvedWorkspaceReachesDispatcher_overwritingClientSuppliedMetadata() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .requestAttr("workspaceId", "workspace-from-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o",
                                  "messages": [{"role": "user", "content": "hi"}],
                                  "metadata": {"workspace_id": "attacker-claimed", "session_id": "s-42"}
                                }
                                """))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ChatRequest> captor = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).chat(captor.capture());
        assertThat(captor.getValue().getMetadata())
                .containsEntry("workspace_id", "workspace-from-api-key")
                .containsEntry("session_id", "s-42");
    }

    @Test
    void streaming_resolvedWorkspaceReachesDispatcher_overwritingClientSuppliedMetadata() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta("hi").finishReason("stop").done(true).build()
        );
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .requestAttr("workspaceId", "workspace-from-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o",
                                  "stream": true,
                                  "messages": [{"role": "user", "content": "hi"}],
                                  "metadata": {"workspace_id": "attacker-claimed"}
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk());

        org.mockito.ArgumentCaptor<ChatRequest> captor = org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).streamChat(captor.capture());
        assertThat(captor.getValue().getMetadata())
                .containsEntry("workspace_id", "workspace-from-api-key");
    }

    // ================== function-calling round-trip ==================

    @Test
    void tools_roundTrip_mapsDefinitionsInAndToolCallsOut() throws Exception {
        ChatResponse withToolCall = ChatResponse.builder()
                .id("c1").model("gpt-4o").object("chat.completion").created(1L)
                .choices(List.of(ChatResponse.Choice.builder()
                        .index(0)
                        .message(MultimodalMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentBlock.TextBlock("")))
                                .toolCalls(List.of(com.dvarahq.core.model.ToolCall.builder()
                                        .id("call_1").name("get_weather").arguments("{\"city\":\"Paris\"}").build()))
                                .build())
                        .finishReason("tool_calls")
                        .build()))
                .usage(ChatResponse.Usage.builder().build())
                .build();

        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        when(dispatcher.chat(captor.capture())).thenReturn(withToolCall);

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "model": "gpt-4o",
                                  "messages": [
                                    {"role": "user", "content": "weather in Paris?"},
                                    {"role": "assistant", "content": null, "tool_calls": [
                                      {"id": "call_1", "type": "function", "function": {"name": "get_weather", "arguments": "{}"}}]},
                                    {"role": "tool", "tool_call_id": "call_1", "content": "sunny"}
                                  ],
                                  "tools": [{"type": "function", "function": {"name": "get_weather", "description": "Get weather", "parameters": {"type": "object"}}}],
                                  "tool_choice": "auto"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].id").value("call_1"))
                .andExpect(jsonPath("$.choices[0].message.tool_calls[0].function.name").value("get_weather"))
                .andExpect(jsonPath("$.choices[0].finish_reason").value("tool_calls"));

        // tools and tool_choice reached the dispatcher, and the assistant tool_calls and the tool
        // result's id survived the DTO-to-internal translation.
        ChatRequest captured = captor.getValue();
        assertThat(captured.getTools()).hasSize(1);
        assertThat(captured.getTools().get(0).getName()).isEqualTo("get_weather");
        assertThat(captured.getToolChoice()).isEqualTo("auto");
        assertThat(captured.getMessages().get(1).getToolCalls()).hasSize(1);
        assertThat(captured.getMessages().get(2).getRole()).isEqualTo("tool");
        assertThat(captured.getMessages().get(2).getToolCallId()).isEqualTo("call_1");
    }

    @Test
    void nOtherThanOne_isRefusedRatherThanAnsweredWithOneChoice() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "n": 3, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("unsupported_capability"));
        verify(dispatcher, never()).chat(any());
    }

    @Test
    void penaltiesAndMessageName_reachTheInternalRequest_andUserIsIgnored() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("chatcmpl-abc", "gpt-4o", "Paris"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "n": 1, "user": "u-42",
                                 "frequency_penalty": 0.5, "presence_penalty": -0.25,
                                 "messages": [{"role": "user", "content": "Hi", "name": "alice"}]}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(requestPipeline).preDispatch(sent.capture(), any(FilterContext.class));
        assertThat(sent.getValue().getFrequencyPenalty()).isEqualTo(0.5);
        assertThat(sent.getValue().getPresencePenalty()).isEqualTo(-0.25);
        assertThat(sent.getValue().getMessages().get(0).getName()).isEqualTo("alice");
    }

    /** A stream that ends before its finish is incomplete, so it is not closed with [DONE] as if it were whole. */
    @Test
    void streaming_aStreamThatEndsBeforeItsFinishIsNotServedAsComplete() throws Exception {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("chunk-1").model("gpt-4o").delta("Hello wor").done(false).build());
        when(dispatcher.streamChat(any())).thenReturn(chunks.iterator());

        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(mvcResult)).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("Hello wor");
        assertThat(body).doesNotContain("[DONE]");
    }

    // ================== content arrays and request fields ==================

    // A content array must reach the provider as text and image blocks, not as its Java map text.
    @Test
    void contentArray_textAndImagePartsBecomeTextAndImageBlocks() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "messages": [{"role": "user", "content": [
                                  {"type": "text", "text": "What is in this image?"},
                                  {"type": "image_url", "image_url": {"url": "data:image/png;base64,iVBORw0KGgo"}}]}]}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).chat(sent.capture());
        assertThat(sent.getValue().getMessages().getFirst().getContent()).containsExactly(
                new ContentBlock.TextBlock("What is in this image?"),
                new ContentBlock.ImageBlock("image/png", "iVBORw0KGgo"));
    }

    @Test
    void contentArray_anUnknownPartTypeIsRefused() throws Exception {
        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "messages": [{"role": "user", "content": [{"type": "video", "url": "x"}]}]}
                                """))
                .andExpect(status().isBadRequest());
        verify(dispatcher, org.mockito.Mockito.never()).chat(any());
    }

    @Test
    void stopAndSeed_travelUpstream() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "messages": [{"role": "user", "content": "Hi"}],
                                 "stop": ["END", "STOP"], "seed": 42}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).chat(sent.capture());
        assertThat(sent.getValue().getStop()).containsExactly("END", "STOP");
        assertThat(sent.getValue().getSeed()).isEqualTo(42L);
    }

    @Test
    void aSingleStopStringIsOneSequence() throws Exception {
        when(dispatcher.chat(any())).thenReturn(chatResponse("id", "gpt-4o", "ok"));

        mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "messages": [{"role": "user", "content": "Hi"}], "stop": "END"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ChatRequest> sent = ArgumentCaptor.forClass(ChatRequest.class);
        verify(dispatcher).chat(sent.capture());
        assertThat(sent.getValue().getStop()).containsExactly("END");
    }

    @Test
    void fieldsTheGatewayCannotHonourAreRefusedNotDropped() throws Exception {
        for (String extra : List.of("\"logprobs\": true", "\"top_logprobs\": 2", "\"parallel_tool_calls\": false")) {
            mockMvc.perform(post("/v1/chat/completions")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"model\": \"gpt-4o\", \"messages\": [{\"role\": \"user\", \"content\": \"Hi\"}], "
                                    + extra + "}"))
                    .andExpect(status().isBadRequest());
        }
        verify(dispatcher, org.mockito.Mockito.never()).chat(any());
    }

    @Test
    void streaming_includeUsage_sendsTheUsageChunkBeforeDone() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("c").model("gpt-4o").finishReason("stop").done(true)
                        .usage(ChatResponse.Usage.builder().promptTokens(11).completionTokens(3).totalTokens(14).build())
                        .build()).iterator());
        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "stream_options": {"include_usage": true},
                                 "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"choices\":[]").contains("\"usage\"").contains("14");
        assertThat(body.indexOf("\"usage\"")).isLessThan(body.indexOf("[DONE]"));
    }

    @Test
    void streaming_withoutIncludeUsage_sendsNoUsageChunk() throws Exception {
        when(dispatcher.streamChat(any())).thenReturn(List.of(
                SseChunk.builder().id("c").model("gpt-4o").finishReason("stop").delta("Hi").done(true)
                        .usage(ChatResponse.Usage.builder().promptTokens(11).completionTokens(3).totalTokens(14).build())
                        .build()).iterator());
        MvcResult mvcResult = mockMvc.perform(post("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mockMvc.perform(asyncDispatch(mvcResult)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"usage\"");
    }
}
