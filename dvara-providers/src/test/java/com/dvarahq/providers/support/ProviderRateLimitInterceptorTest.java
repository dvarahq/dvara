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
import com.dvarahq.core.resilience.ProviderRateLimitTracker;
import com.dvarahq.core.resilience.ProviderRateLimitTracker.Decision;
import com.dvarahq.core.secret.SecretProvider;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProviderRateLimitInterceptorTest {

    private final SecretProvider secretProvider = mock(SecretProvider.class);
    private final ProviderRateLimitTracker tracker = mock(ProviderRateLimitTracker.class);
    private final AuditWriter auditWriter = mock(AuditWriter.class);
    private final HttpRequest request = mock(HttpRequest.class);
    private final ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);

    private ProviderRateLimitInterceptor interceptor() {
        return new ProviderRateLimitInterceptor(secretProvider, "provider.openai.api-key", "openai",
                tracker, auditWriter);
    }

    @Test
    void shed_throwsProviderRateLimited_beforeForwarding() throws IOException {
        when(secretProvider.getSecret(eq("provider.openai.api-key"), any()))
                .thenReturn(Optional.of("sk-secret"));
        when(tracker.check(eq("openai"), any(), any(), anyInt()))
                .thenReturn(Decision.shed("request-quota near exhaustion", Duration.ofSeconds(20)));

        assertThatThrownBy(() -> interceptor().intercept(request, new byte[0], execution))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> {
                    GatewayException ge = (GatewayException) e;
                    assertThat(ge.getCode()).isEqualTo("PROVIDER_RATE_LIMITED");
                    assertThat(ge.getRetryAfterSeconds()).isEqualTo(20L);
                });

        verify(execution, never()).execute(any(), any()); // shed BEFORE the upstream call
        verify(tracker, never()).observe(any(), any(), anyInt(), any());
        // a shed emits the UPSTREAM_RATE_LIMIT_SHED audit event
        verify(auditWriter).write(argThat(e ->
                "UPSTREAM_RATE_LIMIT_SHED".equals(((AuditEvent) e).eventType())
                        && "openai".equals(((AuditEvent) e).payload().get("provider"))));
    }

    @Test
    void allow_forwards_thenObservesResponseHeaders() throws IOException {
        when(secretProvider.getSecret(eq("provider.openai.api-key"), any()))
                .thenReturn(Optional.of("sk-secret"));
        when(tracker.check(eq("openai"), any(), any(), anyInt())).thenReturn(Decision.allowed());

        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-remaining-requests", "7");
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);
        when(response.getHeaders()).thenReturn(headers);
        when(execution.execute(request, new byte[0])).thenReturn(response);

        ClientHttpResponse result = interceptor().intercept(request, new byte[0], execution);

        assertThat(result).isSameAs(response);
        verify(execution).execute(request, new byte[0]);
        verify(tracker).observe(eq("openai"), any(), eq(200),
                argThat(m -> "7".equals(((Map<?, ?>) m).get("x-ratelimit-remaining-requests"))));
    }

    @Test
    void observesA429_forReactiveCooldown() throws IOException {
        when(secretProvider.getSecret(eq("provider.openai.api-key"), any()))
                .thenReturn(Optional.of("sk-secret"));
        when(tracker.check(any(), any(), any(), anyInt())).thenReturn(Decision.allowed());

        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set("retry-after", "30");
        when(response.getStatusCode()).thenReturn(HttpStatus.TOO_MANY_REQUESTS);
        when(response.getHeaders()).thenReturn(headers);
        when(execution.execute(any(), any())).thenReturn(response);

        interceptor().intercept(request, new byte[0], execution);
        verify(tracker).observe(eq("openai"), any(), eq(429), any());
    }

    @Test
    void noResolvableCredential_failsOpen_noCheckOrObserve() throws IOException {
        when(secretProvider.getSecret(eq("provider.openai.api-key"), any())).thenReturn(Optional.empty());
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(execution.execute(any(), any())).thenReturn(response);

        ClientHttpResponse result = interceptor().intercept(request, new byte[0], execution);

        assertThat(result).isSameAs(response);
        verify(execution).execute(request, new byte[0]);
        verifyNoInteractions(tracker); // no credential → skip rate limiting entirely
    }

    @Test
    void readsPriorityTierFromRequestAttribute() throws IOException {
        var attrs = mock(org.springframework.web.context.request.RequestAttributes.class);
        when(attrs.getAttribute("priorityTier",
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST)).thenReturn("premium");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attrs);
        try {
            when(secretProvider.getSecret(any(), any())).thenReturn(Optional.of("sk-secret"));
            when(tracker.check(any(), any(), any(), anyInt())).thenReturn(Decision.allowed());
            ClientHttpResponse response = mock(ClientHttpResponse.class);
            when(response.getStatusCode()).thenReturn(HttpStatus.OK);
            when(response.getHeaders()).thenReturn(new HttpHeaders());
            when(execution.execute(any(), any())).thenReturn(response);

            interceptor().intercept(request, new byte[0], execution);

            verify(tracker).check(eq("openai"), any(), eq("premium"), anyInt());
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void trackerCheckError_failsOpen() throws IOException {
        when(secretProvider.getSecret(eq("provider.openai.api-key"), any()))
                .thenReturn(Optional.of("sk-secret"));
        when(tracker.check(any(), any(), any(), anyInt())).thenThrow(new RuntimeException("tracker down"));
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(execution.execute(any(), any())).thenReturn(response);

        ClientHttpResponse result = interceptor().intercept(request, new byte[0], execution);
        assertThat(result).isSameAs(response); // check error must not break the call
    }
}