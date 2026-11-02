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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Minting, keeping and refreshing Google access tokens, against a stub of Google's token endpoint. */
class GoogleAccessTokensTest {

    static final String TOKEN_URL = "https://oauth2.test/token";
    static final TestKeys KEYS = new TestKeys();

    private MockRestServiceServer google;
    private MutableClock clock;
    private GoogleAccessTokens tokens;
    /** Each form body the stub received, decoded. */
    private final List<Map<String, String>> forms = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        google = MockRestServiceServer.bindTo(builder).build();
        clock = new MutableClock(Instant.parse("2026-11-02T10:00:00Z"));
        tokens = new GoogleAccessTokens(builder.build(), TOKEN_URL, clock);
    }

    // ---- a service account signs its own request ------------------------------------------------------------

    @Test
    void aServiceAccountTradesASignedJwtForATokenWithTheCloudPlatformScope() {
        expectToken("ya29.first", 3600);

        GoogleAccessTokens.AccessToken token = tokens.token("sa", KEYS::serviceAccount);

        assertThat(token.value()).isEqualTo("ya29.first");
        assertThat(token.expiresAt()).isEqualTo(clock.instant().plusSeconds(3600));
        Map<String, String> form = forms.getFirst();
        assertThat(form.get("grant_type")).isEqualTo("urn:ietf:params:oauth:grant-type:jwt-bearer");
        String jwt = form.get("assertion");
        assertThat(KEYS.signed(jwt)).as("signed with the service account's key").isTrue();
        Map<?, ?> header = TestKeys.part(jwt, 0);
        assertThat(header.get("alg")).isEqualTo("RS256");
        assertThat(header.get("kid")).isEqualTo(TestKeys.KEY_ID);
        Map<?, ?> claims = TestKeys.part(jwt, 1);
        assertThat(claims.get("iss")).isEqualTo(TestKeys.CLIENT_EMAIL);
        assertThat(claims.get("scope")).isEqualTo("https://www.googleapis.com/auth/cloud-platform");
        assertThat(claims.get("aud")).as("the configured endpoint, never the key file's token_uri").isEqualTo(TOKEN_URL);
        long iat = ((Number) claims.get("iat")).longValue();
        assertThat(iat).isEqualTo(clock.instant().getEpochSecond());
        assertThat(((Number) claims.get("exp")).longValue() - iat).isEqualTo(3600);
        google.verify();
    }

    @Test
    void aJwtSignedByAnotherKeyDoesNotVerify_soTheCheckAboveCanFail() {
        TestKeys other = new TestKeys();
        String jwt = tokens.assertion(other.serviceAccount(), clock.instant());
        assertThat(KEYS.signed(jwt)).isFalse();
        assertThat(other.signed(jwt)).isTrue();
    }

    // ---- kept, refreshed before expiry -----------------------------------------------------------------------

    @Test
    void aTokenIsKept_andOnlyReplacedFiveMinutesBeforeItExpires() {
        expectToken("ya29.first", 3600);
        assertThat(tokens.token("sa", KEYS::serviceAccount).value()).isEqualTo("ya29.first");

        clock.advance(Duration.ofMinutes(54));
        assertThat(tokens.token("sa", KEYS::serviceAccount).value()).as("54 minutes in, still fresh").isEqualTo("ya29.first");
        google.verify();

        google.reset();
        expectToken("ya29.second", 3600);
        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertThat(tokens.token("sa", KEYS::serviceAccount).value()).as("inside the last five minutes").isEqualTo("ya29.second");
        assertThat(tokens.token("sa", KEYS::serviceAccount).value()).isEqualTo("ya29.second");
        google.verify();
    }

    @Test
    void twoCredentialsKeepTwoTokens() {
        google.expect(ExpectedCount.twice(), requestTo(TOKEN_URL)).andRespond(req -> withSuccess(
                "{\"access_token\":\"ya29." + forms.size() + "\",\"expires_in\":3600}", MediaType.APPLICATION_JSON)
                .createResponse(capture(req)));

        String a = tokens.token("a", KEYS::serviceAccount).value();
        String b = tokens.token("b", KEYS::serviceAccount).value();

        assertThat(a).isNotEqualTo(b);
        assertThat(tokens.token("a", KEYS::serviceAccount).value()).isEqualTo(a);
        google.verify();
    }

    @Test
    void aFailedRefreshKeepsUsingTheTokenUntilItExpires_thenFails() {
        expectToken("ya29.first", 3600);
        tokens.token("sa", KEYS::serviceAccount);
        google.reset();

        google.expect(ExpectedCount.twice(), requestTo(TOKEN_URL)).andRespond(withServerError());
        clock.advance(Duration.ofMinutes(57));
        assertThat(tokens.token("sa", KEYS::serviceAccount).value()).as("still valid for 3 minutes").isEqualTo("ya29.first");

        clock.advance(Duration.ofMinutes(4));
        assertThatThrownBy(() -> tokens.token("sa", KEYS::serviceAccount))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("refused the access-token request with 500");
        google.verify();
    }

    @Test
    void manyCallsAtOnceMintOneToken() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        RestClient slow = RestClient.builder().requestFactory((uri, httpMethod) -> {
            calls.incrementAndGet();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            MockClientHttpRequest request = new MockClientHttpRequest(httpMethod, uri);
            org.springframework.mock.http.client.MockClientHttpResponse response =
                    new org.springframework.mock.http.client.MockClientHttpResponse(
                            "{\"access_token\":\"ya29.once\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8),
                            org.springframework.http.HttpStatus.OK);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            request.setResponse(response);
            return request;
        }).build();
        GoogleAccessTokens shared = new GoogleAccessTokens(slow, TOKEN_URL, clock);

        List<Thread> threads = new ArrayList<>();
        List<String> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() -> seen.add(shared.token("sa", KEYS::serviceAccount).value())));
        }
        for (Thread t : threads) {
            t.join();
        }

        assertThat(calls.get()).isEqualTo(1);
        assertThat(seen).hasSize(8).containsOnly("ya29.once");
    }

    // ---- the other credentials -------------------------------------------------------------------------------

    @Test
    void aUserCredentialTradesItsRefreshToken() {
        expectToken("ya29.user", 3599);

        GoogleCredential user = new GoogleCredential.AuthorizedUser("client-1", "client-secret-1", "refresh-1");
        assertThat(tokens.token("user", () -> user).value()).isEqualTo("ya29.user");

        assertThat(forms.getFirst()).containsEntry("grant_type", "refresh_token").containsEntry("client_id", "client-1")
                .containsEntry("client_secret", "client-secret-1").containsEntry("refresh_token", "refresh-1");
    }

    @Test
    void theMetadataServerIsAskedWithItsHeader() {
        google.expect(requestTo("http://metadata.test/computeMetadata/v1/instance/service-accounts/default/token"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Metadata-Flavor", "Google"))
                .andRespond(withSuccess("{\"access_token\":\"ya29.metadata\",\"expires_in\":1800,\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON));

        GoogleAccessTokens.AccessToken token = tokens.token("m", () -> new GoogleCredential.MetadataServer("http://metadata.test"));

        assertThat(token.value()).isEqualTo("ya29.metadata");
        assertThat(token.expiresAt()).isEqualTo(clock.instant().plusSeconds(1800));
    }

    @Test
    void noMetadataServerMeansNoCredentials_andTheMessageSaysWhatToSet() {
        google.expect(requestTo("http://metadata.test/computeMetadata/v1/instance/service-accounts/default/token"))
                .andRespond(withException(new IOException("unknown host")));

        assertThatThrownBy(() -> tokens.token("m", () -> new GoogleCredential.MetadataServer("http://metadata.test")))
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.getCode()).isEqualTo("CREDENTIAL_NOT_FOUND"))
                .hasMessageContaining("GOOGLE_APPLICATION_CREDENTIALS");
    }

    // ---- errors carry Google's reason and never a secret -----------------------------------------------------

    @Test
    void aRefusalCarriesGooglesReason_andNoSecret() {
        google.expect(requestTo(TOKEN_URL)).andRespond(req -> withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid JWT Signature.\"}")
                .createResponse(capture(req)));

        assertThatThrownBy(() -> tokens.token("sa", KEYS::serviceAccount))
                .isInstanceOfSatisfying(GatewayException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("PROVIDER_ERROR");
                    assertThat(e.getMessage()).isEqualTo("Vertex AI: Google's token endpoint refused the access-token "
                            + "request with 400: invalid_grant: Invalid JWT Signature.");
                    assertThat(e.getMessage()).doesNotContain(forms.getFirst().get("assertion"));
                });
    }

    @Test
    void anAnswerWithNoTokenIsAnError() {
        google.expect(requestTo(TOKEN_URL)).andRespond(withSuccess("{\"expires_in\":3600}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> tokens.token("sa", KEYS::serviceAccount))
                .hasMessage("Vertex AI: Google's token endpoint answered with no access_token");
    }

    @Test
    void aTokenNeverPrintsItself() {
        GoogleAccessTokens.AccessToken token = new GoogleAccessTokens.AccessToken("ya29.secret", Instant.EPOCH);
        assertThat(token.toString()).doesNotContain("ya29.secret");
    }

    @Test
    void theDefaultTokenEndpointIsGooglesOwn() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("{\"access_token\":\"t\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        new GoogleAccessTokens(builder.build(), " ").token("sa", KEYS::serviceAccount);
        server.verify();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    private void expectToken(String value, int expiresIn) {
        google.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", org.hamcrest.Matchers.startsWith("application/x-www-form-urlencoded")))
                .andRespond(req -> withSuccess("{\"access_token\":\"" + value + "\",\"expires_in\":" + expiresIn
                        + ",\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON).createResponse(capture(req)));
    }

    private org.springframework.http.client.ClientHttpRequest capture(org.springframework.http.client.ClientHttpRequest req) {
        String body = ((MockClientHttpRequest) req).getBodyAsString();
        Map<String, String> form = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        forms.add(form);
        return req;
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
