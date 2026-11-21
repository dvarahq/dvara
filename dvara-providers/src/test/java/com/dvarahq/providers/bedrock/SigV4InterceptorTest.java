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
package com.dvarahq.providers.bedrock;

import com.dvarahq.core.secret.SecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The AWS SigV4 request signer. A signing bug surfaces as an opaque upstream 403 that looks like
 * bad credentials, so the parts AWS matches literally are pinned here.
 *
 * <p>The signature is over {@code Instant.now()} and the clock is not injectable, so the published
 * AWS test vectors, which fix the timestamp, cannot be reproduced. Instead these tests pin the
 * credential scope and signed-header list, the payload hash against a known SHA-256, and that the
 * signature varies with the body, the secret and the region, which shows those inputs reach the
 * HMAC.</p>
 */
class SigV4InterceptorTest {

    private SecretProvider secrets;
    private ClientHttpRequestExecution execution;
    private BedrockProvider.SigV4Interceptor interceptor;

    /** SHA-256 of the empty string, the AWS "no payload" hash. */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @BeforeEach
    void setUp() throws IOException {
        // requireSecret is a default method on the interface, and a Mockito mock returns null for
        // it unless stubbed directly; stub the method the interceptor calls.
        secrets = mock(SecretProvider.class);
        when(secrets.requireSecret(eq("provider.bedrock.access-key"), any())).thenReturn("AKIDEXAMPLE");
        when(secrets.requireSecret(eq("provider.bedrock.secret-key"), any()))
                .thenReturn("wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
        execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(new MockClientHttpResponse(new byte[0], 200));
        interceptor = new BedrockProvider.SigV4Interceptor(secrets, "us-east-1");
    }

    private MockClientHttpRequest request() {
        MockClientHttpRequest r = new MockClientHttpRequest(HttpMethod.POST,
                URI.create("https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude/invoke"));
        r.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return r;
    }

    private HttpHeaders sign(MockClientHttpRequest request, byte[] body) throws IOException {
        interceptor.intercept(request, body, execution);
        return request.getHeaders();
    }

    @Test
    void signsWithTheScopeAndSignedHeaderListAwsMatchesLiterally() throws IOException {
        HttpHeaders headers = sign(request(), new byte[0]);

        String auth = headers.getFirst("Authorization");
        assertThat(auth).isNotNull().startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/");
        // scope = <yyyyMMdd>/<region>/<service>/aws4_request; the date is today's
        assertThat(auth).containsPattern("Credential=AKIDEXAMPLE/\\d{8}/us-east-1/bedrock/aws4_request");
        // The signed-header list must match the canonical-headers block exactly, in this order.
        assertThat(auth).contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date");
        assertThat(auth).containsPattern("Signature=[0-9a-f]{64}$");
    }

    @Test
    void setsTheAmzDateAndPayloadHashHeaders() throws IOException {
        HttpHeaders headers = sign(request(), new byte[0]);

        assertThat(headers.getFirst("x-amz-date")).matches("\\d{8}T\\d{6}Z");
        assertThat(headers.getFirst("x-amz-content-sha256")).isEqualTo(EMPTY_SHA256);
    }

    @Test
    void payloadHashIsOverTheActualBody() throws IOException {
        HttpHeaders headers = sign(request(), "{\"prompt\":\"hi\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(headers.getFirst("x-amz-content-sha256"))
                .isNotEqualTo(EMPTY_SHA256)
                .matches("[0-9a-f]{64}");
    }

    /** Bedrock signs rather than sending a header, so it records the fingerprint itself: of the access key id. */
    @Test
    void recordsTheFingerprintOfTheAccessKeyItSignedWith() throws IOException {
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
        try {
            sign(request(), new byte[0]);

            assertThat(com.dvarahq.providers.support.CredentialInterceptor.resolveFingerprint())
                    .isNotNull()
                    .isEqualTo(com.dvarahq.core.credential.CredentialFingerprint.of("AKIDEXAMPLE"));
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    /** Host is derived from the URI when absent; it is one of the four signed headers. */
    @Test
    void setsHostFromTheUriWhenAbsent() throws IOException {
        HttpHeaders headers = sign(request(), new byte[0]);

        assertThat(headers.getFirst("Host")).isEqualTo("bedrock-runtime.us-east-1.amazonaws.com");
    }

    /** A caller-set Host wins; overwriting it would sign a value the transport does not send. */
    @Test
    void doesNotOverwriteACallerSuppliedHost() throws IOException {
        MockClientHttpRequest r = request();
        r.getHeaders().set("Host", "proxy.internal");

        assertThat(sign(r, new byte[0]).getFirst("Host")).isEqualTo("proxy.internal");
    }

    /**
     * A different body gives a different signature, which shows the payload hash is folded into
     * the string-to-sign rather than only set as a header.
     */
    @Test
    void signatureVariesWithTheBody() throws IOException {
        String a = signatureOf(sign(request(), "one".getBytes(StandardCharsets.UTF_8)));
        String b = signatureOf(sign(request(), "two".getBytes(StandardCharsets.UTF_8)));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void signatureVariesWithTheSecretKey() throws IOException {
        String a = signatureOf(sign(request(), new byte[0]));

        when(secrets.requireSecret(eq("provider.bedrock.secret-key"), any()))
                .thenReturn("a-completely-different-secret");
        String b = signatureOf(sign(request(), new byte[0]));

        assertThat(a).isNotEqualTo(b);
    }

    /** The region is in both the credential scope and the derived signing key. */
    @Test
    void signatureVariesWithTheRegion() throws IOException {
        String usEast = signatureOf(sign(request(), new byte[0]));

        MockClientHttpRequest euRequest = request();
        new BedrockProvider.SigV4Interceptor(secrets, "eu-west-1")
                .intercept(euRequest, new byte[0], execution);

        assertThat(signatureOf(euRequest.getHeaders())).isNotEqualTo(usEast);
        assertThat(euRequest.getHeaders().getFirst("Authorization"))
                .contains("/eu-west-1/bedrock/aws4_request");
    }

    /**
     * A missing credential is an {@code IOException}, not an NPE or a silently unsigned request.
     * An unsigned request to Bedrock is a 403 that looks exactly like a bad key.
     */
    @Test
    void missingCredentials_failLoudlyBeforeTheCallIsMade() throws IOException {
        // What the real default does when the credential is absent.
        when(secrets.requireSecret(eq("provider.bedrock.access-key"), any()))
                .thenThrow(new com.dvarahq.core.exception.GatewayException(
                        "CREDENTIAL_NOT_FOUND", "Required credential not found"));
        MockClientHttpRequest r = request();

        assertThatThrownBy(() -> interceptor.intercept(r, new byte[0], execution))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("SigV4 signing failed");

        verify(execution, org.mockito.Mockito.never()).execute(any(), any());
    }

    @Test
    void passesTheRequestOnToTheExecutionChain() throws IOException {
        MockClientHttpRequest r = request();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        ClientHttpResponse response = interceptor.intercept(r, body, execution);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(execution).execute(r, body);
    }

    private static String signatureOf(HttpHeaders headers) {
        String auth = headers.getFirst("Authorization");
        return auth.substring(auth.indexOf("Signature=") + "Signature=".length());
    }
}