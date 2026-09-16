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

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void generatesTraceId_whenHeaderAbsent() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertThat(traceId)
                .isNotNull()
                .isNotBlank()
                .hasSize(32);          // UUID without hyphens → 32 hex chars
        assertThat(request.getAttribute(TraceIdFilter.ATTR)).isEqualTo(traceId);
    }

    @Test
    void echoesExistingTraceId_whenHeaderPresent() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(TraceIdFilter.HEADER, "custom-trace-id-123");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("custom-trace-id-123");
        assertThat(request.getAttribute(TraceIdFilter.ATTR)).isEqualTo("custom-trace-id-123");
    }

    // A caller-supplied id is copied into every log line, so one that is too long or carries other
    // characters is replaced rather than trusted.
    @Test
    void anUnsafeTraceIdIsReplaced() throws Exception {
        for (String bad : new String[] {"x".repeat(129), "abc\"}, {\"level\":\"ERROR", "a b"}) {
            MockHttpServletRequest  request  = new MockHttpServletRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();
            request.addHeader(TraceIdFilter.HEADER, bad);

            filter.doFilter(request, response, new MockFilterChain());

            assertThat(response.getHeader(TraceIdFilter.HEADER)).isNotEqualTo(bad).hasSize(32);
        }
    }

    @Test
    void anUnsafeSessionIdIsIgnored() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.SESSION_HEADER, "s".repeat(500));

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(TraceIdFilter.SESSION_ATTR)).isNull();
    }

    @Test
    void aSafeSessionIdIsKept() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.SESSION_HEADER, "agent-run_42:step.7");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(TraceIdFilter.SESSION_ATTR)).isEqualTo("agent-run_42:step.7");
    }

    @Test
    void filterChain_isContinued() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain         chain    = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // MockFilterChain.getRequest() returns non-null only after doFilter was invoked on it
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void generatedTraceId_isHexString() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).matches("[0-9a-f]{32}");
    }

    @Test
    void setsTraceIdInMdc_duringRequest() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(TraceIdFilter.HEADER, "test-trace-123");

        AtomicReference<String> capturedMdc = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> capturedMdc.set(MDC.get("trace_id")));

        assertThat(capturedMdc.get()).isEqualTo("test-trace-123");
    }

    @Test
    void clearsTraceIdFromMdc_afterRequest() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(MDC.get("trace_id")).isNull();
    }

    @Test
    void blankIncomingTraceId_isReplaced() throws Exception {
        MockHttpServletRequest  request  = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(TraceIdFilter.HEADER, "   ");

        filter.doFilter(request, response, new MockFilterChain());

        // A blank value should be treated as absent and a new ID generated
        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).hasSize(32).matches("[0-9a-f]{32}");
    }

    /** On the async dispatch the id resolved at arrival is reused, so the access log carries it. */
    @Test
    void theAsyncDispatchReusesTheTraceIdResolvedAtArrival() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        String resolved = (String) request.getAttribute(TraceIdFilter.ATTR);
        assertThat(resolved).isNotBlank();

        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        java.util.concurrent.atomic.AtomicReference<String> inMdc = new java.util.concurrent.atomic.AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> inMdc.set(org.slf4j.MDC.get(TraceIdFilter.MDC_TRACE_ID)));
        assertThat(inMdc.get()).isEqualTo(resolved);
    }

    /** The duration the recording filters report starts here, the first thing done with a request. */
    @Test
    void stampsTheRequestStartForTheRecordingFilters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertThat(request.getAttribute(AccessLogFilter.ATTR_START_NANOS)).isInstanceOf(Long.class);
    }
}
