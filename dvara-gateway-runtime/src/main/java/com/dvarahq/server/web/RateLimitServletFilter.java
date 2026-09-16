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

import com.dvarahq.core.plane.GatewayPlane;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.ratelimit.EffectiveRateLimit;
import com.dvarahq.core.ratelimit.RateLimitErrorDetail;
import com.dvarahq.core.ratelimit.RateLimitResult;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.ratelimit.WorkspaceRateLimitResolver;
import com.dvarahq.core.util.JsonMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 6)
public class RateLimitServletFilter extends OncePerRequestFilter {

    /** Tokens reserved at admission, read by whoever settles the real figure. */
    public static final String RESERVED_TOKENS_ATTR = "dvara.rateLimit.reservedTokens";
    /** The limiter's own handle on that reservation, a {@code Long}; {@code 0} when it issued none. */
    public static final String RESERVATION_ID_ATTR = "dvara.rateLimit.reservationId";

    public static final String API_KEY_ATTR = ApiKeyAuthFilter.API_KEY_ATTR;
    public static final String WORKSPACE_ID_ATTR = ApiKeyAuthFilter.WORKSPACE_ID_ATTR;

    /**
     * Max body size to read for token estimation (10 MiB). A larger body skips estimation and is
     * forwarded whole — whether its length was declared, or was unknown and turned out to exceed
     * this once read.
     */
    private static final int MAX_ESTIMATION_BODY_BYTES = 10 * 1024 * 1024;

    private final RateLimiter rateLimiter;
    private final TokenEstimator tokenEstimator;
    private final WorkspaceRateLimitResolver workspaceRateLimitResolver;

