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
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Structured access log filter that emits a single JSON log line per request
 * with all gateway-relevant context fields in the MDC.
 *
 * <p>Runs after {@link TraceIdFilter} (which sets {@code trace_id} in MDC)
 * and captures: method, path, status, latency_ms, api_key (the key's opaque id, or
 * {@code anonymous} — never the bearer token), model, provider, cache status, and
 * token usage.</p>
 */
@Component
// The recording filters run before auth and rate limiting, so a refused request is recorded too.
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    public static final String ATTR_MODEL = "gateway.model";
    public static final String ATTR_PROVIDER = "gateway.provider";
    public static final String ATTR_CACHE_STATUS = "gateway.cacheStatus";
    public static final String ATTR_TOKENS_PROMPT = "gateway.tokensPrompt";
    public static final String ATTR_TOKENS_COMPLETION = "gateway.tokensCompletion";
    public static final String ATTR_TOKENS_TOTAL = "gateway.tokensTotal";
    public static final String ATTR_STREAM = "gateway.stream";
    public static final String ATTR_ERROR_CODE = "gateway.errorCode";
    /**
     * When the gateway first saw the request — stamped by the trace filter, the first thing to run,
     * on the initial dispatch, and read on the async one. Shared by the access-log, metrics
     * and audit filters so all three report the same duration.
     */
    public static final String ATTR_START_NANOS = "gateway.startNanos";

    /**
     * A streaming request hands its emitter back and the chain returns while the upstream has not
     * even been opened; the response is finished later, on the async dispatch that completing the
     * emitter triggers. Recording at the first return logged every stream as 200 with the
     * preparation time as its latency and no provider, tokens or error. So the initial pass
     * of an async request records nothing, the async dispatch records, and a synchronous request
     * records at its return as before.
     */
    static boolean recordsNow(HttpServletRequest request) {
        return !request.isAsyncStarted();
    }

    /** The request's duration so far, from the stamp the initial dispatch left. */
    static long elapsedNanos(HttpServletRequest request, long fallbackStartNanos) {
        Object start = request.getAttribute(ATTR_START_NANOS);
        long startNanos = start instanceof Long l ? l : fallbackStartNanos;
        return System.nanoTime() - startNanos;
    }

    static long elapsedMillis(HttpServletRequest request, long fallbackStartNanos) {
        return elapsedNanos(request, fallbackStartNanos) / 1_000_000L;
    }

    static void stampStart(HttpServletRequest request) {
        if (request.getAttribute(ATTR_START_NANOS) == null) {
            request.setAttribute(ATTR_START_NANOS, System.nanoTime());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !GatewayPlane.LLM.owns(request.getRequestURI());
    }

    /** See {@link #recordsNow}: the async dispatch is where a stream's record is written. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        stampStart(request);

        MDC.put("method", request.getMethod());
        MDC.put("path", request.getRequestURI());

        try {
            chain.doFilter(request, response);
        } finally {
            // No early return here: a return inside finally would swallow what the chain threw.
            if (recordsNow(request)) {
                record(request, response, startNanos);
            }
            clearMdc();
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, long startNanos) {
        MDC.put("latency_ms", String.valueOf(elapsedMillis(request, startNanos)));
        MDC.put("status", String.valueOf(response.getStatus()));

        // Enrich from request attributes set by controllers/dispatcher
        putIfPresent(request, ATTR_MODEL, "model");
        putIfPresent(request, ATTR_PROVIDER, "provider");
        putIfPresent(request, ATTR_CACHE_STATUS, "cache_status");
        putIfPresent(request, ATTR_STREAM, "stream");
        putIfPresent(request, ATTR_TOKENS_PROMPT, "tokens_prompt");
        putIfPresent(request, ATTR_TOKENS_COMPLETION, "tokens_completion");
        putIfPresent(request, ATTR_TOKENS_TOTAL, "tokens_total");
        putIfPresent(request, ATTR_ERROR_CODE, "error_code");

        // Priority tier
        Object priorityTier = request.getAttribute("priorityTier");
        if (priorityTier != null) {
            MDC.put("priority_tier", priorityTier.toString());
        }

        // The key's opaque id, never the bearer token or a slice of it.
        MDC.put("api_key", attribution((String) request.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR)));

        // Workspace ID (from API key context, if available)
        String workspaceId = (String) request.getAttribute("workspaceId");
        MDC.put("workspace_id", workspaceId != null ? workspaceId : "unknown");

        log.info("Request completed");
    }

    /** Clean up all MDC keys we added (trace_id is cleaned by TraceIdFilter). */
    private static void clearMdc() {
        for (String key : new String[]{"method", "path", "latency_ms", "status", "model", "provider",
                "cache_status", "stream", "tokens_prompt", "tokens_completion", "tokens_total",
                "error_code", "priority_tier", "api_key", "workspace_id"}) {
            MDC.remove(key);
        }
    }

    private void putIfPresent(HttpServletRequest request, String attrName, String mdcKey) {
        Object value = request.getAttribute(attrName);
        if (value != null) {
            MDC.put(mdcKey, value.toString());
        }
    }

    /**
     * What the access log records as {@code api_key}: the key's opaque id, or {@code anonymous}.
     * The id is the same identifier budgets and usage rows carry, and it is not a secret, so it is
     * logged whole.
     */
    static String attribution(String apiKeyId) {
        return apiKeyId == null || apiKeyId.isBlank() ? "anonymous" : apiKeyId;
    }
}