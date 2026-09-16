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

import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyGenerator;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.core.apikey.ApiKeyStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class ApiKeyAuthFilterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VALID_KEY = "gw_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String VALID_KEY_HASH = ApiKeyGenerator.hash(VALID_KEY);

    private ApiKeyRepository repository;
    private FilterChain chain;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(ApiKeyRepository.class);
        chain = mock(FilterChain.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
    }

    // --- require-api-key=false (default) ---

    @Test
    void permissiveMode_noHeader_passesThrough() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request).setAttribute(ApiKeyAuthFilter.API_KEY_ATTR, "anonymous");
    }

    @Test
    void permissiveMode_validKey_setsWorkspaceId() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
        when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(
                ApiKey.builder().id("key-1").workspaceId("acme").status(ApiKeyStatus.ACTIVE).build()));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request).setAttribute(ApiKeyAuthFilter.WORKSPACE_ID_ATTR, "acme");
        verify(request).setAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR, "key-1");
    }

    /**
     * A key that does not resolve is refused even in the permissive posture. Serving it as
     * anonymous would be the one credential failure a caller cannot detect: a {@code 200} with no
     * workspace behind it, so every per-workspace control silently does not apply. Sending no key
     * at all is different in kind and stays permitted; see
     * {@link #permissiveMode_noHeader_passesThrough()}.
     */
    @Test
    void permissiveMode_unknownKey_rejects401() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer unknown-key");
        when(repository.findByKeyHash(any())).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        var body = MAPPER.readValue(responseBody.toString(), Map.class);
        assertThat(((Map<?, ?>) body.get("error")).get("code")).isEqualTo("invalid_api_key");
    }

    /**
     * A keyless request is served anonymously when the flag is off. That is the development
     * convenience the flag exists for, and refusing unknown keys does not take it away.
     */
    @Test
    void permissiveMode_stillAllowsAKeylessRequest() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request).setAttribute(ApiKeyAuthFilter.API_KEY_ATTR, "anonymous");
    }

    @Test
    void permissiveMode_revokedKey_rejects401() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
        when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(
                ApiKey.builder().id("key-1").workspaceId("acme").status(ApiKeyStatus.REVOKED).build()));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        var body = MAPPER.readValue(responseBody.toString(), Map.class);
        assertThat(((Map<?, ?>) body.get("error")).get("code")).isEqualTo("api_key_revoked");
    }

    @Test
    void permissiveMode_expiredKey_rejects401() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
        when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(
                ApiKey.builder().id("key-1").workspaceId("acme").status(ApiKeyStatus.ACTIVE)
                        .expiresAt(Instant.now().minusSeconds(3600)).build()));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(401);
        var body = MAPPER.readValue(responseBody.toString(), Map.class);
        assertThat(((Map<?, ?>) body.get("error")).get("code")).isEqualTo("api_key_expired");
    }

    // --- require-api-key=true ---

    @Test
    void strictMode_noHeader_rejects401() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, true);
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(401);
        verify(chain, never()).doFilter(any(), any());
        var body = MAPPER.readValue(responseBody.toString(), Map.class);
        assertThat(((Map<?, ?>) body.get("error")).get("code")).isEqualTo("api_key_required");
    }

    @Test
    void strictMode_unknownKey_rejects401() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, true);
        when(request.getHeader("Authorization")).thenReturn("Bearer unknown-key");
        when(repository.findByKeyHash(any())).thenReturn(Optional.empty());

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(401);
        var body = MAPPER.readValue(responseBody.toString(), Map.class);
        assertThat(((Map<?, ?>) body.get("error")).get("code")).isEqualTo("invalid_api_key");
    }

    @Test
    void strictMode_validKey_passesThrough() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, true);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
        when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(
                ApiKey.builder().id("key-1").workspaceId("acme").status(ApiKeyStatus.ACTIVE).build()));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request).setAttribute(ApiKeyAuthFilter.WORKSPACE_ID_ATTR, "acme");
    }

    // --- no repository configured ---

    /**
     * A request with no repository to check against still passes: the gateway is not in a position
     * to judge, which is a different condition from having checked and failed. The raw token must
     * not be put on the request, because that attribute is the rate limiter's bucket identity and
     * would turn a live API key into a plaintext key name in whatever store the limiter uses.
     */
    @Test
    void noRepository_passesThroughWithoutPuttingTheTokenOnTheRequest() throws Exception {
        var filter = new ApiKeyAuthFilter(null, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer some-token");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request).setAttribute(ApiKeyAuthFilter.API_KEY_ATTR,
                "sha256:" + ApiKeyGenerator.hash("some-token"));
        verify(request, never()).setAttribute(ApiKeyAuthFilter.API_KEY_ATTR, "some-token");
    }

    // --- non-v1 paths are skipped ---

    @Test
    void nonV1Path_skipsFilter() {
        var filter = new ApiKeyAuthFilter(repository, true);
        when(request.getRequestURI()).thenReturn("/status");

        assertThat(filter.shouldNotFilter(request)).isTrue();
    }

    @Test
    void encodedOrMatrixV1Prefix_neverSkipsTheFilter() {
        var filter = new ApiKeyAuthFilter(repository, true);
        for (String uri : new String[]{"/v%31/chat/completions", "/v1%2Fchat/completions", "/v1;x/chat/completions"}) {
            when(request.getRequestURI()).thenReturn(uri);
            assertThat(filter.shouldNotFilter(request)).as(uri).isFalse();
        }
    }

    // ------------------------------------------------------------------ scopes are enforced

    private ApiKey scopedKey(String... scopes) {
        return ApiKey.builder().id("key-1").workspaceId("acme").status(ApiKeyStatus.ACTIVE)
                .scopes(java.util.List.of(scopes)).build();
    }

    @Test
    void scopedKey_offItsFamily_rejects403_asAPermissionError() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
        when(request.getRequestURI()).thenReturn("/v1/chat/completions");
        when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(scopedKey("embeddings:write")));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(any(), any());
        assertThat(responseBody.toString()).contains("api_key_scope").contains("permission_error").contains("completions:write");
    }

    @Test
    void scopedKey_onItsFamily_passes() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
        when(request.getRequestURI()).thenReturn("/v1/embeddings");
        when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(scopedKey("embeddings:write")));

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request).setAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR, "key-1");
    }

    @Test
    void unscopedKey_isUnrestricted_onEveryFamily() throws Exception {
        for (String uri : new String[]{"/v1/chat/completions", "/v1/embeddings", "/v1/batches", "/v1/models"}) {
            setUp();
            var filter = new ApiKeyAuthFilter(repository, false);
            when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
            when(request.getRequestURI()).thenReturn(uri);
            when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(
                    ApiKey.builder().id("key-1").workspaceId("acme").status(ApiKeyStatus.ACTIVE).build()));

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    @Test
    void unknownScope_grantsNothing() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
        when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(scopedKey("completion:write")));   // a typo

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(403);
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void aNonCanonicalPath_isRefusedBeforeScopeIsJudged() throws Exception {
        // /v1/%63hat/completions routes to the chat handler but matches no scope family; rather than
        // decode here, any percent-escape, matrix parameter, empty or dot segment under /v1 is a 400.
        for (String uri : new String[]{"/v1/%63hat/completions", "/v1/chat;x=1/completions", "/v1//chat/completions", "/v1/./chat/completions", "/v1/../v1/chat/completions", "/v1/chat\\completions"}) {
            setUp();
            var filter = new ApiKeyAuthFilter(repository, false);
            when(request.getRequestURI()).thenReturn(uri);
            when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
            when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(scopedKey("embeddings:write")));

            filter.doFilterInternal(request, response, chain);

            verify(response).setStatus(400);
            verify(chain, never()).doFilter(any(), any());
            assertThat(responseBody.toString()).contains("invalid_path");
        }
        assertThat(ApiKeyAuthFilter.isCanonical("/v1/chat/completions")).isTrue();
        assertThat(ApiKeyAuthFilter.isCanonical("/v1/batches/01J8ZK4M")).isTrue();
    }

    // --- the webhook approval endpoint, which carries its own credential ---

    @Test
    void webhookApproval_reachesTheControllerWithNoKey_evenWhenAKeyIsRequired() throws Exception {
        // The link is clicked by a person reading a chat message, who holds no gateway key, so the
        // endpoint has to work in the posture where a key is required.
        var filter = new ApiKeyAuthFilter(repository, true);
        when(request.getRequestURI()).thenReturn("/v1/webhooks/actions/approve");
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(response, never()).setStatus(anyInt());
    }

    @Test
    void webhookApproval_isNotConsultedAgainstTheKeyStore_evenWhenAKeyIsSent() throws Exception {
        // A key means nothing on this path, valid or not: the signed token is the whole
        // authorisation and the endpoint reads no workspace. Serving a bad one concedes nothing,
        // because sending none already reaches the controller.
        var filter = new ApiKeyAuthFilter(repository, true);
        when(request.getRequestURI()).thenReturn("/v1/webhooks/actions/deny");
        when(request.getHeader("Authorization")).thenReturn("Bearer gw_not_a_real_key");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(repository, never()).findByKeyHash(any());
    }

    @Test
    void theExemptionIsExact_soNothingElseUnderV1IsFreed() throws Exception {
        // This path takes an unauthenticated, state-changing action from the public internet, so
        // the exemption is an exact match and every near miss still needs a key.
        for (String uri : new String[] {
                "/v1/webhooks/actions",              // the collection, no action
                "/v1/webhooks/actions/",             // trailing slash, empty action
                "/v1/webhooks/actions/approve/more", // deeper than one segment
                "/v1/webhooks/actions-of-mine",      // a prefix is not a path
                "/v1/webhooks/subscriptions",
                "/v1/chat/completions" }) {
            var freshChain = mock(FilterChain.class);
            var freshResponse = mock(HttpServletResponse.class);
            when(freshResponse.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
            var filter = new ApiKeyAuthFilter(repository, true);
            when(request.getRequestURI()).thenReturn(uri);
            when(request.getHeader("Authorization")).thenReturn(null);

            filter.doFilterInternal(request, freshResponse, freshChain);

            assertThat(ApiKeyAuthFilter.isWebhookApprovalAction(uri)).as(uri).isFalse();
            verify(freshChain, never()).doFilter(any(), any());
        }
    }

    @Test
    void theExemptionIsBelowTheCanonicalCheck_soAnEncodedSpellingCannotUseIt() throws Exception {
        // /v1/%77ebhooks/actions/approve routes to the same handler while matching no literal, so
        // the canonical refusal has to come first or the exact match is bypassable.
        var filter = new ApiKeyAuthFilter(repository, true);
        when(request.getRequestURI()).thenReturn("/v1/%77ebhooks/actions/approve");
        when(request.getHeader("Authorization")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(response).setStatus(400);
    }

    // --- the limiter's bucket is not the credential ------------------------------------

    /**
     * This attribute is handed to the rate limiter, which makes it a key name in whatever store
     * the limiter uses, so it is the key's opaque id and never the token itself.
     */
    @Test
    void aResolvedKeyIsBucketedByItsIdNotByTheToken() throws Exception {
        var filter = new ApiKeyAuthFilter(repository, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);
        when(repository.findByKeyHash(VALID_KEY_HASH)).thenReturn(Optional.of(
                ApiKey.builder().id("key-1").workspaceId("acme").status(ApiKeyStatus.ACTIVE).build()));

        filter.doFilterInternal(request, response, chain);

        verify(request).setAttribute(ApiKeyAuthFilter.API_KEY_ATTR, "key-1");
        verify(request, never()).setAttribute(ApiKeyAuthFilter.API_KEY_ATTR, VALID_KEY);
    }

    /**
     * With no repository nothing can resolve an id, and the token must still not be the bucket. The
     * hash is what a repository would have been searched by: it identifies the caller as precisely,
     * and authenticating with it is impossible without the preimage.
     */
    @Test
    void withNoRepositoryTheBucketIsTheHashNotTheToken() throws Exception {
        var filter = new ApiKeyAuthFilter(null, false);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + VALID_KEY);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(request).setAttribute(ApiKeyAuthFilter.API_KEY_ATTR, "sha256:" + VALID_KEY_HASH);
        verify(request, never()).setAttribute(ApiKeyAuthFilter.API_KEY_ATTR, VALID_KEY);
    }
}
