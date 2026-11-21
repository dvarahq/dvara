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
package com.dvarahq.providers.openai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.resilience.ProviderRateLimitTracker;
import com.dvarahq.core.resilience.ProviderRateLimitTracker.Decision;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.support.ProviderRateLimitInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Checks that {@link ProviderRateLimitInterceptor} works inside a real {@link OpenAiProvider}
 * RestClient call chain, which the unit tests cannot reach. The interceptor is attached to the
 * builder the way {@code ProviderAutoConfiguration} attaches it, then a real provider is driven
 * against a {@link MockRestServiceServer}. (In the {@code openai} package to reach the
 * package-private test constructor, like {@code OpenAiProviderTest}.)
 */
class ProviderRateLimitOpenAiIntegrationTest {

    private final SecretProvider secretProvider = mock(SecretProvider.class);
    private final ProviderRateLimitTracker tracker = mock(ProviderRateLimitTracker.class);

    private OpenAiProvider providerWith(MockRestServiceServer[] serverOut) {
        when(secretProvider.getSecret(eq("provider.openai.api-key"), any())).thenReturn(Optional.of("sk-test"));
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .requestInterceptor(new ProviderRateLimitInterceptor(
                        secretProvider, "provider.openai.api-key", "openai", tracker, null));
        serverOut[0] = MockRestServiceServer.bindTo(builder).build();
        return new OpenAiProvider(builder.build());
    }

    private static ChatRequest chat() {
        return ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("hi"))).build();
    }

    @Test
    void allow_forwardsThroughRealProvider_andObservesResponse() {
        when(tracker.check(eq("openai"), any(), any(), anyInt())).thenReturn(Decision.allowed());
        MockRestServiceServer[] s = new MockRestServiceServer[1];
        OpenAiProvider provider = providerWith(s);
        s[0].expect(requestTo(containsString("/chat/completions")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("""
                        {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-4o",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """, MediaType.APPLICATION_JSON)
                        .header("x-ratelimit-remaining-requests", "42"));

        ChatResponse response = provider.chat(chat());

        assertThat(response.getId()).isEqualTo("chatcmpl-1");
        s[0].verify(); // the upstream call actually happened
        verify(tracker).observe(eq("openai"), any(), eq(200),
                argThat(m -> "42".equals(m.get("x-ratelimit-remaining-requests"))));
    }

    @Test
    void shed_abortsBeforeTheUpstreamCall_withProviderRateLimited() {
        when(tracker.check(eq("openai"), any(), any(), anyInt()))
                .thenReturn(Decision.shed("request-quota near exhaustion", Duration.ofSeconds(12)));
        MockRestServiceServer[] s = new MockRestServiceServer[1];
        OpenAiProvider provider = providerWith(s);
        // No server.expect(...) — the request must never reach the upstream.

        assertThatThrownBy(() -> provider.chat(chat()))
                .satisfies(t -> assertThat(rateLimitCode(t)).isEqualTo("PROVIDER_RATE_LIMITED"));

        verify(tracker, never()).observe(any(), any(), anyInt(), any());
        s[0].verify(); // no expectations set → verifies zero upstream requests were made
    }

    private static String rateLimitCode(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof GatewayException ge) return ge.getCode();
        }
        return null;
    }
}