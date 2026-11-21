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
package com.dvarahq.providers.vertexai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.util.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * OAuth2 access tokens for Google Cloud, minted from a {@link GoogleCredential} and kept until shortly before they
 * expire.
 *
 * <p>A service account signs a JWT with its own key (RS256, by the JDK) and trades it at Google's token endpoint
 * for an access token with the {@code cloud-platform} scope, the JWT bearer grant. A user credential trades its
 * refresh token. The metadata server hands out the attached service account's token directly.
 *
 * <p>Each token is kept per credential and used until {@link #REFRESH_BEFORE_EXPIRY} before it expires; only one
 * call per credential mints a new one while the others wait for it. When minting fails and the old token has
 * not expired yet, the old token is used and the failure is logged, so a short outage at Google's token endpoint
 * does not fail calls that a still-valid token can make.
 *
 * <p>Tokens and keys are never logged and never put in an error message.
 */
public class GoogleAccessTokens {

    private static final Logger log = LoggerFactory.getLogger(GoogleAccessTokens.class);

    /** The scope Vertex AI takes. */
    static final String CLOUD_PLATFORM_SCOPE = "https://www.googleapis.com/auth/cloud-platform";
    /** Google's OAuth2 token endpoint. */
    public static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
    static final String JWT_BEARER_GRANT = "urn:ietf:params:oauth:grant-type:jwt-bearer";
    /** Google's limit on a self-signed token request's lifetime. */
    static final Duration ASSERTION_LIFETIME = Duration.ofHours(1);
    /** How long before expiry a token is replaced. Google's own client libraries use a few minutes too. */
    static final Duration REFRESH_BEFORE_EXPIRY = Duration.ofMinutes(5);
    /** A token response without {@code expires_in} is taken to last this long, Google's usual lifetime. */
    static final Duration DEFAULT_LIFETIME = Duration.ofHours(1);
    /**
     * Most credentials kept at once. Each workspace with its own key adds one; past the limit the cache starts
     * over rather than grow without end.
     */
    static final int MAX_CACHED = 10_000;
    private static final int REASON_LIMIT = 300;

    /** An access token and when it expires. {@code toString} never shows the token. */
    public record AccessToken(String value, Instant expiresAt) {
        @Override
        public String toString() {
            return "AccessToken[expiresAt=" + expiresAt + "]";
        }
    }

    private final RestClient http;
    private final String tokenUrl;
    private final Clock clock;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();

    /**
     * @param http     a client for absolute URLs, with no base URL of its own
     * @param tokenUrl Google's token endpoint; {@code null} or blank means {@link #DEFAULT_TOKEN_URL}. The
     *                 {@code token_uri} in a key file is not used, so a key file cannot send the gateway's request
     *                 somewhere else.
     */
    public GoogleAccessTokens(RestClient http, String tokenUrl) {
        this(http, tokenUrl, Clock.systemUTC());
    }

    GoogleAccessTokens(RestClient http, String tokenUrl, Clock clock) {
        this.http = http;
        this.tokenUrl = tokenUrl == null || tokenUrl.isBlank() ? DEFAULT_TOKEN_URL : tokenUrl.trim();
        this.clock = clock;
    }

    /**
     * A token for the credential named by {@code cacheKey}: the kept one while it is fresh, else a new one.
     *
     * @param cacheKey   names the credential; two different credentials must never share one
     * @param credential read only when a token has to be minted
     */
    public AccessToken token(String cacheKey, Supplier<GoogleCredential> credential) {
        Slot slot = slots.get(cacheKey);
        if (slot == null) {
            if (slots.size() >= MAX_CACHED) {
                slots.clear();
            }
            slot = slots.computeIfAbsent(cacheKey, k -> new Slot());
        }
        AccessToken kept = slot.token;
        if (fresh(kept)) {
            return kept;
        }
        slot.lock.lock();
        try {
            kept = slot.token;
            if (fresh(kept)) {
                return kept;
            }
            try {
                AccessToken minted = mint(credential.get());
                slot.token = minted;
                return minted;
            } catch (RuntimeException e) {
                if (kept != null && clock.instant().isBefore(kept.expiresAt())) {
                    log.warn("Could not refresh a Google access token; using the current one until it expires at {}: {}",
                            kept.expiresAt(), e.getMessage());
                    return kept;
                }
                throw e;
            }
        } finally {
            slot.lock.unlock();
        }
    }

    private boolean fresh(AccessToken token) {
        return token != null && clock.instant().isBefore(token.expiresAt().minus(REFRESH_BEFORE_EXPIRY));
    }

    AccessToken mint(GoogleCredential credential) {
        return switch (credential) {
            case GoogleCredential.ServiceAccount sa -> {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("grant_type", JWT_BEARER_GRANT);
                form.add("assertion", assertion(sa, clock.instant()));
                yield exchange(form);
            }
            case GoogleCredential.AuthorizedUser user -> {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("grant_type", "refresh_token");
                form.add("client_id", user.clientId());
                form.add("client_secret", user.clientSecret());
                form.add("refresh_token", user.refreshToken());
                yield exchange(form);
            }
            case GoogleCredential.MetadataServer metadata -> fromMetadataServer(metadata);
        };
    }

    /**
     * The signed JWT a service account sends for a token: its email as issuer, the cloud-platform scope, the token
     * endpoint as audience, valid for an hour.
     */
    String assertion(GoogleCredential.ServiceAccount sa, Instant now) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (sa.privateKeyId() != null) {
            header.put("kid", sa.privateKeyId());
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", sa.clientEmail());
        claims.put("scope", CLOUD_PLATFORM_SCOPE);
        claims.put("aud", tokenUrl);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(ASSERTION_LIFETIME).getEpochSecond());
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        try {
            String signingInput = b64.encodeToString(JsonMapper.instance().writeValueAsBytes(header)) + "."
                    + b64.encodeToString(JsonMapper.instance().writeValueAsBytes(claims));
            Signature rs256 = Signature.getInstance("SHA256withRSA");
            rs256.initSign(sa.privateKey());
            rs256.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + b64.encodeToString(rs256.sign());
        } catch (GeneralSecurityException | IOException e) {
            throw new GatewayException("PROVIDER_ERROR",
                    "Vertex AI: could not sign the service account's token request (" + e.getClass().getSimpleName() + ")");
        }
    }

    private AccessToken exchange(MultiValueMap<String, String> form) {
        try {
            String answer = http.post()
                    .uri(tokenUrl)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        throw refused("Google's token endpoint", res);
                    })
                    .body(String.class);
            return read(answer, "Google's token endpoint");
        } catch (RestClientException e) {
            throw new GatewayException("PROVIDER_ERROR",
                    "Vertex AI: could not reach Google's token endpoint (" + e.getClass().getSimpleName() + ")");
        }
    }

    private AccessToken fromMetadataServer(GoogleCredential.MetadataServer metadata) {
        try {
            String answer = http.get()
                    .uri(metadata.baseUrl() + "/computeMetadata/v1/instance/service-accounts/default/token")
                    .header("Metadata-Flavor", "Google")
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        throw refused("The metadata server", res);
                    })
                    .body(String.class);
            return read(answer, "The metadata server");
        } catch (RestClientException e) {
            throw new GatewayException("CREDENTIAL_NOT_FOUND", "Vertex AI: no Google credentials found. Set "
                    + "VERTEX_AI_CREDENTIALS_FILE or GOOGLE_APPLICATION_CREDENTIALS to a key file, or run on Google "
                    + "Cloud with a service account attached (the metadata server could not be reached)");
        }
    }

    /**
     * Reads the token from the answer's text with the gateway's own JSON mapper, not the client's message
     * converters, which an application may set up for a different Jackson.
     */
    private AccessToken read(String text, String source) {
        JsonNode answer;
        try {
            answer = text == null ? null : JsonMapper.instance().readTree(text);
        } catch (IOException e) {
            answer = null;
        }
        JsonNode value = answer == null ? null : answer.get("access_token");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new GatewayException("PROVIDER_ERROR", "Vertex AI: " + source + " answered with no access_token");
        }
        JsonNode expiresIn = answer.get("expires_in");
        Duration lifetime = expiresIn != null && expiresIn.canConvertToLong() && expiresIn.asLong() > 0
                ? Duration.ofSeconds(expiresIn.asLong()) : DEFAULT_LIFETIME;
        return new AccessToken(value.asText(), clock.instant().plus(lifetime));
    }

    /**
     * The error for a refused token request. Google's {@code error} and {@code error_description} are kept: they
     * say what is wrong with the credential ("invalid_grant: Invalid JWT Signature.") and carry no secret.
     */
    private static GatewayException refused(String source, ClientHttpResponse res) {
        int status;
        try {
            status = res.getStatusCode().value();
        } catch (IOException e) {
            status = -1;
        }
        String reason = "";
        try (InputStream in = res.getBody()) {
            JsonNode body = JsonMapper.instance().readTree(in.readNBytes(8192));
            if (body != null) {
                String error = body.path("error").isTextual() ? body.path("error").asText() : null;
                String description = body.path("error_description").asText(null);
                reason = error == null ? "" : ": " + error + (description == null ? "" : ": " + description);
            }
        } catch (IOException | RuntimeException ignored) {
            // No readable reason; the status still says what happened.
        }
        reason = reason.replaceAll("\\p{Cntrl}+", " ");
        if (reason.length() > REASON_LIMIT) {
            reason = reason.substring(0, REASON_LIMIT) + "…";
        }
        String message = "Vertex AI: " + source + " refused the access-token request with " + status + reason;
        log.warn(message);
        return new GatewayException("PROVIDER_ERROR", message);
    }

    /** One credential's token, and the lock that lets only one call mint it. */
    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        volatile AccessToken token;
    }
}
