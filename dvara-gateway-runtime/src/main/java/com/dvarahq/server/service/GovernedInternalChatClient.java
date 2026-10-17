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
package com.dvarahq.server.service;

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.call.InternalCall;
import com.dvarahq.core.call.InternalChatClient;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.providers.support.WorkspaceScope;
import com.dvarahq.server.web.AccessLogFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runs the gateway's own model calls through {@link ChatExecutionService}, the path every caller's
 * chat request takes, on a request of their own ({@link InternalCallRequest}). The caller's request,
 * if one is bound to the thread, is put back afterwards untouched.
 */
@Component
public class GovernedInternalChatClient implements InternalChatClient {

    /** The audit event every internal call writes, refused or served. */
    public static final String AUDIT_EVENT = "INTERNAL_CALL";

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GovernedInternalChatClient.class);

    private final ChatExecutionService execution;
    private final AuditWriter auditWriter;

    public GovernedInternalChatClient(ChatExecutionService execution, AuditWriter auditWriter) {
        this.execution = execution;
        this.auditWriter = auditWriter;
    }

    @Override
    public ChatResponse chat(ChatRequest request, InternalCall call) {
        InternalCall running = InternalCall.current();
        if (running != null) {
            throw new IllegalStateException("An internal call of kind '" + call.kind()
                    + "' was started inside one of kind '" + running.kind() + "'; an internal call never starts another.");
        }
        HttpServletRequest httpRequest = InternalCallRequest.of(call);
        String traceId = Ids.newId();
        RequestAttributes callersRequest = RequestContextHolder.getRequestAttributes();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(httpRequest));
        long start = System.nanoTime();
        String errorCode = null;
        try (InternalCall.Scope ignored = call.open();
             WorkspaceScope.Scope alsoIgnored = WorkspaceScope.open(call.workspaceId())) {
            ChatExecutionService.Prepared prepared = execution.prepare(request, httpRequest, null, traceId);
            try {
                return execution.executeSync(prepared.request(), prepared.ctx(), httpRequest).response();
            } finally {
                execution.releasePriority(prepared.ctx());
            }
        } catch (GatewayException e) {
            errorCode = e.getCode();
            throw e;
        } catch (RuntimeException e) {
            errorCode = "INTERNAL_ERROR";
            throw e;
        } finally {
            // Written while the call's own request is still bound, so nothing that adds to an audit
            // record from the bound request mistakes the caller's for this one.
            try {
                audit(call, request, httpRequest, traceId, (System.nanoTime() - start) / 1_000_000L, errorCode);
            } catch (RuntimeException auditFailed) {
                log.warn("Could not write the audit record of an internal call (kind={}, workspace={}): {}",
                        call.kind(), call.workspaceId(), auditFailed.getMessage());
            }
            if (callersRequest == null) {
                RequestContextHolder.resetRequestAttributes();
            } else {
                RequestContextHolder.setRequestAttributes(callersRequest);
            }
        }
    }

    /** The same facts a caller's GATEWAY_RESPONSE record carries, plus what the call was for and what caused it. */
    private void audit(InternalCall call, ChatRequest request, HttpServletRequest httpRequest, String traceId,
                       long latencyMs, String errorCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", call.kind());
        payload.put("trace_id", traceId);
        putIfPresent(payload, "parent_trace_id", call.parentTraceId());
        Object served = httpRequest.getAttribute(ProviderDispatcher.SERVED_MODEL_ATTR);
        putIfPresent(payload, "model", served != null ? served : request.getModel());
        putIfPresent(payload, "provider", httpRequest.getAttribute(AccessLogFilter.ATTR_PROVIDER));
        payload.put("status", errorCode == null ? "OK" : "ERROR");
        payload.put("latency_ms", latencyMs);
        payload.put("api_key", call.apiKeyId());
        putIfPresent(payload, "tokens_total", httpRequest.getAttribute(AccessLogFilter.ATTR_TOKENS_TOTAL));
        putIfPresent(payload, "error_code", errorCode);
        putIfPresent(payload, "policy_decision", httpRequest.getAttribute("policy.decision"));
        putIfPresent(payload, "policy_id", httpRequest.getAttribute("policy.policyId"));
        putIfPresent(payload, "policy_rule_id", httpRequest.getAttribute("policy.ruleId"));
        auditWriter.write(new AuditEvent(Ids.newId(), Instant.now(), call.workspaceId(), AUDIT_EVENT, payload));
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
