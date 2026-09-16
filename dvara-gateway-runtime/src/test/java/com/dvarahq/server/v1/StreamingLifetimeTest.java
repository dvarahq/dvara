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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.server.config.TestMetricsConfig;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.server.service.ProviderDispatcher;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The stream's lifetime is the global {@code resilience.timeout.streaming-timeout-ms}, on both
 * doorways, so an operator who sets it changes how long a stream may stay open.
 */
@WebMvcTest({ChatCompletionController.class, ResponsesController.class})
@Import(TestMetricsConfig.class)
@TestPropertySource(properties = {
        "dvara.llm-gateway.resilience.timeout.streaming-timeout-ms=4321",
        "dvara.llm-gateway.resilience.providers.openai.timeout.streaming-timeout-ms=99999"})
class StreamingLifetimeTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ProviderDispatcher dispatcher;
    @MockitoBean RequestPipeline requestPipeline;
    @MockitoBean ResponseCache responseCache;
    @MockitoBean TokenUsageRepository tokenUsageRepository;
    @MockitoBean CostCalculationService costCalculationService;
    @MockitoBean CostEstimator costEstimator;
    @MockitoBean PiiEnforcer piiEnforcer;
    @MockitoBean RateLimiter rateLimiter;
    @MockitoBean StreamingResponseEnforcer streamingResponseEnforcer;
    @MockitoBean AuditWriter auditWriter;
    @MockitoBean PriorityAdmissionController priorityAdmissionController;
    @MockitoBean WorkspaceUsageListener usageListener;
    @MockitoBean com.dvarahq.core.metering.CallOutcomeListener outcomeListener;

    private static final String CHAT = """
            {"model": "gpt-4o", "stream": true, "messages": [{"role": "user", "content": "Hi"}]}
            """;
    private static final String RESPONSES = """
            {"model": "gpt-4o", "stream": true, "input": "Hi"}
            """;

    /** An upstream that parks in hasNext() until closed, so the emit thread is still running when the timeout is read. */
    static final class Stalled implements Iterator<SseChunk>, AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        @Override public boolean hasNext() {
            entered.countDown();
            try { released.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return false;
        }
        @Override public SseChunk next() { throw new IllegalStateException(); }
        @Override public void close() { closed.set(true); released.countDown(); }
    }

    private void commonStubs(Iterator<SseChunk> upstream) {
        when(requestPipeline.preDispatch(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(streamingResponseEnforcer.wrap(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(streamingResponseEnforcer.wrap(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        when(responseCache.get(any())).thenReturn(java.util.Optional.empty());
        when(dispatcher.streamChat(any())).thenReturn(upstream);
    }

    @Test
    void chat_theEmitterLifetimeIsTheProperty() throws Exception {
        Stalled upstream = new Stalled();
        commonStubs(upstream);
        MvcResult result = mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(result.getRequest().getAsyncContext().getTimeout()).isEqualTo(4321L);
        upstream.close();   // let the emit thread finish inside this test
    }

    @Test
    void responses_theEmitterLifetimeIsTheProperty() throws Exception {
        Stalled upstream = new Stalled();
        commonStubs(upstream);
        MvcResult result = mockMvc.perform(post("/v1/responses").contentType(MediaType.APPLICATION_JSON).content(RESPONSES))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(result.getRequest().getAsyncContext().getTimeout()).isEqualTo(4321L);
        upstream.close();
    }

    /**
     * A provider's own streaming timeout bounds the resilience layer's open wait, not the emitter:
     * the emitter is created before a provider is chosen, so its lifetime is always the global value.
     */
    @Test
    void aProviderOverride_doesNotReachTheEmitterLifetime() throws Exception {
        Stalled upstream = new Stalled();
        commonStubs(upstream);
        MvcResult result = mockMvc.perform(post("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON).content(CHAT))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(result.getRequest().getAsyncContext().getTimeout()).as("global, not the openai override").isEqualTo(4321L);
        upstream.close();
    }


    // --- the container's timeout releases the transport of an upstream the emit thread is parked on ---

    /** A provider-shaped upstream: parks in hasNext() until its transport is released; close() is the emit thread's. */
    static final class ParkedReleasable implements Iterator<SseChunk>, AutoCloseable, com.dvarahq.core.model.ReleasableUpstream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        final AtomicReference<Thread> closedBy = new AtomicReference<>();
        final CountDownLatch closed = new CountDownLatch(1);
        @Override public boolean hasNext() {
            entered.countDown();
            try { released.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return false;
        }
        @Override public SseChunk next() { throw new IllegalStateException(); }
        @Override public void releaseTransport() { released.countDown(); }
        @Override public void close() { closedBy.set(Thread.currentThread()); closed.countDown(); }
    }

    @Test
    void chat_aTimeoutReleasesTheParkedUpstream_withoutClosingIt() throws Exception {
        assertTimeoutReleases("/v1/chat/completions", CHAT);
    }

    @Test
    void responses_aTimeoutReleasesTheParkedUpstream_withoutClosingIt() throws Exception {
        assertTimeoutReleases("/v1/responses", RESPONSES);
    }

    private void assertTimeoutReleases(String path, String body) throws Exception {
        ParkedReleasable upstream = new ParkedReleasable();
        commonStubs(upstream);
        MvcResult result = mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        assertThat(upstream.entered.await(5, TimeUnit.SECONDS)).as("the read is parked").isTrue();
        // Fire the container's timeout the way Tomcat would: through the registered async listeners.
        org.springframework.mock.web.MockAsyncContext ctx = (org.springframework.mock.web.MockAsyncContext) result.getRequest().getAsyncContext();
        for (jakarta.servlet.AsyncListener listener : ctx.getListeners()) {
            listener.onTimeout(new jakarta.servlet.AsyncEvent(ctx));
        }
        assertThat(upstream.released.await(5, TimeUnit.SECONDS)).as("the transport was released").isTrue();
        // The emit thread, released, runs its tail and closes the iterator — that is its job, on its
        // thread. What must not happen is a close from the thread that fired the timeout (this one).
        assertThat(upstream.closed.await(5, TimeUnit.SECONDS)).as("the emit thread finished and closed the iterator").isTrue();
        assertThat(upstream.closedBy.get()).as("closed by the emit thread, never by the timeout's").isNotSameAs(Thread.currentThread());
    }

}
