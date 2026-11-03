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
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Gives a request the trace id of its server span, the second half of {@link TraceIdFilter}.
 *
 * <p>{@link TraceIdFilter} runs first, so the request start and the trace id are set before anything else.
 * At that point Spring's server observation filter ({@code HIGHEST_PRECEDENCE + 1}) has not opened the
 * request's span yet, so a request with no {@code X-Trace-ID} got a random id. That span continues the
 * caller's W3C {@code traceparent}, or starts a new trace. This filter runs just inside it and replaces the
 * random id with the span's trace id, on the request attribute, the response header and the log context.
 * The audit record, error bodies and the access-log line all read one of those, so they carry it too, and a
 * model call can be joined with the caller's other calls in the same trace.
 *
 * <p>A caller's own valid {@code X-Trace-ID} still wins. With no tracer, or no span, the id
 * {@link TraceIdFilter} made is kept. The log-context entry is left for {@link TraceIdFilter} to remove,
 * after the access-log line has read it.
 */
@Component
@Order(SpanTraceIdFilter.ORDER)
public class SpanTraceIdFilter extends OncePerRequestFilter {

    /** Just inside Spring's server observation filter, which is registered at {@code HIGHEST_PRECEDENCE + 1}. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 2;

    private final Tracer tracer;

    /**
     * The constructor Spring uses, marked so: with two constructors and neither marked, Spring would not
     * pick this one, and the filter would never have a tracer.
     */
    @Autowired
    public SpanTraceIdFilter(ObjectProvider<Tracer> tracer) {
        this(tracer.getIfAvailable());
    }

    SpanTraceIdFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!TraceIdFilter.callerNamedTraceId(request)) {
            String traceId = spanTraceId();
            if (traceId != null) {
                request.setAttribute(TraceIdFilter.ATTR, traceId);
                response.setHeader(TraceIdFilter.HEADER, traceId);
                MDC.put(TraceIdFilter.MDC_TRACE_ID, traceId);
            }
        }
        chain.doFilter(request, response);
    }

    private String spanTraceId() {
        if (tracer == null) {
            return null;
        }
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        String traceId = span.context().traceId();
        return traceId == null || traceId.isBlank() ? null : traceId;
    }
}
