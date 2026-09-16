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

import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.core.apikey.ApiKeyStatus;
import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.ratelimit.EffectiveRateLimit;
import com.dvarahq.core.ratelimit.RateLimitResult;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.server.metrics.GatewayMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServlet;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The data-plane filters in their real order, sorted by their {@code @Order} annotations. The auth and
 * rate-limit filters return without calling the chain when they refuse, so a refusal leaves a log line,
 * a metric and an audit event only if the access-log, metrics and audit filters wrap them.
 */
class RefusedRequestsAreRecordedTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<AuditEvent> audited = new ArrayList<>();
    private final ApiKeyRepository keys = mock(ApiKeyRepository.class);
    private final RateLimiter limiter = mock(RateLimiter.class);

    private MockHttpServletResponse send(String authorization) throws Exception {
        RegionContext region = Optional::empty;
        List<Filter> filters = new ArrayList<>(List.of(
                new RateLimitServletFilter(limiter, null, null),
                new ApiKeyAuthFilter(keys, true),
                new AuditResponseFilter(audited::add),
                new MetricsFilter(new GatewayMetrics(registry, region)),
                new AccessLogFilter(),
                new TraceIdFilter()));
        AnnotationAwareOrderComparator.sort(filters);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/models");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MockFilterChain(new HttpServlet() { }, filters.toArray(Filter[]::new)).doFilter(request, response);
        return response;
    }

    private double requests(String status) {
        Counter c = registry.find("gateway_requests_total").tag("status", status).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void anInvalidKeyIsLoggedMeteredAndAudited() throws Exception {
        when(keys.findByKeyHash(anyString())).thenReturn(Optional.empty());

        MockHttpServletResponse response = send("Bearer sk-guessed");

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(requests("401")).isEqualTo(1.0);
        assertThat(audited).singleElement().satisfies(e -> {
            assertThat(e.eventType()).isEqualTo("GATEWAY_RESPONSE");
            assertThat(e.payload()).containsEntry("status", 401).containsEntry("error_code", "invalid_api_key");
        });
    }

    @Test
    void aRateLimitedCallIsMeteredAndAudited() throws Exception {
        ApiKey key = mock(ApiKey.class);
        when(key.getId()).thenReturn("key-1");
        when(key.getWorkspaceId()).thenReturn("acme");
        when(key.getStatus()).thenReturn(ApiKeyStatus.ACTIVE);
        when(keys.findByKeyHash(anyString())).thenReturn(Optional.of(key));
        RateLimitResult denied = mock(RateLimitResult.class);
        when(denied.allowed()).thenReturn(false);
        when(denied.reason()).thenReturn("limited");
        when(limiter.checkLimit(anyString(), any(EffectiveRateLimit.class))).thenReturn(denied);

        MockHttpServletResponse response = send("Bearer sk-real");

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(requests("429")).as("a rate-limit alert can fire").isEqualTo(1.0);
        assertThat(audited).singleElement().satisfies(e ->
                assertThat(e.payload()).containsEntry("status", 429).containsEntry("error_code", "rate_limit_exceeded"));
    }
}
