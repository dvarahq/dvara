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
import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyGenerator;
import com.dvarahq.core.apikey.ApiKeyScope;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.core.apikey.ApiKeyStatus;
import com.dvarahq.core.util.JsonMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Servlet filter that resolves the {@code Authorization: Bearer} token to a
 * workspace via the {@link ApiKeyRepository}. Sets {@code workspaceId} and
 * {@code apiKeyId} as request attributes for all downstream filters and
 * controllers.
 *
 * <p>Every request under {@code /v1} carries a key, and the key must be valid: a request with no
 * key, a revoked key, an expired key or a key absent from the store is refused with {@code 401}.
 * There is no posture in which a keyless request is served. A request that reached a controller
 * without a key would have no workspace, and every control in the gateway — policy, PII action,
 * guardrail action, rate limits, suspension, credentials, batch and cache tenancy — resolves through
 * the workspace, so it would run against none of them while spending the operator's provider
 * credentials. Keys are minted by the operator with {@code --generate-key}; the gateway never
 * mints one at runtime.
 *
 * <p>Two paths are outside this rule, each because it carries a different credential: the webhook
 * approval action, whose per-approval signed token is the whole authorisation, and the actuator,
 * which is not under {@code /v1} and has its own keys.
 *
 * <p>Runs after the trace, access-log, metrics and audit filters and before rate limiting, so
 * workspace context is available to every subsequent filter.
 */
