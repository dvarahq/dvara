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
import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.ErrorEnvelope;
import com.dvarahq.core.id.Ids;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Servlet filter that writes GATEWAY_RESPONSE audit events after request completion.
 * Only intercepts /v1/* paths (API calls). Captures the trace id, model, provider, status,
 * latency, tokens, workspace, and error info from request attributes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
public class AuditResponseFilter extends OncePerRequestFilter {

    private final AuditWriter auditWriter;

    public AuditResponseFilter(AuditWriter auditWriter) {
        this.auditWriter = auditWriter;
    }

    /** See {@link AccessLogFilter#recordsNow}: a stream is audited on its async dispatch. */
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
            long latencyMs = AccessLogFilter.elapsedMillis(request, startNanos);

            Map<String, Object> payload = new LinkedHashMap<>();
            // The id the client saw in X-Trace-ID, so that id finds this record as it finds the access-log line.
            putIfPresent(payload, "trace_id", traceIdOf(request, response));
            putIfPresent(payload, "model", request.getAttribute(AccessLogFilter.ATTR_MODEL));
            putIfPresent(payload, "provider", request.getAttribute(AccessLogFilter.ATTR_PROVIDER));
            payload.put("status", response.getStatus());
            payload.put("latency_ms", latencyMs);
            // The API the call came in on, from a fixed list, so spend and failures can be read per API.
            payload.put("doorway", com.dvarahq.core.metering.Doorway.of(request.getRequestURI()).wireName());

            // The key's opaque id: the same value budgets, usage and cost rows carry.
            payload.put("api_key", AccessLogFilter.attribution(
                    (String) request.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR)));

            putIfPresent(payload, "tokens_total", request.getAttribute(AccessLogFilter.ATTR_TOKENS_TOTAL));
            // Whether the answer came from the response cache, and on a hit how close the cached request was,
            // so a disputed cached answer can be explained from the record.
            putIfPresent(payload, "cache_status", request.getAttribute(AccessLogFilter.ATTR_CACHE_STATUS));
            if ("HIT".equals(request.getAttribute(AccessLogFilter.ATTR_CACHE_STATUS))) {
                putIfPresent(payload, "cache_similarity", request.getAttribute(AccessLogFilter.ATTR_CACHE_SIMILARITY));
            }
            putIfPresent(payload, "error_code", request.getAttribute(AccessLogFilter.ATTR_ERROR_CODE));
            // Why it failed, when the provider said: its status, its own code, and the message, masked.
            putIfPresent(payload, "provider_status", request.getAttribute(AccessLogFilter.ATTR_PROVIDER_STATUS));
            putIfPresent(payload, "provider_error_code", request.getAttribute(AccessLogFilter.ATTR_PROVIDER_ERROR_CODE));
            putIfPresent(payload, "error_message", request.getAttribute(AccessLogFilter.ATTR_ERROR_MESSAGE));
            putIfPresent(payload, "policy_decision", request.getAttribute("policy.decision"));
            putIfPresent(payload, "policy_id", request.getAttribute("policy.policyId"));
            putIfPresent(payload, "policy_rule_id", request.getAttribute("policy.ruleId"));
            // Which Anthropic beta features the caller asked for: they change what the provider does.
            if (ErrorEnvelope.anthropic(request.getRequestURI())) {
                putIfPresent(payload, "anthropic_beta", request.getHeader("anthropic-beta"));
            }

            String workspaceId = (String) request.getAttribute("workspaceId");

            AuditEvent event = new AuditEvent(
                    Ids.newId(),
                    java.time.Instant.now(),
                    workspaceId,
                    "GATEWAY_RESPONSE",
                    payload);

            auditWriter.write(event);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !GatewayPlane.LLM.owns(path);
    }

    /** The trace id the trace filter gave this call: on the request, or else on the response header. */
    private static String traceIdOf(HttpServletRequest request, HttpServletResponse response) {
        Object onRequest = request.getAttribute(TraceIdFilter.ATTR);
        if (onRequest instanceof String id && !id.isBlank()) {
            return id;
        }
        String onResponse = response.getHeader(TraceIdFilter.HEADER);
        return onResponse == null || onResponse.isBlank() ? null : onResponse;
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

}