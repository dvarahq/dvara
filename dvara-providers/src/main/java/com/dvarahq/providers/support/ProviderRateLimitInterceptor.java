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
package com.dvarahq.providers.support;

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.resilience.ProviderRateLimitTracker;
import com.dvarahq.core.credential.CredentialFingerprint;
import com.dvarahq.core.secret.SecretProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Outer {@link ClientHttpRequestInterceptor} that enforces the per-credential upstream rate-limit
 * tracker. Added to each provider's RestClient <em>before</em> its {@link CredentialInterceptor},
 * so it wraps the whole call: it resolves the same credential, keys the tracker by a fingerprint of
 * that secret (never the raw key), checks before forwarding and observes the response (200 and 429).
 *
 * <p>Fail-open throughout: if the credential can't be resolved, or the tracker errors, the call
 * proceeds unshed. A rate-limit optimisation must never block traffic or mask the real auth path.</p>
 */
public class ProviderRateLimitInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ProviderRateLimitInterceptor.class);

    private final SecretProvider secretProvider;
    private final String secretKey;
    private final String provider;
    private final ProviderRateLimitTracker tracker;
    private final AuditWriter auditWriter; // nullable

    public ProviderRateLimitInterceptor(SecretProvider secretProvider, String secretKey,
                                        String provider, ProviderRateLimitTracker tracker,
                                        AuditWriter auditWriter) {
        this.secretProvider = secretProvider;
        this.secretKey = secretKey;
        this.provider = provider;
        this.tracker = tracker;
        this.auditWriter = auditWriter;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String workspaceId = CredentialInterceptor.resolveWorkspaceId();
        String credentialId = resolveCredentialIdentity(workspaceId);
        if (credentialId != null) {
            try {
                var decision = tracker.check(provider, credentialId, tier(), estimatedInputTokens());
                if (!decision.allow()) {
                    long retryAfter = Math.max(1, decision.retryAfter().toSeconds());
                    emitShedAudit(workspaceId, decision.reason(), retryAfter);
                    throw new GatewayException("PROVIDER_RATE_LIMITED",
                            "Upstream " + provider + " rate limit near exhaustion; retry after "
                                    + retryAfter + "s (" + decision.reason() + ")",
                            retryAfter);
                }
            } catch (GatewayException e) {
                throw e; // the shed itself — propagate to the dispatcher's failover
            } catch (RuntimeException e) {
                log.debug("Provider rate-limit check errored for {} (failing open): {}", provider, e.toString());
            }
        }

        ClientHttpResponse response = execution.execute(request, body);

        if (credentialId != null) {
            try {
                tracker.observe(provider, credentialId, response.getStatusCode().value(),
                        response.getHeaders().toSingleValueMap());
            } catch (RuntimeException e) {
                log.debug("Provider rate-limit observe errored for {} (ignored): {}", provider, e.toString());
            }
        }
        return response;
    }

    /** A stable, non-secret handle for the resolved key, or {@code null} to skip (fail-open). */
    private String resolveCredentialIdentity(String workspaceId) {
        try {
            Optional<String> secret = secretProvider.getSecret(secretKey, workspaceId);
            return secret.map(CredentialFingerprint::of).orElse(null);
        } catch (RuntimeException e) {
            return null; // credential resolution problem → let the credential interceptor surface it
        }
    }

    /** {@code UPSTREAM_RATE_LIMIT_SHED} — one per shed, workspace-scoped. Best-effort; never breaks the call. */
    private void emitShedAudit(String workspaceId, String reason, long retryAfterSeconds) {
        if (auditWriter == null) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("provider", provider);
            payload.put("reason", reason);
            payload.put("retry_after_seconds", retryAfterSeconds);
            payload.put("workspace_tier", tier());
            auditWriter.write(new AuditEvent(Ids.newId(), Instant.now(), workspaceId,
                    "UPSTREAM_RATE_LIMIT_SHED", payload));
        } catch (RuntimeException e) {
            log.debug("Failed to emit UPSTREAM_RATE_LIMIT_SHED audit for {}: {}", provider, e.toString());
        }
    }


    private static String tier() {
        Object v = requestAttr("priorityTier");
        return v != null ? v.toString() : "standard";
    }

    private static int estimatedInputTokens() {
        Object v = requestAttr("estimatedInputTokens");
        if (v instanceof Number n) return n.intValue();
        return 0; // absent → the request/token-fraction shed still applies; only the per-call headroom skips
    }

    private static Object requestAttr(String name) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs != null ? attrs.getAttribute(name, RequestAttributes.SCOPE_REQUEST) : null;
    }

    // Exposed only to keep the header-flatten contract explicit for tests.
    static Map<String, String> flatten(org.springframework.http.HttpHeaders headers) {
        return headers.toSingleValueMap();
    }
}