@Component
// After the access-log, metrics and audit filters (+1..+3): a refusal here returns without calling
// the chain, and those filters record it only because they wrap this one.
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /**
     * The rate limiter's bucket identity for this request, never the bearer token: the limiter
     * passes whatever it is given straight to its store, where a token would sit in plaintext.
     *
     * <p>It holds the key's opaque id, the same value usage, cost and budget rows carry. It is set
     * only after the key resolved, so a request that reaches a controller always has one.
     *
     * <p>Stamped once, here, so the reservation and the settlement cannot key differently.
     */
    public static final String API_KEY_ATTR = "apiKey";
    public static final String WORKSPACE_ID_ATTR = "workspaceId";
    public static final String API_KEY_ID_ATTR = "apiKeyId";

    /**
     * What the access log, the audit record and the rate limiter name a request that carries no
     * credential. Only two kinds of request are in that state past this filter: one being refused
     * here, and one on the webhook approval path. No served request under {@code /v1} is.
     *
     * <p>Reserved: {@code GatewayYamlLoader} refuses an {@code api_keys} entry with this name, so
     * the marker can never be mistaken for a key's id in a log line or a rate-limit bucket.
     */
    public static final String UNAUTHENTICATED = "unauthenticated";

    /**
     * The id of the key a served request carries. Every request under {@code /v1} has one once
     * this filter has run; a request without one reached a controller or a filter this filter did
     * not guard, which is a wiring fault, and is refused rather than served under a made-up
     * identity.
     */
    public static String requiredApiKeyId(HttpServletRequest request) {
        return required(request, API_KEY_ID_ATTR, "API key");
    }

    /** The workspace a served request belongs to; see {@link #requiredApiKeyId}. */
    public static String requiredWorkspaceId(HttpServletRequest request) {
        return required(request, WORKSPACE_ID_ATTR, "workspace");
    }

    /**
     * The rate limiter's bucket for a served request ({@link #API_KEY_ATTR}), read by whatever
     * settles a reservation, so the settlement keys exactly as the reservation did; see
     * {@link #requiredApiKeyId}.
     */
    public static String requiredLimiterKey(HttpServletRequest request) {
        return required(request, API_KEY_ATTR, "API key");
    }

    private static String required(HttpServletRequest request, String attribute, String what) {
        Object value = request.getAttribute(attribute);
        if (value instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalStateException("A request to " + request.getRequestURI() + " has no " + what
                + " on it, so it was not authenticated: ApiKeyAuthFilter did not run before it. The"
                + " filter is registered by GatewayRuntimeAutoConfiguration; an application that"
                + " serves /v1 must not exclude it.");
    }

    private final ApiKeyRepository apiKeyRepository;

    /**
     * @param apiKeyRepository the store every presented key is resolved against. Required: a
     *                         gateway with no key store could authenticate nobody, and a filter that
     *                         waved requests through in that case would be indistinguishable from
     *                         one that had checked them. The store is wired by the configuration
     *                         source in use (the {@code gateway.yaml} store in this build, which
     *                         exists even when the file does not).
     */
    public ApiKeyAuthFilter(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = Objects.requireNonNull(apiKeyRepository,
                "ApiKeyAuthFilter needs an ApiKeyRepository: no API key store is configured, so no "
                        + "request under /v1 could be authenticated. Configure a key store; there is "
                        + "no setting that serves requests without one.");
        log.info("ApiKeyAuthFilter initialized: repository={}", apiKeyRepository.getClass().getSimpleName());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Make this decision with the same decoded first segment Spring matches. Looking only for
        // the raw "/v1/" prefix lets /v%31/chat/completions skip this filter before the canonical
        // path check below, even though Spring routes it to /v1/chat/completions.
        return !targetsV1(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // scope matching reads the request path, and a percent-encoded or matrix-parameter
        // spelling of a governed path (/v1/%63hat/completions, /v1/chat;x=1/completions) routes to the
        // same handler while matching no family. Rather than re-implement the container's decoding,
        // a /v1 path that is not in canonical form is refused outright: no endpoint here has a
        // segment that needs encoding, so there is no legitimate request to lose.
        String uri = request.getRequestURI();
        if (uri != null && !isCanonical(uri)) {
            reject(response, request, HttpStatus.BAD_REQUEST,
                    "Request path is not in canonical form (percent-encoding, matrix parameters, empty or dot segments are not accepted under /v1).",
                    "invalid_path", "invalid_request_error");
            return;
        }
        // The webhook approval endpoint carries its own credential and takes no API key: its links
        // are delivered to a person, in a chat or mail integration, who holds no gateway key, and
        // the token signed per approval is the whole authorisation. Exempt outright rather than
        // "validate a key if one is sent", since a key means nothing there.
        //
        // The match is exact, and sits below the canonical check on purpose: this takes an
        // unauthenticated, state-changing action from the public internet, so a prefix match must
        // not free a neighbouring path, and a percent-encoded spelling cannot creep past.
        if (isWebhookApprovalAction(uri)) {
            chain.doFilter(request, response);
            return;
        }

        String bearerToken = extractBearerToken(request);

        if (bearerToken == null) {
            // The message says how to get a key and nothing about which keys or workspaces exist.
            reject(response, request, HttpStatus.UNAUTHORIZED,
                    "API key is required. Send it as Authorization: Bearer <your-api-key>. "
                            + "The operator mints one with --generate-key.",
                    "api_key_required");
            return;
        }

        // Look up the API key by hash
        String keyHash = ApiKeyGenerator.hash(bearerToken);
        Optional<ApiKey> found = apiKeyRepository.findByKeyHash(keyHash);

        if (found.isEmpty()) {
            reject(response, request, HttpStatus.UNAUTHORIZED,
                    "Invalid API key.",
                    "invalid_api_key");
            return;
        }

        ApiKey apiKey = found.get();

        if (apiKey.getStatus() != ApiKeyStatus.ACTIVE) {
            reject(response, request, HttpStatus.UNAUTHORIZED,
                    "API key has been revoked.",
                    "api_key_revoked");
            return;
        }

        if (apiKey.getExpiresAt() != null && apiKey.getExpiresAt().isBefore(Instant.now())) {
            reject(response, request, HttpStatus.UNAUTHORIZED,
                    "API key has expired.",
                    "api_key_expired");
            return;
        }

        // A key with no scopes is unrestricted; one with scopes may call only the families it
        // names (ApiKeyScope). 403, not 401: the caller authenticated fine and is refused for what
        // the key may do.
        if (!ApiKeyScope.permits(apiKey.getScopes(), request.getRequestURI())) {
            reject(response, request, HttpStatus.FORBIDDEN,
                    "API key is not scoped for this endpoint. It carries " + apiKey.getScopes()
                            + "; this endpoint needs " + ApiKeyScope.forPath(request.getRequestURI()).map(ApiKeyScope::value).orElse("?") + ".",
                    "api_key_scope", "permission_error");
            return;
        }

        // Set workspace context for all downstream filters. The limiter's bucket is the key's id,
        // not the credential — see API_KEY_ATTR.
        request.setAttribute(API_KEY_ATTR, apiKey.getId());
        request.setAttribute(WORKSPACE_ID_ATTR, apiKey.getWorkspaceId());
        request.setAttribute(API_KEY_ID_ATTR, apiKey.getId());

        chain.doFilter(request, response);
    }

    private String extractBearerToken(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            String key = auth.substring(7).trim();
            if (!key.isEmpty()) {
                return key;
            }
        }
        return null;
    }

    /** Only a path with no percent-escapes, matrix parameters, empty or dot segments is judged for scope. */
    static boolean isCanonical(String uri) {
        if (uri.contains("%") || uri.contains(";") || uri.contains("\\") || uri.contains("//")) {
            return false;
        }
        for (String segment : uri.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Exactly {@code /v1/webhooks/actions/&#123;action&#125;} -- three fixed segments and one more,
     * nothing deeper and nothing longer. The action itself is not checked here; whether it is a real
     * one is the controller's question, and this only decides that no API key is asked for.
     */
    static boolean isWebhookApprovalAction(String uri) {
        if (uri == null) {
            return false;
        }
        String prefix = "/v1/webhooks/actions/";
        if (!uri.startsWith(prefix)) {
            return false;
        }
        String action = uri.substring(prefix.length());
        return !action.isEmpty() && action.indexOf('/') < 0;
    }

    /** Whether Spring can treat the request's first path segment as {@code v1}. */
    static boolean targetsV1(String uri) {
        if (uri == null || uri.isEmpty()) {
            return false;
        }
        final String decoded;
        try {
            // UriUtils has path semantics: unlike form decoding, '+' remains '+'. Decoding here is
            // only for filter selection; the raw URI is still what isCanonical rejects.
            decoded = UriUtils.decode(uri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            // A malformed escape cannot turn another first segment into v1. A literal v1 prefix
            // still belongs in this filter so it receives the normal invalid_path response.
            return GatewayPlane.LLM.owns(uri) || uri.startsWith("/v1;");
        }

        int segmentEnd = decoded.indexOf('/', 1);
        String firstSegment = segmentEnd >= 0 ? decoded.substring(1, segmentEnd) : decoded.substring(1);
        int matrixParameter = firstSegment.indexOf(';');
        if (matrixParameter >= 0) {
            firstSegment = firstSegment.substring(0, matrixParameter);
        }
        return firstSegment.equals("v1");
    }

    private void reject(HttpServletResponse response, HttpServletRequest request,
                        HttpStatus status, String message, String code) throws IOException {
        reject(response, request, status, message, code, "authentication_error");
    }

    private void reject(HttpServletResponse response, HttpServletRequest request,
                        HttpStatus status, String message, String code, String type) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // what the access log, metrics and audit event record for this refusal
        request.setAttribute(AccessLogFilter.ATTR_ERROR_CODE, code);

        String traceId = response.getHeader(TraceIdFilter.HEADER);
        Map<String, Object> errorObj = new LinkedHashMap<>();
        errorObj.put("message", message);
        errorObj.put("type", type);
        errorObj.put("code", code);
        errorObj.put("trace_id", traceId != null ? traceId : "");

        Map<String, Object> body = Map.of("error", errorObj);
        response.getWriter().write(JsonMapper.instance().writeValueAsString(body));
    }
}
