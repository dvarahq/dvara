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

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The trace filter runs before the server span exists. {@link SpanTraceIdFilter} runs inside it and gives the
 * request the span's trace id, unless the caller named its own.
 */
class SpanTraceIdFilterTest {

    private static final String SPAN_TRACE = "4bf92f3577b34da6a3ce929d0e0e4736";

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static Tracer tracerWithSpan(String traceId) {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(traceId);
        return tracer;
    }

    /**
     * The trace filter, then (where the server span opens) the span filter, then {@code inner}. Returns the
     * trace id the MDC held inside the chain.
     */
    private static String runBoth(Tracer tracer, MockHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        AtomicReference<String> inMdc = new AtomicReference<>();
        FilterChain inner = (req, res) -> inMdc.set(MDC.get(TraceIdFilter.MDC_TRACE_ID));
        FilterChain throughSpanFilter = (req, res) -> new SpanTraceIdFilter(tracer).doFilter(req, res, inner);
        new TraceIdFilter().doFilter(request, response, throughSpanFilter);
        return inMdc.get();
    }

    @Test
    void theSpansTraceIdReplacesTheMintedOneOnTheRequestTheResponseAndTheLogContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String inMdc = runBoth(tracerWithSpan(SPAN_TRACE), request, response);

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(SPAN_TRACE);
        assertThat(request.getAttribute(TraceIdFilter.ATTR)).isEqualTo(SPAN_TRACE);
        assertThat(inMdc).isEqualTo(SPAN_TRACE);
        assertThat(MDC.get(TraceIdFilter.MDC_TRACE_ID)).as("the trace filter still removes it").isNull();
    }

    @Test
    void aCallersTraceIdStillWins() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.addHeader(TraceIdFilter.HEADER, "caller-trace-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String inMdc = runBoth(tracerWithSpan(SPAN_TRACE), request, response);

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("caller-trace-1");
        assertThat(request.getAttribute(TraceIdFilter.ATTR)).isEqualTo("caller-trace-1");
        assertThat(inMdc).isEqualTo("caller-trace-1");
    }

    @Test
    void aCallersTraceIdThatFailsTheCheckIsReplacedByTheSpans() throws Exception {
        for (String bad : new String[] {"not valid!", "   ", "x".repeat(129)}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
            request.addHeader(TraceIdFilter.HEADER, bad);
            MockHttpServletResponse response = new MockHttpServletResponse();

            runBoth(tracerWithSpan(SPAN_TRACE), request, response);

            assertThat(response.getHeader(TraceIdFilter.HEADER)).as(bad).isEqualTo(SPAN_TRACE);
        }
    }

    @Test
    void withNoSpanOrNoTracerTheMintedIdIsKept() throws Exception {
        Tracer noSpan = mock(Tracer.class);
        Tracer blankSpan = tracerWithSpan("");
        for (Tracer tracer : new Tracer[] {noSpan, blankSpan, null}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<String> minted = new AtomicReference<>();
            FilterChain inner = (req, res) -> { };
            new TraceIdFilter().doFilter(request, response, (req, res) -> {
                minted.set((String) req.getAttribute(TraceIdFilter.ATTR));
                new SpanTraceIdFilter(tracer).doFilter(req, res, inner);
            });

            assertThat(minted.get()).matches("[0-9a-f]{32}");
            assertThat(request.getAttribute(TraceIdFilter.ATTR)).isEqualTo(minted.get());
            assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(minted.get());
        }
    }

    /** The access log is written on a stream's async dispatch; it must find the span's id there too. */
    @Test
    void theAsyncDispatchKeepsTheSpansTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        runBoth(tracerWithSpan(SPAN_TRACE), request, response);

        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        String inMdc = runBoth(tracerWithSpan("ffffffffffffffffffffffffffffffff"), request, response);

        assertThat(inMdc).isEqualTo(SPAN_TRACE);
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(SPAN_TRACE);
    }

    /**
     * Built by Spring, the filter gets the application's tracer. A second constructor without
     * {@code @Autowired} would make Spring take that one and leave the filter with no tracer.
     */
    @Test
    void springBuildsItWithTheApplicationsTracer() {
        new ApplicationContextRunner()
                .withBean(Tracer.class, () -> tracerWithSpan(SPAN_TRACE))
                .withBean(SpanTraceIdFilter.class)
                .run(context -> {
                    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
                    MockHttpServletResponse response = new MockHttpServletResponse();
                    context.getBean(SpanTraceIdFilter.class).doFilter(request, response, new MockFilterChain());
                    assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(SPAN_TRACE);
                });
    }

    @Test
    void springBuildsItWithNoTracerAtAll() {
        new ApplicationContextRunner()
                .withBean(SpanTraceIdFilter.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(SpanTraceIdFilter.class));
    }

    /** It runs just inside the server observation filter, which Spring Boot registers one after the highest. */
    @Test
    void itRunsJustInsideTheServerObservationFilter() {
        assertThat(SpanTraceIdFilter.ORDER).isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE + 2);
        assertThat(SpanTraceIdFilter.class.getAnnotation(org.springframework.core.annotation.Order.class).value())
                .isEqualTo(SpanTraceIdFilter.ORDER);
    }
}
