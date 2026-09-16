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
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link TraceIdFilter} integration with Micrometer {@link Tracer}.
 */
class TraceIdFilterWithTracerTest {

    @Test
    void usesOtelTraceIdWhenTracerActiveAndNoClientHeader() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("abcdef1234567890abcdef1234567890");

        TraceIdFilter filter = new TraceIdFilter(tracer);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-Trace-ID")).isEqualTo("abcdef1234567890abcdef1234567890");
        assertThat(request.getAttribute("traceId")).isEqualTo("abcdef1234567890abcdef1234567890");
    }

    @Test
    void clientHeaderTakesPrecedenceOverOtelTraceId() throws Exception {
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn("abcdef1234567890abcdef1234567890");

        TraceIdFilter filter = new TraceIdFilter(tracer);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Trace-ID", "my-custom-trace-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(response.getHeader("X-Trace-ID")).isEqualTo("my-custom-trace-id");
        assertThat(request.getAttribute("traceId")).isEqualTo("my-custom-trace-id");
    }

    @Test
    void fallsBackToUuidWhenNoSpanActive() throws Exception {
        Tracer tracer = mock(Tracer.class);
        when(tracer.currentSpan()).thenReturn(null);

        TraceIdFilter filter = new TraceIdFilter(tracer);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        String traceId = response.getHeader("X-Trace-ID");
        assertThat(traceId).isNotNull().hasSize(32); // UUID hex without dashes
    }

    @Test
    void fallsBackToUuidWhenTracerIsNull() throws Exception {
        TraceIdFilter filter = new TraceIdFilter(null);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        String traceId = response.getHeader("X-Trace-ID");
        assertThat(traceId).isNotNull().hasSize(32);
    }

    @Test
    void noArgConstructorFallsBackToUuid() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        String traceId = response.getHeader("X-Trace-ID");
        assertThat(traceId).isNotNull().hasSize(32);
    }
}