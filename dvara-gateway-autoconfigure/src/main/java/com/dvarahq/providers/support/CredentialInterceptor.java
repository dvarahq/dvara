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
        if (fingerprint != null) {
            attrs.setAttribute(FINGERPRINT_ATTRIBUTE, fingerprint, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** The fingerprint stamped by {@link #recordFingerprint}, or null if this request made no
     *  credential-bearing upstream call — a cache hit, or a provider needing no secret. */
    public static String resolveFingerprint() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs == null
                ? null
                : (String) attrs.getAttribute(FINGERPRINT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
    }

    /**
     * Extracts workspaceId from the current servlet request context.
     * Returns null when called outside a request scope (e.g., background jobs).
     */
    public static String resolveWorkspaceId() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            return (String) attrs.getAttribute("workspaceId", RequestAttributes.SCOPE_REQUEST);
        }
        return null;
    }
}