    public RateLimitServletFilter(RateLimiter rateLimiter,
                                   @Autowired(required = false) TokenEstimator tokenEstimator,
                                   @Autowired(required = false) WorkspaceRateLimitResolver workspaceRateLimitResolver) {
        this.rateLimiter = rateLimiter;
        this.tokenEstimator = tokenEstimator;
        this.workspaceRateLimitResolver = workspaceRateLimitResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !GatewayPlane.LLM.owns(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = (String) request.getAttribute(API_KEY_ATTR);
        if (apiKey == null) apiKey = "anonymous";

        // Estimate tokens for pre-request token budget check
        int estimatedTokens = 0;
        HttpServletRequest effectiveRequest = request;
        if (tokenEstimator != null && isChatCompletionRequest(request)) {
            int contentLength = request.getContentLength();
            // Skip estimation for oversized bodies (avoids OOM)
            if (contentLength < 0 || contentLength <= MAX_ESTIMATION_BODY_BYTES) {
                jakarta.servlet.ServletInputStream in = request.getInputStream();
                byte[] body = in.readNBytes(MAX_ESTIMATION_BODY_BYTES + 1);
                if (body.length > MAX_ESTIMATION_BODY_BYTES) {
                    // The length was unknown (a chunked body) and the cap is exceeded. Treated as a
                    // declared oversized body is: no estimate, and the body forwarded whole (the
                    // prefix read here, then whatever is still on the wire) with the length
                    // reported as the request reported it.
                    effectiveRequest = new CachedBodyRequestWrapper(request, body, in);
                } else {
                    if (body.length > 0) {
                        String bodyStr = new String(body, StandardCharsets.UTF_8);
                        estimatedTokens = tokenEstimator.estimateTokens(bodyStr);
                    }
                    effectiveRequest = new CachedBodyRequestWrapper(request, body);
                }
            }
        }

        // This workspace's per-key override; ApiKeyAuthFilter, which runs just before this filter,
        // stamped WORKSPACE_ID_ATTR. NONE means the limiter uses its global defaults.
        EffectiveRateLimit override = EffectiveRateLimit.NONE;
        if (workspaceRateLimitResolver != null) {
            override = workspaceRateLimitResolver.resolve((String) request.getAttribute(WORKSPACE_ID_ATTR));
        }

        // What this request reserved, for whoever settles it: the real figure arrives in
        // ChatExecutionService, which must settle against the estimate rather than charge on top of it.
        request.setAttribute(RESERVED_TOKENS_ATTR, estimatedTokens);

        RateLimitResult result = estimatedTokens > 0
                ? rateLimiter.checkLimit(apiKey, estimatedTokens, override)
                : rateLimiter.checkLimit(apiKey, override);
        if (result == null) {
            result = RateLimitResult.allow();
        }
        request.setAttribute(RESERVATION_ID_ATTR, result.reservationId());

        if (!result.allowed()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            request.setAttribute(AccessLogFilter.ATTR_ERROR_CODE, "rate_limit_exceeded");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            response.setHeader("X-RateLimit-Retry-After-Seconds", String.valueOf(result.retryAfterSeconds()));

            String traceId = response.getHeader(TraceIdFilter.HEADER);
            Map<String, Object> errorObj = new LinkedHashMap<>();
            errorObj.put("message", result.reason());
            errorObj.put("type", "rate_limit_error");
            errorObj.put("code", "rate_limit_exceeded");
            errorObj.put("trace_id", traceId != null ? traceId : "");

            // Add token-specific headers when token limiting is active
            RateLimitErrorDetail detail = result.detail();
            if (detail != null && "tokens".equals(detail.getLimitedResource())) {
                response.setHeader("X-RateLimit-Tokens-Limit", String.valueOf(detail.getLimit()));
                response.setHeader("X-RateLimit-Tokens-Remaining", String.valueOf(detail.getRemaining()));
            }
            if (detail != null) {
                Map<String, Object> rateLimitInfo = new LinkedHashMap<>();
                if (detail.getLimitedResource() != null) {
                    rateLimitInfo.put("limited_resource", detail.getLimitedResource());
                }
                if (detail.getLimitType() != null) {
                    rateLimitInfo.put("limit_type", detail.getLimitType());
                }
                rateLimitInfo.put("limit", detail.getLimit());
                rateLimitInfo.put("remaining", detail.getRemaining());
                rateLimitInfo.put("retry_after_seconds", detail.getRetryAfterSeconds());
                if (detail.getResetAt() != null) {
                    rateLimitInfo.put("reset_at", detail.getResetAt().toString());
                    response.setHeader("X-RateLimit-Reset", detail.getResetAt().toString());
                }
                errorObj.put("rate_limit", rateLimitInfo);
            }

            Map<String, Object> body = Map.of("error", errorObj);
            response.getWriter().write(JsonMapper.instance().writeValueAsString(body));
            return;
        }

        chain.doFilter(effectiveRequest, response);
    }

    private boolean isChatCompletionRequest(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().contains("/chat/completions");
    }

    /**
     * Request wrapper that replays a pre-read body for downstream consumers.
     */
    private static class CachedBodyRequestWrapper extends jakarta.servlet.http.HttpServletRequestWrapper {
        private final byte[] cachedBody;
        /** What is still unread on the wire after {@code cachedBody}; null when the body is wholly cached. */
        private final java.io.InputStream remainder;

        CachedBodyRequestWrapper(HttpServletRequest request, byte[] body) {
            this(request, body, null);
        }

        CachedBodyRequestWrapper(HttpServletRequest request, byte[] prefix, java.io.InputStream remainder) {
            super(request);
            this.cachedBody = prefix;
            this.remainder = remainder;
        }

        @Override
        public jakarta.servlet.ServletInputStream getInputStream() {
            java.io.InputStream in = remainder == null
                    ? new ByteArrayInputStream(cachedBody)
                    : new java.io.SequenceInputStream(new ByteArrayInputStream(cachedBody), remainder);
            return new jakarta.servlet.ServletInputStream() {
                private boolean finished;
                @Override public int read() throws IOException {
                    int b = in.read();
                    if (b < 0) finished = true;
                    return b;
                }
                @Override public int read(byte[] buf, int off, int len) throws IOException {
                    int n = in.read(buf, off, len);
                    if (n < 0) finished = true;
                    return n;
                }
                @Override public boolean isFinished() { return finished; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(jakarta.servlet.ReadListener listener) {}
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() { return remainder == null ? cachedBody.length : super.getContentLength(); }

        @Override
        public long getContentLengthLong() { return remainder == null ? cachedBody.length : super.getContentLengthLong(); }
    }

}