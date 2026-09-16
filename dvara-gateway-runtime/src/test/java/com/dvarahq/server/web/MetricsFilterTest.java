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

import com.dvarahq.core.region.RegionContext;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MetricsFilterTest {

    private SimpleMeterRegistry registry;
    private GatewayMetrics metrics;
    private MetricsFilter filter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        RegionContext regionContext = () -> java.util.Optional.empty();
        metrics = new GatewayMetrics(registry, regionContext);
        filter = new MetricsFilter(metrics);
    }

    @Test
    void recordsRequestCounterAndLatency() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        request.setAttribute(AccessLogFilter.ATTR_MODEL, "gpt-4");
        request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");
        request.setAttribute("workspaceId", "acme");

        filter.doFilter(request, response, (req, res) -> {});

        Counter counter = registry.find("gateway_requests_total")
                .tag("workspace", "acme")
                .tag("model", "gpt-4")
                .tag("provider", "openai")
                .tag("status", "200")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);

        Timer timer = registry.find("gateway_latency_seconds")
                .tag("workspace", "acme")
                .tag("model", "gpt-4")
                .tag("provider", "openai")
                .tag("status", "200")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    void defaultsToUnknownWhenAttributesMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, (req, res) -> {});

        Counter counter = registry.find("gateway_requests_total")
                .tag("workspace", "unknown")
                .tag("model", "unknown")
                .tag("provider", "unknown")
                .tag("status", "200")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void recordsTokensWhenPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        request.setAttribute(AccessLogFilter.ATTR_MODEL, "gpt-4");
        request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");
        request.setAttribute("workspaceId", "acme");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_PROMPT, "100");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_COMPLETION, "50");

        filter.doFilter(request, response, (req, res) -> {});

        Counter input = registry.find("gateway_tokens_total")
                .tag("workspace", "acme")
                .tag("model", "gpt-4")
                .tag("direction", "input")
                .counter();
        assertThat(input).isNotNull();
        assertThat(input.count()).isEqualTo(100.0);

        Counter output = registry.find("gateway_tokens_total")
                .tag("workspace", "acme")
                .tag("model", "gpt-4")
                .tag("direction", "output")
                .counter();
        assertThat(output).isNotNull();
        assertThat(output.count()).isEqualTo(50.0);
    }

    @Test
    void skipsTokenRecordingWhenNoTokenAttributes() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(registry.find("gateway_tokens_total").counters()).isEmpty();
    }

    @Test
    void recordsProviderErrorWhenPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(502);

        request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");
        request.setAttribute(AccessLogFilter.ATTR_ERROR_CODE, "PROVIDER_ERROR");

        filter.doFilter(request, response, (req, res) -> {});

        Counter counter = registry.find("gateway_provider_errors_total")
                .tag("provider", "openai")
                .tag("error_code", "PROVIDER_ERROR")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void skipsProviderErrorWhenNoErrorCode() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(registry.find("gateway_provider_errors_total").counters()).isEmpty();
    }

    @Test
    void skipsProviderErrorWhenNoProvider() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        request.setAttribute(AccessLogFilter.ATTR_ERROR_CODE, "INTERNAL_ERROR");

        filter.doFilter(request, response, (req, res) -> {});

        assertThat(registry.find("gateway_provider_errors_total").counters()).isEmpty();
    }

    @Test
    void recordsMetricsEvenWhenChainThrows() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        request.setAttribute(AccessLogFilter.ATTR_MODEL, "gpt-4");
        request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");

        FilterChain throwingChain = (req, res) -> {
            throw new ServletException("simulated error");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, throwingChain))
                .isInstanceOf(ServletException.class);

        Counter counter = registry.find("gateway_requests_total")
                .tag("model", "gpt-4")
                .tag("provider", "openai")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void capturesResponseStatusCode() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            ((MockHttpServletResponse) res).setStatus(429);
        });

        Counter counter = registry.find("gateway_requests_total")
                .tag("status", "429")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    void handlesInvalidTokenValues() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        request.setAttribute(AccessLogFilter.ATTR_TOKENS_PROMPT, "not-a-number");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_COMPLETION, "");

        filter.doFilter(request, response, (req, res) -> {});

        // Invalid values parsed as 0, so no tokens recorded (0 values skipped)
        assertThat(registry.find("gateway_tokens_total").counters()).isEmpty();
    }

    @Test
    void multipleRequestsAccumulate() throws ServletException, IOException {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setStatus(200);

            request.setAttribute(AccessLogFilter.ATTR_MODEL, "gpt-4");
            request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");
            request.setAttribute("workspaceId", "acme");

            filter.doFilter(request, response, (req, res) -> {});
        }

        Counter counter = registry.find("gateway_requests_total")
                .tag("workspace", "acme")
                .tag("model", "gpt-4")
                .tag("provider", "openai")
                .counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(3.0);

        Timer timer = registry.find("gateway_latency_seconds")
                .tag("workspace", "acme")
                .tag("model", "gpt-4")
                .tag("provider", "openai")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(3);
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

    @Test
    void statusPath_isNotFiltered() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/status");
        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    // ---- a stream is metered on its async dispatch, not at emitter handoff ----

    @Test
    void aStreamIsMeteredOnItsAsyncDispatch_notAtHandoff() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.setAttribute(AccessLogFilter.ATTR_MODEL, "gpt-4");
        request.setAttribute("workspaceId", "acme");

        filter.doFilter(request, response, (req, res) -> request.setAsyncStarted(true));
        assertThat(registry.find("gateway_requests_total").counter()).as("nothing at handoff").isNull();

        request.setAsyncStarted(false);
        request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
        request.setAttribute(AccessLogFilter.ATTR_PROVIDER, "openai");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_PROMPT, "80");
        request.setAttribute(AccessLogFilter.ATTR_TOKENS_COMPLETION, "40");
        request.setAttribute(AccessLogFilter.ATTR_START_NANOS, System.nanoTime() - 300_000_000L);
        filter.doFilter(request, response, (req, res) -> {});

        assertThat(registry.find("gateway_requests_total").tag("provider", "openai").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("gateway_latency_seconds").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .as("from arrival, not from the dispatch").isGreaterThanOrEqualTo(300);
        assertThat(registry.find("gateway_tokens_total").tag("direction", "input").counter().count()).isEqualTo(80.0);
    }

    /** What the chain throws after starting async must propagate; a return inside finally would eat it. */
    @Test
    void anExceptionAfterAsyncStarted_propagatesAndNothingIsRecorded() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            request.setAsyncStarted(true);
            throw new IllegalStateException("emitter failed to initialise");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(registry.find("gateway_requests_total").counter()).isNull();
    }

    /** A synchronous call under a millisecond keeps its real duration rather than recording zero. */
    @Test
    void aSubMillisecondCallIsNotRecordedAsZero() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {});
        assertThat(registry.find("gateway_latency_seconds").timer().totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .isGreaterThan(0);
    }
}
