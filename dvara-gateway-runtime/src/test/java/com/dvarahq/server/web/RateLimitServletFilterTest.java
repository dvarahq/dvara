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

import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.ratelimit.EffectiveRateLimit;
import com.dvarahq.core.ratelimit.RateLimitErrorDetail;
import com.dvarahq.core.ratelimit.RateLimitResult;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.ratelimit.WorkspaceRateLimitResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RateLimitServletFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RateLimiter rateLimiter;
    private RateLimitServletFilter filter;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        rateLimiter = mock(RateLimiter.class);
        filter = new RateLimitServletFilter(rateLimiter, null, null);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        chain = mock(FilterChain.class);
    }

    @Test
    void allowedRequest_continuesFilterChain() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test-key");
        when(rateLimiter.checkLimit(eq("sk-test-key"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    /** The limiter's handle on the reservation travels with the request, for the settlement. */
    @Test
    void theReservationIdTheLimiterIssued_isStoredOnTheRequest() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test-key");
        when(rateLimiter.checkLimit(eq("sk-test-key"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow(4_242L));

        filter.doFilterInternal(request, response, chain);

        verify(request).setAttribute(RateLimitServletFilter.RESERVATION_ID_ATTR, 4_242L);
        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectedRequest_returns429WithRetryAfterHeader() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test-key");
        when(rateLimiter.checkLimit(eq("sk-test-key"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.reject(5, "Rate limit exceeded"));
        when(response.getHeader(TraceIdFilter.HEADER)).thenReturn("trace-123");
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(429);
        verify(response).setHeader("Retry-After", "5");
        verify(chain, never()).doFilter(any(), any());

        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> body = MAPPER.readValue(writer.toString(), Map.class);
        assertThat(body.get("error").get("message")).isEqualTo("Rate limit exceeded");
        assertThat(body.get("error").get("type")).isEqualTo("rate_limit_error");
        assertThat(body.get("error").get("code")).isEqualTo("rate_limit_exceeded");
        assertThat(body.get("error").get("trace_id")).isEqualTo("trace-123");
    }

    /**
     * The {@code error.rate_limit} block, which the filter builds by hand key by key. Every key in
     * it is one a limiter fills; a key no limiter fills would read as a feature that does not exist.
     */
    @Test
    void tokenRejection_carriesTheRateLimitBlockAndTheTokenHeaders() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test-key");
        Instant resetAt = Instant.parse("2026-09-12T17:30:00Z");
        when(rateLimiter.checkLimit(eq("sk-test-key"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.reject(42, "Token rate limit exceeded",
                        RateLimitErrorDetail.builder()
                                .limitedResource("tokens")
                                .limitType("tokens_per_minute")
                                .limit(100_000)
                                .remaining(0)
                                .resetAt(resetAt)
                                .retryAfterSeconds(42)
                                .build()));
        StringWriter writer = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(writer));

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader("X-RateLimit-Tokens-Limit", "100000");
        verify(response).setHeader("X-RateLimit-Tokens-Remaining", "0");
        verify(response).setHeader("X-RateLimit-Reset", resetAt.toString());

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Map<String, Object>>> body = MAPPER.readValue(writer.toString(), Map.class);
        Map<String, Object> rateLimit = body.get("error").get("rate_limit");
        assertThat(rateLimit)
                .containsEntry("limited_resource", "tokens")
                .containsEntry("limit_type", "tokens_per_minute")
                .containsEntry("limit", 100_000)
                .containsEntry("remaining", 0)
                .containsEntry("retry_after_seconds", 42)
                .containsEntry("reset_at", resetAt.toString());
        assertThat(rateLimit.keySet())
                .as("every key here is filled by a limiter; a key no limiter fills is dead weight "
                        + "that reads as a feature")
                .containsExactlyInAnyOrder("limited_resource", "limit_type", "limit", "remaining",
                        "retry_after_seconds", "reset_at");
    }

    @Test
    void readsApiKeyFromAttribute() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("my-api-key-123");
        when(rateLimiter.checkLimit(eq("my-api-key-123"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        verify(rateLimiter).checkLimit(eq("my-api-key-123"), any(EffectiveRateLimit.class));
    }

    @Test
    void missingAttribute_usesAnonymous() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/embeddings");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn(null);
        when(rateLimiter.checkLimit(eq("anonymous"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        verify(rateLimiter).checkLimit(eq("anonymous"), any(EffectiveRateLimit.class));
    }

    @Test
    void nonV1Paths_areNotFiltered() {
        when(request.getRequestURI()).thenReturn("/status");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void v1Paths_areFiltered() {
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");

        assertThat(filter.shouldNotFilter(request)).isFalse();
    }

    // ---- Token estimation tests ----

    @Test
    void tokenEstimation_callsCheckLimitWithEstimate() throws Exception {
        TokenEstimator tokenEstimator = mock(TokenEstimator.class);
        when(tokenEstimator.estimateTokens(any(String.class))).thenReturn(500);
        filter = new RateLimitServletFilter(rateLimiter, tokenEstimator, null);

        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test");
        when(request.getContentLength()).thenReturn(100);
        when(request.getInputStream()).thenReturn(mockInputStream("{\"model\":\"gpt-4o\",\"messages\":[]}"));
        when(rateLimiter.checkLimit(eq("sk-test"), eq(500), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        verify(rateLimiter).checkLimit(eq("sk-test"), eq(500), any(EffectiveRateLimit.class));
        verify(chain).doFilter(any(), eq(response));
    }

    /**
     * A chunked body over the estimation cap is forwarded whole and not estimated. Cutting it at the
     * cap and replaying the prefix would hand the controller truncated JSON with nothing to say the
     * gateway had done the cutting.
     */
    @Test
    void aChunkedBodyOverTheCap_isForwardedWholeAndNotEstimated() throws Exception {
        TokenEstimator tokenEstimator = mock(TokenEstimator.class);
        filter = new RateLimitServletFilter(rateLimiter, tokenEstimator, null);
        byte[] body = new byte[10 * 1024 * 1024 + 3];
        java.util.Arrays.fill(body, (byte) 'a');
        body[body.length - 3] = '}'; body[body.length - 2] = '\r'; body[body.length - 1] = '\n';

        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test");
        when(request.getContentLength()).thenReturn(-1);   // chunked: the length is not declared
        when(request.getContentLengthLong()).thenReturn(-1L);
        when(request.getInputStream()).thenReturn(mockInputStream(body));
        when(rateLimiter.checkLimit(eq("sk-test"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        ArgumentCaptor<HttpServletRequest> forwarded = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(forwarded.capture(), eq(response));
        assertThat(forwarded.getValue().getInputStream().readAllBytes())
                .as("every byte reaches the controller, the ones past the cap included")
                .isEqualTo(body);
        assertThat(forwarded.getValue().getContentLength()).as("still undeclared").isEqualTo(-1);
        verify(tokenEstimator, never()).estimateTokens(any(String.class));
        verify(rateLimiter).checkLimit(eq("sk-test"), any(EffectiveRateLimit.class));
    }

    /** The boundary, both sides: exactly the cap is estimated; one byte more is forwarded whole. */
    @Test
    void theCapItself_isEstimated_andOneByteMore_isForwardedWhole() throws Exception {
        for (int over : new int[]{0, 1}) {
            setUp();
            TokenEstimator tokenEstimator = mock(TokenEstimator.class);
            when(tokenEstimator.estimateTokens(any(String.class))).thenReturn(7);
            filter = new RateLimitServletFilter(rateLimiter, tokenEstimator, null);
            byte[] body = new byte[10 * 1024 * 1024 + over];
            java.util.Arrays.fill(body, (byte) 'b');

            when(request.getRequestURI()).thenReturn("/v1/chat/completions");
            when(request.getMethod()).thenReturn("POST");
            when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test");
            when(request.getContentLength()).thenReturn(-1);
            when(request.getInputStream()).thenReturn(mockInputStream(body));
            when(rateLimiter.checkLimit(eq("sk-test"), any(EffectiveRateLimit.class))).thenReturn(RateLimitResult.allow());
            when(rateLimiter.checkLimit(eq("sk-test"), eq(7), any(EffectiveRateLimit.class))).thenReturn(RateLimitResult.allow());

            filter.doFilterInternal(request, response, chain);

            ArgumentCaptor<HttpServletRequest> forwarded = ArgumentCaptor.forClass(HttpServletRequest.class);
            verify(chain).doFilter(forwarded.capture(), eq(response));
            assertThat(forwarded.getValue().getInputStream().readAllBytes()).as("over=" + over).isEqualTo(body);
            if (over == 0) {
                verify(tokenEstimator).estimateTokens(any(String.class));
                verify(rateLimiter).checkLimit(eq("sk-test"), eq(7), any(EffectiveRateLimit.class));
            } else {
                verify(tokenEstimator, never()).estimateTokens(any(String.class));
                verify(rateLimiter).checkLimit(eq("sk-test"), any(EffectiveRateLimit.class));
            }
        }
    }

    /** A chunked body under the cap is estimated like a declared one. */
    @Test
    void aChunkedBodyUnderTheCap_isEstimated() throws Exception {
        TokenEstimator tokenEstimator = mock(TokenEstimator.class);
        when(tokenEstimator.estimateTokens(any(String.class))).thenReturn(500);
        filter = new RateLimitServletFilter(rateLimiter, tokenEstimator, null);

        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test");
        when(request.getContentLength()).thenReturn(-1);
        when(request.getInputStream()).thenReturn(mockInputStream("{\"model\":\"gpt-4o\",\"messages\":[]}"));
        when(rateLimiter.checkLimit(eq("sk-test"), eq(500), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        ArgumentCaptor<HttpServletRequest> forwarded = ArgumentCaptor.forClass(HttpServletRequest.class);
        verify(chain).doFilter(forwarded.capture(), eq(response));
        assertThat(new String(forwarded.getValue().getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                .isEqualTo("{\"model\":\"gpt-4o\",\"messages\":[]}");
        verify(rateLimiter).checkLimit(eq("sk-test"), eq(500), any(EffectiveRateLimit.class));
    }

    @Test
    void tokenEstimation_skippedForNonChatEndpoints() throws Exception {
        TokenEstimator tokenEstimator = mock(TokenEstimator.class);
        filter = new RateLimitServletFilter(rateLimiter, tokenEstimator, null);

        when(request.getRequestURI()).thenReturn("/v1/embeddings");
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test");
        when(rateLimiter.checkLimit(eq("sk-test"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        verify(rateLimiter).checkLimit(eq("sk-test"), any(EffectiveRateLimit.class));
        verify(rateLimiter, never()).checkLimit(any(), anyInt(), any());
        verify(tokenEstimator, never()).estimateTokens(any(String.class));
    }

    @Test
    void tokenEstimation_skippedWhenEstimatorNull() throws Exception {
        // filter created with null tokenEstimator (from setUp)
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("sk-test");
        when(rateLimiter.checkLimit(eq("sk-test"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        verify(rateLimiter).checkLimit(eq("sk-test"), any(EffectiveRateLimit.class));
        verify(rateLimiter, never()).checkLimit(any(), anyInt(), any());
    }

    // ---- per-workspace override wiring ----

    @Test
    void nullResolver_passesNoneOverride() throws Exception {
        // setUp's filter has a null resolver → the limiter must receive EffectiveRateLimit.NONE
        when(request.getRequestURI()).thenReturn("/v1/embeddings");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("keyA");
        when(rateLimiter.checkLimit(eq("keyA"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        verify(rateLimiter).checkLimit("keyA", EffectiveRateLimit.NONE);
    }

    @Test
    void resolvesWorkspaceOverride_passedToLimiter_requestPath() throws Exception {
        EffectiveRateLimit override = new EffectiveRateLimit(1000, 0);
        WorkspaceRateLimitResolver resolver = mock(WorkspaceRateLimitResolver.class);
        when(resolver.resolve("workspace-A")).thenReturn(override);
        filter = new RateLimitServletFilter(rateLimiter, null, resolver);

        when(request.getRequestURI()).thenReturn("/v1/embeddings");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("keyA");
        when(request.getAttribute(ApiKeyAuthFilter.WORKSPACE_ID_ATTR)).thenReturn("workspace-A");
        when(rateLimiter.checkLimit(eq("keyA"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        // the filter read WORKSPACE_ID_ATTR, resolved workspace-A's override, and passed it through
        verify(resolver).resolve("workspace-A");
        verify(rateLimiter).checkLimit("keyA", override);
    }

    @Test
    void resolverPresent_nullWorkspaceId_passesNone() throws Exception {
        // keyless / anonymous traffic has no WORKSPACE_ID_ATTR — the resolver must return NONE for null
        WorkspaceRateLimitResolver resolver = mock(WorkspaceRateLimitResolver.class);
        when(resolver.resolve(null)).thenReturn(EffectiveRateLimit.NONE);
        filter = new RateLimitServletFilter(rateLimiter, null, resolver);

        when(request.getRequestURI()).thenReturn("/v1/embeddings");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn(null);
        when(request.getAttribute(ApiKeyAuthFilter.WORKSPACE_ID_ATTR)).thenReturn(null);
        when(rateLimiter.checkLimit(eq("anonymous"), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        verify(resolver).resolve(null);
        verify(rateLimiter).checkLimit("anonymous", EffectiveRateLimit.NONE);
    }

    @Test
    void resolvesWorkspaceOverride_passedToLimiter_tokenPath() throws Exception {
        EffectiveRateLimit override = new EffectiveRateLimit(0, 2_000_000);
        WorkspaceRateLimitResolver resolver = mock(WorkspaceRateLimitResolver.class);
        when(resolver.resolve("workspace-A")).thenReturn(override);
        TokenEstimator tokenEstimator = mock(TokenEstimator.class);
        when(tokenEstimator.estimateTokens(any(String.class))).thenReturn(500);
        filter = new RateLimitServletFilter(rateLimiter, tokenEstimator, resolver);

        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(request.getMethod()).thenReturn("POST");
        when(request.getAttribute(ApiKeyAuthFilter.API_KEY_ATTR)).thenReturn("keyA");
        when(request.getAttribute(ApiKeyAuthFilter.WORKSPACE_ID_ATTR)).thenReturn("workspace-A");
        when(request.getContentLength()).thenReturn(100);
        when(request.getInputStream()).thenReturn(mockInputStream("{\"model\":\"gpt-4o\",\"messages\":[]}"));
        when(rateLimiter.checkLimit(eq("keyA"), eq(500), any(EffectiveRateLimit.class)))
                .thenReturn(RateLimitResult.allow());

        filter.doFilterInternal(request, response, chain);

        verify(rateLimiter).checkLimit("keyA", 500, override);
    }

    private static ServletInputStream mockInputStream(String content) {
        return mockInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static ServletInputStream mockInputStream(byte[] bytes) {
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        return new ServletInputStream() {
            @Override public int read() { return bais.read(); }
            @Override public int read(byte[] b, int off, int len) { return bais.read(b, off, len); }
            @Override public boolean isFinished() { return bais.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(jakarta.servlet.ReadListener listener) {}
        };
    }
}