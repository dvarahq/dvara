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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that ensures every response carries an X-Trace-ID header.
 * If the incoming request already has an X-Trace-ID the same value is echoed back;
 * otherwise, when an OpenTelemetry {@link Tracer} is active and a span is in progress,
 * the OTel trace ID (32-char hex) is used. If neither is available, a new random UUID
 * hex is generated. The resolved ID is stored as a request attribute and in the SLF4J
 * MDC so every log line emitted during request processing automatically includes the
 * trace ID.
 *
 * <p>Also extracts the optional {@code X-Session-Id} header and stores it as a request
 * attribute and MDC entry for agent session correlation.</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-ID";
    public static final String ATTR   = "traceId";
    public static final String MDC_TRACE_ID = "trace_id";

    public static final String SESSION_HEADER = "X-Session-Id";
    public static final String SESSION_ATTR = "sessionId";
    public static final String MDC_SESSION_ID = "session_id";

    /**
     * What a caller-supplied trace or session id may look like. Both are copied into the response, the MDC
     * and every log line, so an unchecked header could put kilobytes, or text shaped like other JSON log
     * fields, into each line of a caller's requests.
     */
    static final java.util.regex.Pattern SAFE_ID = java.util.regex.Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final Tracer tracer;

    public TraceIdFilter(@Autowired(required = false) Tracer tracer) {
        this.tracer = tracer;
    }

    public TraceIdFilter() {
        this(null);
    }

    /** The access log is written on a stream's async dispatch; its MDC needs the trace id there. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // this filter runs on the async dispatch too, so the access log written there
        // carries the trace id. The id resolved on the initial dispatch is on the request; a second
        // resolution here would mint a new one for a request that had none in its header.
        String traceId = (String) request.getAttribute(ATTR);
        if (traceId == null) {
            traceId = request.getHeader(HEADER);
            if (traceId != null && !SAFE_ID.matcher(traceId).matches()) {
                traceId = null;   // replaced with a fresh id below rather than copied into every line
            }
        }
        // The duration the access log, metrics and audit filters report runs from here, the first
        // thing the gateway does with a request. Idempotent, so the async dispatch keeps it.
        AccessLogFilter.stampStart(request);
        if (traceId == null || traceId.isBlank()) {
            traceId = resolveTraceId();
        }
        request.setAttribute(ATTR, traceId);
        response.setHeader(HEADER, traceId);
        MDC.put(MDC_TRACE_ID, traceId);

        String sessionId = request.getHeader(SESSION_HEADER);
        if (sessionId != null && SAFE_ID.matcher(sessionId).matches()) {
            request.setAttribute(SESSION_ATTR, sessionId);
            MDC.put(MDC_SESSION_ID, sessionId);
        }

        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SESSION_ID);
        }
    }

    private String resolveTraceId() {
        if (tracer != null) {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                String otelTraceId = currentSpan.context().traceId();
                if (otelTraceId != null && !otelTraceId.isBlank()) {
                    return otelTraceId;
                }
            }
        }
        return UUID.randomUUID().toString().replace("-", "");
    }
}