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
import com.dvarahq.core.filter.StreamStopCheck;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.metering.TokenUsageRecord;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.server.TestApiKey;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.web.AccessLogFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * A registered {@link StreamStopCheck} ends a stream that is already running, on each streaming doorway: the
 * chunk in hand is not written, the client gets one error event in its API's shape, the upstream is closed,
 * the call is recorded under the stop's code, and what was sent is still metered.
 */
@WebMvcTest({ChatCompletionController.class, ResponsesController.class, MessagesController.class})
@Import(TestMetricsConfig.class)
class StreamStopCheckTest {

    private static final StreamStopCheck.Stop KILLED =
            new StreamStopCheck.Stop(403, "session_killed", "session_killed", "The session was ended.");

    @Autowired MockMvc mockMvc;

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
    @MockitoBean StreamStopCheck stopCheck;

    /** Three text chunks and the finish; closing it is what releases the provider connection. */
    private final Upstream upstream = new Upstream();

    @BeforeEach
    void setUp() {
        when(responseCache.get(any())).thenReturn(Optional.empty());
        when(requestPipeline.preDispatch(any(ChatRequest.class), any(FilterContext.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(requestPipeline.postDispatch(any(ChatRequest.class), any(ChatResponse.class), any(FilterContext.class)))
                .thenAnswer(inv -> inv.getArgument(1));
        when(streamingResponseEnforcer.wrap(any(), any(), any())).thenAnswer(i -> i.getArgument(0));
        when(dispatcher.streamChat(any())).thenReturn(upstream);
        // Lets the first chunk through and stops before the second is written.
        when(stopCheck.check(any())).thenReturn(Optional.empty(), Optional.of(KILLED));
    }

    @Test
    void chatCompletions_endsWithOneErrorEvent_andNoDone() throws Exception {
        MvcResult result = stream(post("/v1/chat/completions").content("""
                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                """));
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("first").doesNotContain("second").doesNotContain("third");
        assertThat(body).contains("data:{\"error\":{\"message\":\"The session was ended.\",\"type\":\"session_killed\","
                + "\"code\":\"session_killed\"");
        assertThat(body).doesNotContain("[DONE]");
        assertRecordedAsStopped(result);
    }

    @Test
    void responses_endsWithResponseFailed_carryingTheStopsCode() throws Exception {
        MvcResult result = stream(post("/v1/responses").content("""
                {"model": "gpt-4o", "stream": true, "input": "Hi"}
                """));
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("first").doesNotContain("second");
        assertThat(body).contains("event:response.failed").contains("\"code\":\"session_killed\"");
        assertThat(body).doesNotContain("response.completed").doesNotContain("provider_error");
        assertRecordedAsStopped(result);
    }

    @Test
    void messages_endsWithAnAnthropicErrorEvent_typedByTheStatus() throws Exception {
        MvcResult result = stream(post("/v1/messages").header("anthropic-version", "2023-06-01").content("""
                {"model": "claude-sonnet-4-5", "max_tokens": 100, "stream": true,
                 "messages": [{"role": "user", "content": "Hi"}]}
                """));
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("first").doesNotContain("second");
        assertThat(body).contains("event:error").contains("\"type\":\"permission_error\"")
                .contains("\"code\":\"session_killed\"");
        assertThat(body).doesNotContain("message_stop");
        assertRecordedAsStopped(result);
    }

    @Test
    void theCheckSeesWhoTheStreamBelongsTo() throws Exception {
        stream(post("/v1/chat/completions").header("X-Session-Id", "agent-run-7").content("""
                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                """));

        ArgumentCaptor<StreamStopCheck.RunningStream> seen = ArgumentCaptor.forClass(StreamStopCheck.RunningStream.class);
        verify(stopCheck, atLeastOnce()).check(seen.capture());
        StreamStopCheck.RunningStream stream = seen.getValue();
        assertThat(stream.workspaceId()).isEqualTo(TestApiKey.WORKSPACE);
        assertThat(stream.apiKeyId()).isEqualTo(TestApiKey.ID);
        assertThat(stream.sessionId()).isEqualTo("agent-run-7");
        assertThat(stream.model()).isEqualTo("gpt-4o");
        assertThat(stream.path()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void aCheckThatNeverStops_leavesTheStreamWhole() throws Exception {
        when(stopCheck.check(any())).thenReturn(Optional.empty());

        MvcResult result = stream(post("/v1/chat/completions").content("""
                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                """));
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("first").contains("second").contains("third").contains("[DONE]");
        assertThat(body).doesNotContain("\"error\"");
        assertThat(result.getRequest().getAttribute(AccessLogFilter.ATTR_ERROR_CODE)).isNull();
        verify(stopCheck, times(4)).check(any());   // once per chunk
    }

    @Test
    void aCheckThatThrows_neverEndsTheStream_andIsNotAskedAgain() throws Exception {
        when(stopCheck.check(any())).thenThrow(new IllegalStateException("index unavailable"));

        MvcResult result = stream(post("/v1/chat/completions").content("""
                {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
                """));
        String body = result.getResponse().getContentAsString();

        assertThat(body).contains("first").contains("third").contains("[DONE]");
        assertThat(result.getRequest().getAttribute(AccessLogFilter.ATTR_ERROR_CODE)).isNull();
        verify(stopCheck, times(1)).check(any());
    }

    // -------------------------------------------------------------------------

    private MvcResult stream(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult started = mockMvc.perform(builder.contentType(MediaType.APPLICATION_JSON))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started)).andReturn();
    }

    /** The upstream is closed, the call carries the stop's code, and the delivered part is metered. */
    private void assertRecordedAsStopped(MvcResult result) {
        assertThat(upstream.closed.get()).as("the provider connection is released").isTrue();
        assertThat(upstream.read).as("nothing is read after the stop").isEqualTo(2);
        assertThat(result.getRequest().getAttribute(AccessLogFilter.ATTR_ERROR_CODE)).isEqualTo("session_killed");
        ArgumentCaptor<TokenUsageRecord> row = ArgumentCaptor.forClass(TokenUsageRecord.class);
        verify(tokenUsageRepository).save(row.capture());
        assertThat(row.getValue().getOutputTokens()).as("what was sent is billed").isPositive();
        assertThat(row.getValue().isEstimated()).isTrue();
    }

    static final class Upstream implements Iterator<SseChunk>, AutoCloseable {
        private final List<SseChunk> chunks = List.of(
                SseChunk.builder().id("c1").model("gpt-4o").delta("first ").done(false).build(),
                SseChunk.builder().id("c1").model("gpt-4o").delta("second ").done(false).build(),
                SseChunk.builder().id("c1").model("gpt-4o").delta("third").done(false).build(),
                SseChunk.builder().id("c1").model("gpt-4o").finishReason("stop").done(true).build());
        final AtomicBoolean closed = new AtomicBoolean();
        int read;

        @Override public boolean hasNext() { return read < chunks.size(); }
        @Override public SseChunk next() { return chunks.get(read++); }
        @Override public void close() { closed.set(true); }
    }
}
