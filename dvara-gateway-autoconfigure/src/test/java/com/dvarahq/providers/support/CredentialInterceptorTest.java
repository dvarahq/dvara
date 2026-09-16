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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.secret.SecretProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CredentialInterceptorTest {

    @Test
    void intercept_setsHeaderWithFormattedCredential() throws Exception {
        SecretProvider secrets = key -> Optional.of("sk-test-key");

        CredentialInterceptor interceptor = new CredentialInterceptor(
                secrets, "provider.openai.api-key",
                "Authorization", k -> "Bearer " + k);

        HttpHeaders headers = new HttpHeaders();
        HttpRequest request = stubRequest(headers);
        ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
        ClientHttpRequestExecution execution = (req, body) -> mockResponse;

        interceptor.intercept(request, new byte[0], execution);

        assertThat(headers.getFirst("Authorization")).isEqualTo("Bearer sk-test-key");
    }

    @Test
    void intercept_identityFormatter_setsRawValue() throws Exception {
        SecretProvider secrets = key -> Optional.of("sk-ant-secret");

        CredentialInterceptor interceptor = new CredentialInterceptor(
                secrets, "provider.anthropic.api-key",
                "x-api-key", Function.identity());

        HttpHeaders headers = new HttpHeaders();
        HttpRequest request = stubRequest(headers);
        ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
        ClientHttpRequestExecution execution = (req, body) -> mockResponse;

        interceptor.intercept(request, new byte[0], execution);

        assertThat(headers.getFirst("x-api-key")).isEqualTo("sk-ant-secret");
    }

    @Test
    void intercept_credentialRotation_resolvesFreshValueEachCall() throws Exception {
        AtomicReference<String> currentKey = new AtomicReference<>("key-v1");
        SecretProvider secrets = key -> Optional.of(currentKey.get());

        CredentialInterceptor interceptor = new CredentialInterceptor(
                secrets, "provider.openai.api-key",
                "Authorization", k -> "Bearer " + k);

        ClientHttpResponse mockResponse = mock(ClientHttpResponse.class);
        ClientHttpRequestExecution execution = (req, body) -> mockResponse;

        // First call: key-v1
        HttpHeaders headers1 = new HttpHeaders();
        interceptor.intercept(stubRequest(headers1), new byte[0], execution);
        assertThat(headers1.getFirst("Authorization")).isEqualTo("Bearer key-v1");

        // Rotate the credential
        currentKey.set("key-v2");

        // Second call: key-v2
        HttpHeaders headers2 = new HttpHeaders();
        interceptor.intercept(stubRequest(headers2), new byte[0], execution);
        assertThat(headers2.getFirst("Authorization")).isEqualTo("Bearer key-v2");
    }

    @Test
    void intercept_missingCredential_throws() {
        SecretProvider secrets = key -> Optional.empty();

        CredentialInterceptor interceptor = new CredentialInterceptor(
                secrets, "provider.openai.api-key",
                "Authorization", k -> "Bearer " + k);

        HttpHeaders headers = new HttpHeaders();
        HttpRequest request = stubRequest(headers);
        ClientHttpRequestExecution execution = (req, body) -> mock(ClientHttpResponse.class);

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], execution))
                .isInstanceOf(GatewayException.class)
                .satisfies(ex -> {
                    GatewayException ge = (GatewayException) ex;
                    assertThat(ge.getCode()).isEqualTo("CREDENTIAL_NOT_FOUND");
                });
    }

    private static HttpRequest stubRequest(HttpHeaders headers) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getHeaders()).thenReturn(headers);
        when(request.getURI()).thenReturn(URI.create("https://api.example.com/v1/chat"));
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        return request;
    }
}