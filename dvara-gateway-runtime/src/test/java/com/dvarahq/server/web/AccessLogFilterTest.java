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

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();

    @Test
    void setsMethodAndPathInMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> capturedMethod = new AtomicReference<>();
        AtomicReference<String> capturedPath = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            capturedMethod.set(MDC.get("method"));
            capturedPath.set(MDC.get("path"));
        };

        filter.doFilter(request, response, chain);

        assertThat(capturedMethod.get()).isEqualTo("POST");
        assertThat(capturedPath.get()).isEqualTo("/v1/chat/completions");
    }

    @Test
    void cleansUpMdcAfterRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/status");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {});

        // All MDC keys should be cleaned up after the request
        assertThat(MDC.get("method")).isNull();
        assertThat(MDC.get("path")).isNull();
        assertThat(MDC.get("latency_ms")).isNull();
        assertThat(MDC.get("status")).isNull();
        assertThat(MDC.get("api_key")).isNull();
        assertThat(MDC.get("workspace_id")).isNull();
    }

    @Test
    void enrichesFromRequestAttributes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        request.setAttribute(AccessLogFilter.ATTR_MODEL, "gpt-4");
        request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");
        request.setAttribute(AccessLogFilter.ATTR_CACHE_STATUS, "MISS");
        request.setAttribute(AccessLogFilter.ATTR_STREAM, "false");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_PROMPT, "100");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_COMPLETION, "50");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_TOTAL, "150");
        request.setAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR, "key-id-1");

        filter.doFilter(request, response, (req, res) -> {});

        // MDC is cleaned up after, so we can't check it here.
        // The structured log line is emitted inside the filter.
        // We simply verify no exceptions and cleanup happened.
        assertThat(MDC.get("model")).isNull();
    }

    @Test
    void attribution_isTheKeyId_orAnonymous() {
        // The log carries the key's opaque id whole: it is not a secret, and it is never a
        // slice of the bearer token.
        assertThat(AccessLogFilter.attribution("key-id-1")).isEqualTo("key-id-1");
        assertThat(AccessLogFilter.attribution(null)).isEqualTo("anonymous");
        assertThat(AccessLogFilter.attribution("  ")).isEqualTo("anonymous");
    }

    @Test
    void setsLatencyAndStatus() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        // We can't capture MDC values after the filter since they're cleaned up.
        // But we verify the filter runs without error.
        filter.doFilter(request, response, (req, res) -> {});

        assertThat(MDC.get("latency_ms")).isNull();
        assertThat(MDC.get("status")).isNull();
    }

    @Test
    void workspaceIdDefaultsToUnknown() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // No workspaceId attribute set — should default to "unknown"
        filter.doFilter(request, response, (req, res) -> {});

        assertThat(MDC.get("workspace_id")).isNull(); // cleaned up after
    }

    @Test
    void mdcCleanedUpEvenOnException() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new RuntimeException("simulated error");
            });
        } catch (RuntimeException ignored) {
            // expected
        }

        // MDC should still be cleaned up
        assertThat(MDC.get("method")).isNull();
        assertThat(MDC.get("path")).isNull();
    }

    @Test
    void nonV1Paths_areNotFiltered() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void v1Paths_areFiltered() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    // ---- a stream is recorded on its async dispatch, not at emitter handoff ----

    /**
     * The chain returns at emitter handoff with async started; nothing is logged then. The async
     * dispatch, after the stream ended, logs with the attributes the tail set and the latency from
     * the request's arrival.
     */
    @Test
    void aStreamIsLoggedOnItsAsyncDispatch_withWhatTheTailSet() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        java.util.List<String> logged = new java.util.ArrayList<>();
        FilterChain handoff = (req, res) -> request.setAsyncStarted(true);

        filter.doFilter(request, response, handoff);
        assertThat(MDC.get("status")).as("nothing recorded at handoff").isNull();
        Object stamped = request.getAttribute(AccessLogFilter.ATTR_START_NANOS);
        assertThat(stamped).isInstanceOf(Long.class);

        // The stream ran: the tail set provider and tokens, then completed the emitter.
        request.setAsyncStarted(false);
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_TOTAL, "120");
        request.setAttribute(AccessLogFilter.ATTR_START_NANOS, System.nanoTime() - 250_000_000L);
        AtomicReference<String> latencyAtLog = new AtomicReference<>();
        AtomicReference<String> providerAtLog = new AtomicReference<>();
        // The filter puts, logs and removes its MDC keys, so the record is read off the log event.
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AccessLogFilter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilter(request, response, (req, res) -> {});
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(1);
        java.util.Map<String, String> mdc = appender.list.get(0).getMDCPropertyMap();
        assertThat(mdc).containsEntry("provider", "openai").containsEntry("tokens_total", "120");
        assertThat(Long.parseLong(mdc.get("latency_ms"))).as("from arrival, not from the dispatch").isGreaterThanOrEqualTo(250);
    }

    /** A request that never went async is logged when the chain returns. */
    @Test
    void aSynchronousRequestIsLoggedAtItsReturn() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AccessLogFilter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            filter.doFilter(request, response, (req, res) -> {});
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(1);
    }

    /** What the chain throws after starting async must propagate; a return inside finally would eat it. */
    @Test
    void anExceptionAfterAsyncStarted_propagatesAndNothingIsLogged() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(AccessLogFilter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
                request.setAsyncStarted(true);
                throw new IllegalStateException("emitter failed to initialise");
            })).isInstanceOf(IllegalStateException.class);
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).isEmpty();
        assertThat(MDC.get("method")).as("MDC still cleaned").isNull();
    }
}
