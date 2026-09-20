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

import com.dvarahq.core.credential.CredentialFingerprint;
import com.dvarahq.core.secret.SecretProvider;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.util.function.Function;

/**
 * A {@link ClientHttpRequestInterceptor} that resolves a credential from
 * {@link SecretProvider} on every request and sets a header value.
 * <p>
 * This enables credential rotation without restarting the application —
 * the secret is fetched fresh on each HTTP call.
 *
 * <pre>
 * // Example: Bearer auth for OpenAI
 * new CredentialInterceptor(secretProvider, "provider.openai.api-key",
 *         "Authorization", key -&gt; "Bearer " + key);
 *
 * // Example: x-api-key for Anthropic
 * new CredentialInterceptor(secretProvider, "provider.anthropic.api-key",
 *         "x-api-key", Function.identity());
 * </pre>
 */
public class CredentialInterceptor implements ClientHttpRequestInterceptor {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CredentialInterceptor.class);

    /** So a fleet-wide propagation gap is one line, not one per call. */
    private static final java.util.concurrent.atomic.AtomicBoolean LOST_WORKSPACE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private final SecretProvider secretProvider;
    private final String secretKey;
    private final String headerName;
    private final Function<String, String> headerFormatter;

    public CredentialInterceptor(SecretProvider secretProvider,
                                 String secretKey,
                                 String headerName,
                                 Function<String, String> headerFormatter) {
        this.secretProvider = secretProvider;
        this.secretKey = secretKey;
        this.headerName = headerName;
        this.headerFormatter = headerFormatter;
    }

    /**
     * Request attribute carrying the fingerprint of the credential this request went upstream with.
     * Read by the metering path, which writes it onto the usage record.
     */
    public static final String FINGERPRINT_ATTRIBUTE = "dvara.credentialFingerprint";

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String workspaceId = resolveWorkspaceId();
        String credential = secretProvider.requireSecret(secretKey, workspaceId);
        request.getHeaders().set(headerName, headerFormatter.apply(credential));
        recordFingerprint(credential);
        return execution.execute(request, body);
    }

    /**
     * Stamps the credential's fingerprint on the request, for egress attribution.
     *
     * <p>This is the one place that is both always on and holds the resolved secret, so every
     * provider that sends its secret as a header gets attribution here. Gemini, which puts its key
     * in the query string, and Bedrock, which signs with its keys, call this method themselves.
     *
     * <p>Best-effort and silent: a missing request scope means a background caller with no
     * workspace to attribute, and attribution must never be the reason a request fails.
     */
    public static void recordFingerprint(String credential) {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return;
        }
        String fingerprint = CredentialFingerprint.of(credential);
        if (fingerprint == null) {
            return;
        }
        try {
            attrs.setAttribute(FINGERPRINT_ATTRIBUTE, fingerprint, RequestAttributes.SCOPE_REQUEST);
        } catch (IllegalStateException requestIsGone) {
            // A streamed call reaches here after its request has finished. Attribution is
            // best-effort by design and this is the one case where it genuinely cannot be
            // recorded; failing the customer's call to note who we billed would be the wrong way
            // round.
        }
    }

    /** The fingerprint stamped by {@link #recordFingerprint}, or null if this request made no
     *  credential-bearing upstream call — a cache hit, or a provider needing no secret. */
    public static String resolveFingerprint() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        try {
            return (String) attrs.getAttribute(FINGERPRINT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        } catch (IllegalStateException requestIsGone) {
            // Same reasoning as recording it: this is read on the metering path, which on a stream
            // runs after the request has finished. Hardening the write alone would only have moved
            // the failure a few frames later. Not knowing which credential was used costs a column
            // on a usage row; throwing here would cost the caller their answer.
            return null;
        }
    }

    /**
     * Which workspace this call is being made for, so its own credential is used.
     *
     * <p>Asks {@link WorkspaceScope} first, and only then the servlet request. That order is the
     * fix for a defect worth remembering: on a streamed response the upstream call happens after
     * the controller has handed back the emitter, and by then the request has been closed. Asking
     * it anything throws, so every streaming call through a provider that carries a credential
     * failed before it reached the upstream at all.
     *
     * <p>The value is known long before that — the API-key filter works it out while the request is
     * still ordinary — so it is carried to the call rather than looked up at the last moment.
     *
     * <p><b>It would have been easy, and wrong, to catch that exception and return null.</b> The
     * crash stops, and the call quietly goes out on the installation's own key instead: one
     * customer's traffic served and billed against another's credential, with nothing in the logs.
     * A loud failure is much the better of the two. Where the workspace genuinely cannot be
     * recovered this says so once, and returns null only because a scheduler or a probe has no
     * workspace to speak of and must still be able to call.
     */
    public static String resolveWorkspaceId() {
        String carried = WorkspaceScope.current();
        if (carried != null) {
            return carried;
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;   // no request at all: a scheduler, a probe, a test
        }
        try {
            return (String) attrs.getAttribute("workspaceId", RequestAttributes.SCOPE_REQUEST);
        } catch (IllegalStateException requestIsGone) {
            // There was a request, it has been closed, and nobody carried the workspace across.
            // That is a propagation gap rather than a workspace-less caller, and it is worth
            // saying so: the call is about to be made without a workspace, which strict BYOK will
            // refuse and a permissive install will serve on the shared credential.
            if (LOST_WORKSPACE_LOGGED.compareAndSet(false, true)) {
                log.warn("The workspace for a provider call could not be recovered: the request had "
                        + "already finished and no workspace was carried with the call. The call "
                        + "proceeds without one. This will not be logged again.");
            }
            return null;
        }
    }
}