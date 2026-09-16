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
import com.dvarahq.server.metrics.GatewayMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Servlet filter that records Prometheus metrics for every request.
 * Captures latency, request count, and token usage by enriching from
 * request attributes set by controllers and the dispatcher.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class MetricsFilter extends OncePerRequestFilter {

    private final GatewayMetrics metrics;

    public MetricsFilter(GatewayMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !GatewayPlane.LLM.owns(request.getRequestURI());
    }

    /** See {@link AccessLogFilter#recordsNow}: a stream is metered on its async dispatch. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        AccessLogFilter.stampStart(request);

        try {
            chain.doFilter(request, response);
        } finally {
            // No early return here: a return inside finally would swallow what the chain threw.
            if (AccessLogFilter.recordsNow(request)) {
                record(request, response, startNanos);
            }
        }
    }

    private void record(HttpServletRequest request, HttpServletResponse response, long startNanos) {
        {
            long durationNanos = AccessLogFilter.elapsedNanos(request, startNanos);

            String model = attr(request, AccessLogFilter.ATTR_MODEL);
            String provider = attr(request, AccessLogFilter.ATTR_PROVIDER);
            String workspace = attr(request, "workspaceId");
            String status = String.valueOf(response.getStatus());

            metrics.recordRequest(
                    workspace != null ? workspace : "unknown",
                    model != null ? model : "unknown",
                    provider != null ? provider : "unknown",
                    status,
                    Duration.ofNanos(durationNanos)
            );

            // Record tokens if present
            String promptTokens = attr(request, AccessLogFilter.ATTR_TOKENS_PROMPT);
            String completionTokens = attr(request, AccessLogFilter.ATTR_TOKENS_COMPLETION);
            if (promptTokens != null || completionTokens != null) {
                metrics.recordTokens(
                        workspace != null ? workspace : "unknown",
                        model != null ? model : "unknown",
                        parseIntSafe(promptTokens),
                        parseIntSafe(completionTokens)
                );
            }

            // Record provider error if present
            String errorCode = attr(request, AccessLogFilter.ATTR_ERROR_CODE);
            if (errorCode != null && provider != null) {
                metrics.recordProviderError(provider, errorCode);
            }
        }
    }

    private static String attr(HttpServletRequest request, String name) {
        Object val = request.getAttribute(name);
        return val != null ? val.toString() : null;
    }

    private static int parseIntSafe(String value) {
        if (value == null) return 0;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}