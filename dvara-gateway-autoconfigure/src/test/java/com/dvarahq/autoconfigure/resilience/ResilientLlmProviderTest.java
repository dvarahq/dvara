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
package com.dvarahq.autoconfigure.resilience;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.web.context.request.AbstractRequestAttributes;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResilientLlmProviderTest {

    private LlmProvider delegate;
    private CircuitBreaker circuitBreaker;
    private Retry retry;
    private TimeLimiter chatTimeLimiter;
    private TimeLimiter streamingTimeLimiter;

    @BeforeEach
    void setUp() {
        delegate = mock(LlmProvider.class);
        when(delegate.name()).thenReturn("test-provider");
        when(delegate.supports(any())).thenReturn(true);
        when(delegate.supportsEmbedding(any())).thenReturn(true);
        when(delegate.capabilities()).thenReturn(new ProviderCapabilities(true, false, false, false, false, 128000));

        circuitBreaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());

        retry = Retry.of("test", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryOnException(e -> e instanceof GatewayException ge && "PROVIDER_ERROR".equals(ge.getCode()))
                .build());

        chatTimeLimiter = TimeLimiter.of("test-chat", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .build());

        streamingTimeLimiter = TimeLimiter.of("test-stream", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(30))
                .build());
    }

    // -------------------------------------------------------------------------
    // Delegation tests
    // -------------------------------------------------------------------------

    @Test
    void name_delegatesToWrappedProvider() {
        ResilientLlmProvider resilient = createProvider();
        assertThat(resilient.name()).isEqualTo("test-provider");
    }

    @Test
    void supports_delegatesToWrappedProvider() {
        ResilientLlmProvider resilient = createProvider();
        assertThat(resilient.supports(chatRequest())).isTrue();
        verify(delegate).supports(any());
    }

    @Test
    void supportsEmbedding_delegatesToWrappedProvider() {
        when(delegate.supportsEmbedding("text-embedding-ada-002")).thenReturn(true);
        when(delegate.supportsEmbedding("unknown-model")).thenReturn(false);

        ResilientLlmProvider resilient = createProvider();

        assertThat(resilient.supportsEmbedding("text-embedding-ada-002")).isTrue();
        assertThat(resilient.supportsEmbedding("unknown-model")).isFalse();
        verify(delegate).supportsEmbedding("text-embedding-ada-002");
        verify(delegate).supportsEmbedding("unknown-model");
    }

    @Test
    void capabilities_delegatesToWrappedProvider() {
        ResilientLlmProvider resilient = createProvider();
        ProviderCapabilities caps = resilient.capabilities();

        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.maxContextTokens()).isEqualTo(128000);
        verify(delegate).capabilities();
    }

    @Test
    void getDelegate_returnsWrappedProvider() {
        ResilientLlmProvider resilient = createProvider();
        assertThat(resilient.getDelegate()).isSameAs(delegate);
    }

    // -------------------------------------------------------------------------
    // chat() — success and retry
    // -------------------------------------------------------------------------

    @Test
    void chat_delegatesSuccessfully() {
        ChatResponse expected = chatResponse("id-1");
        when(delegate.chat(any())).thenReturn(expected);

        ResilientLlmProvider resilient = createProvider();
        ChatResponse result = resilient.chat(chatRequest());

        assertThat(result.getId()).isEqualTo("id-1");
        verify(delegate).chat(any());
    }

    @Test
    void chat_retriesOnProviderError() {
        AtomicInteger callCount = new AtomicInteger();
        when(delegate.chat(any())).thenAnswer(inv -> {
            if (callCount.incrementAndGet() < 3) {
                throw new GatewayException("PROVIDER_ERROR", "upstream error");
            }
            return chatResponse("recovered");
        });

        ResilientLlmProvider resilient = createProvider();
        ChatResponse result = resilient.chat(chatRequest());

        assertThat(result.getId()).isEqualTo("recovered");
        verify(delegate, times(3)).chat(any());
    }

    @Test
    void chat_doesNotRetryOnNoProvider() {
        when(delegate.chat(any())).thenThrow(new GatewayException("NO_PROVIDER", "no provider"));

        ResilientLlmProvider resilient = createProvider();

        assertThatThrownBy(() -> resilient.chat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("NO_PROVIDER"));
        verify(delegate, times(1)).chat(any());
    }

    @Test
    void chat_exhaustsRetriesAndThrows() {
        when(delegate.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "always fails"));

        ResilientLlmProvider resilient = createProvider();

        assertThatThrownBy(() -> resilient.chat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_ERROR"));
        // maxAttempts=3, so 3 calls total
        verify(delegate, times(3)).chat(any());
    }

    // -------------------------------------------------------------------------
    // chat() — circuit breaker
    // -------------------------------------------------------------------------

    @Test
    void chat_circuitOpensAfterThreshold() {
        when(delegate.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "fail"));

        Retry noRetry = Retry.of("no-retry", RetryConfig.custom()
                .maxAttempts(1)
                .build());

        ResilientLlmProvider resilient = new ResilientLlmProvider(
                delegate, circuitBreaker, noRetry, chatTimeLimiter, streamingTimeLimiter);

        // Four failures meet the breaker's minimum call count and exceed its failure-rate threshold.
        for (int i = 0; i < 4; i++) {
            try {
                resilient.chat(chatRequest());
            } catch (GatewayException ignored) {
            }
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> resilient.chat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_CIRCUIT_OPEN"))
                .hasMessageContaining("too many recent requests failed");
    }

    @Test
    void chat_circuitOpenExceptionIncludesProviderName() {
        when(delegate.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "fail"));

        Retry noRetry = Retry.of("no-retry", RetryConfig.custom().maxAttempts(1).build());
        ResilientLlmProvider resilient = new ResilientLlmProvider(
                delegate, circuitBreaker, noRetry, chatTimeLimiter, streamingTimeLimiter);

        for (int i = 0; i < 4; i++) {
            try { resilient.chat(chatRequest()); } catch (GatewayException ignored) { }
        }

        assertThatThrownBy(() -> resilient.chat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("test-provider")
                .hasMessageContaining("check your provider API key");
    }

    // -------------------------------------------------------------------------
    // embed() — full resilience
    // -------------------------------------------------------------------------

    @Test
    void embed_delegatesSuccessfully() {
        EmbeddingResponse expected = EmbeddingResponse.builder()
                .model("text-embedding-ada-002")
                .data(List.of())
                .build();
        when(delegate.embed(any())).thenReturn(expected);

        ResilientLlmProvider resilient = createProvider();
        EmbeddingRequest request = embeddingRequest();
        EmbeddingResponse result = resilient.embed(request);

        assertThat(result.getModel()).isEqualTo("text-embedding-ada-002");
        verify(delegate).embed(any());
    }

    @Test
    void embed_retriesOnProviderError() {
        AtomicInteger callCount = new AtomicInteger();
        when(delegate.embed(any())).thenAnswer(inv -> {
            if (callCount.incrementAndGet() < 2) {
                throw new GatewayException("PROVIDER_ERROR", "embed error");
            }
            return EmbeddingResponse.builder().model("text-embedding-ada-002").data(List.of()).build();
        });

        ResilientLlmProvider resilient = createProvider();
        EmbeddingResponse result = resilient.embed(embeddingRequest());

        assertThat(result.getModel()).isEqualTo("text-embedding-ada-002");
        verify(delegate, times(2)).embed(any());
    }

    @Test
    void embed_circuitBreakerProtectsEmbeddings() {
        when(delegate.embed(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "fail"));

        Retry noRetry = Retry.of("no-retry", RetryConfig.custom().maxAttempts(1).build());
        ResilientLlmProvider resilient = new ResilientLlmProvider(
                delegate, circuitBreaker, noRetry, chatTimeLimiter, streamingTimeLimiter);

        for (int i = 0; i < 4; i++) {
            try { resilient.embed(embeddingRequest()); } catch (GatewayException ignored) { }
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> resilient.embed(embeddingRequest()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_CIRCUIT_OPEN"));
    }

    // -------------------------------------------------------------------------
    // streamChat() — connection retry
    // -------------------------------------------------------------------------

    @Test
    void streamChat_delegatesSuccessfully() {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("s1").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("s1").model("gpt-4o").delta(null).finishReason("stop").done(true).build()
        );
        when(delegate.streamChat(any())).thenReturn(chunks.iterator());

        ResilientLlmProvider resilient = createProvider();
        Iterator<SseChunk> result = resilient.streamChat(chatRequest());

        assertThat(result.hasNext()).isTrue();
        assertThat(result.next().getDelta()).isEqualTo("Hi");
        assertThat(result.hasNext()).isTrue();
        assertThat(result.next().isDone()).isTrue();
        verify(delegate).streamChat(any());
    }

    @Test
    void streamChat_retriesConnectionOnly() {
        List<SseChunk> chunks = List.of(
                SseChunk.builder().id("s1").model("gpt-4o").delta("Hi").done(false).build(),
                SseChunk.builder().id("s1").model("gpt-4o").delta(null).finishReason("stop").done(true).build()
        );

        AtomicInteger callCount = new AtomicInteger();
        when(delegate.streamChat(any())).thenAnswer(inv -> {
            if (callCount.incrementAndGet() < 2) {
                throw new GatewayException("PROVIDER_ERROR", "connection error");
            }
            return chunks.iterator();
        });

        ResilientLlmProvider resilient = createProvider();
        Iterator<SseChunk> result = resilient.streamChat(chatRequest());

        assertThat(result.hasNext()).isTrue();
        assertThat(result.next().getDelta()).isEqualTo("Hi");
        verify(delegate, times(2)).streamChat(any());
    }

    @Test
    void streamChat_circuitBreakerProtectsStreaming() {
        when(delegate.streamChat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "stream fail"));

        Retry noRetry = Retry.of("no-retry", RetryConfig.custom().maxAttempts(1).build());
        ResilientLlmProvider resilient = new ResilientLlmProvider(
                delegate, circuitBreaker, noRetry, chatTimeLimiter, streamingTimeLimiter);

        for (int i = 0; i < 4; i++) {
            try { resilient.streamChat(chatRequest()); } catch (GatewayException ignored) { }
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> resilient.streamChat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_CIRCUIT_OPEN"));
    }

    // -------------------------------------------------------------------------
    // Timeout
    // -------------------------------------------------------------------------

    @Test
    void chat_timesOutOnSlowProvider() {
        TimeLimiter shortTimeout = TimeLimiter.of("short", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(50))
                .cancelRunningFuture(true)
                .build());

        when(delegate.chat(any())).thenAnswer(inv -> {
            Thread.sleep(500);
            return chatResponse("slow");
        });

        // No retry, so only the timeout is in play.
        Retry noRetry = Retry.of("no-retry", RetryConfig.custom().maxAttempts(1).build());
        ResilientLlmProvider resilient = new ResilientLlmProvider(
                delegate, circuitBreaker, noRetry, shortTimeout, streamingTimeLimiter);

        assertThatThrownBy(() -> resilient.chat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_ERROR"))
                .hasMessageContaining("timed out");
    }

    // -------------------------------------------------------------------------
    // Error propagation
    // -------------------------------------------------------------------------

    @Test
    void chat_propagatesGatewayExceptionFromDelegate() {
        GatewayException original = new GatewayException("PROVIDER_ERROR", "specific error message");
        when(delegate.chat(any())).thenThrow(original);

        ResilientLlmProvider resilient = createProvider();

        assertThatThrownBy(() -> resilient.chat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .hasMessage("specific error message")
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_ERROR"));
    }

    @Test
    void chat_wrapsGenericExceptionAsProviderError() {
        // The retry here only retries PROVIDER_ERROR GatewayExceptions, so a plain RuntimeException
        // fails on the first attempt and is wrapped as PROVIDER_ERROR.
        when(delegate.chat(any())).thenThrow(new RuntimeException("network error"));

        ResilientLlmProvider resilient = createProvider();

        assertThatThrownBy(() -> resilient.chat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_ERROR"))
                .hasMessageContaining("network error");
    }

    @Test
    void chat_circuitBreakerSharedAcrossOperations() {
        // chat() and embed() share one circuit breaker.
        when(delegate.chat(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "fail"));
        when(delegate.embed(any())).thenThrow(new GatewayException("PROVIDER_ERROR", "fail"));

        Retry noRetry = Retry.of("no-retry", RetryConfig.custom().maxAttempts(1).build());
        ResilientLlmProvider resilient = new ResilientLlmProvider(
                delegate, circuitBreaker, noRetry, chatTimeLimiter, streamingTimeLimiter);

        // Two chat failures and two embed failures make the four the breaker needs.
        for (int i = 0; i < 2; i++) {
            try { resilient.chat(chatRequest()); } catch (GatewayException ignored) { }
        }
        for (int i = 0; i < 2; i++) {
            try { resilient.embed(embeddingRequest()); } catch (GatewayException ignored) { }
        }

        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Both chat and embed now see PROVIDER_CIRCUIT_OPEN.
        assertThatThrownBy(() -> resilient.chat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_CIRCUIT_OPEN"));

        assertThatThrownBy(() -> resilient.embed(embeddingRequest()))
                .isInstanceOf(GatewayException.class)
                .satisfies(e -> assertThat(((GatewayException) e).getCode()).isEqualTo("PROVIDER_CIRCUIT_OPEN"));
    }

    // -------------------------------------------------------------------------
    // request-attribute propagation across the resilience boundary
    // -------------------------------------------------------------------------

    /**
     * The upstream call runs on a pool thread, not the request thread, and
     * CredentialInterceptor reads the workspaceId from the thread-local
     * {@link RequestContextHolder}. The request attributes must therefore be
     * copied across, or a workspace-aware secret provider falls through to the
     * platform-default credential.
     */
    @Test
    void chat_propagatesRequestAttributesToAsyncProviderThread() {
        AtomicReference<String> seenWorkspaceId = new AtomicReference<>();
        AtomicReference<Thread> requestThread = new AtomicReference<>();
        AtomicReference<Thread> providerThread = new AtomicReference<>();

        when(delegate.chat(any())).thenAnswer(inv -> {
            providerThread.set(Thread.currentThread());
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            seenWorkspaceId.set(attrs != null
                    ? (String) attrs.getAttribute("workspaceId", RequestAttributes.SCOPE_REQUEST)
                    : null);
            return chatResponse("ok");
        });

        var attrs = new MapBackedRequestAttributes(Map.of("workspaceId", "acme-corp"));
        RequestContextHolder.setRequestAttributes(attrs);
        try {
            requestThread.set(Thread.currentThread());
            createProvider().chat(chatRequest());

            assertThat(seenWorkspaceId.get())
                    .as("workspaceId must propagate from the request thread to the resilience pool thread")
                    .isEqualTo("acme-corp");
            assertThat(providerThread.get())
                    .as("the upstream call must run on a different thread from the request; "
                            + "otherwise there is no resilience boundary to test")
                    .isNotEqualTo(requestThread.get());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void chat_doesNotLeakRequestAttributesIntoPoolThreadAfterCall() {
        // After chat() completes, the pool thread must not keep the request attributes;
        // the next task on that thread would otherwise inherit a stale workspace and
        // CredentialInterceptor would resolve credentials for the wrong workspace.
        AtomicReference<Thread> providerThread = new AtomicReference<>();
        when(delegate.chat(any())).thenAnswer(inv -> {
            providerThread.set(Thread.currentThread());
            return chatResponse("ok");
        });

        RequestContextHolder.setRequestAttributes(
                new MapBackedRequestAttributes(Map.of("workspaceId", "workspace-1")));
        try {
            createProvider().chat(chatRequest());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        // Probe the same pool. It may pick a different worker, but no worker may
        // carry a stale request scope.
        AtomicReference<RequestAttributes> probeAttrs = new AtomicReference<>();
        try {
            java.util.concurrent.CompletableFuture
                    .runAsync(() -> probeAttrs.set(RequestContextHolder.getRequestAttributes()))
                    .get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("probe failed", e);
        }
        assertThat(probeAttrs.get())
                .as("a fresh task on the resilience pool must see no request attributes")
                .isNull();
    }

    @Test
    void chat_propagatesWorkspaceCredentialRequiredFromInterceptor() {
        // CredentialInterceptor throws this when it resolves the credential on the pool
        // thread. The code must survive as it is: re-wrapped as PROVIDER_ERROR it would
        // map to a 502 and the PROVIDER_CREDENTIAL_MISSING audit event would not fire.
        when(delegate.chat(any())).thenThrow(new GatewayException(
                "WORKSPACE_CREDENTIAL_REQUIRED",
                "Workspace 'acme' has no active provider credential for 'provider.openai.api-key'"));

        RequestContextHolder.setRequestAttributes(
                new MapBackedRequestAttributes(Map.of("workspaceId", "acme")));
        try {
            assertThatThrownBy(() -> createProvider().chat(chatRequest()))
                    .isInstanceOf(GatewayException.class)
                    .satisfies(e -> assertThat(((GatewayException) e).getCode())
                            .as("the credential code must survive the resilience boundary, "
                                    + "not be re-wrapped as PROVIDER_ERROR")
                            .isEqualTo("WORKSPACE_CREDENTIAL_REQUIRED"));
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        // Not retried: only PROVIDER_ERROR is retryable.
        verify(delegate, times(1)).chat(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private ResilientLlmProvider createProvider() {
        return new ResilientLlmProvider(delegate, circuitBreaker, retry, chatTimeLimiter, streamingTimeLimiter);
    }

    private static ChatRequest chatRequest() {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("hello")))
                .build();
    }

    private static ChatResponse chatResponse(String id) {
        return ChatResponse.builder()
                .id(id)
                .model("gpt-4o")
                .choices(List.of())
                .build();
    }

    private static EmbeddingRequest embeddingRequest() {
        return EmbeddingRequest.builder()
                .model("text-embedding-ada-002")
                .input("hello world")
                .build();
    }

    @Test
    void listModels_delegatesToWrappedProvider() {
        var models = List.of(
                new com.dvarahq.core.provider.ModelInfo("gpt-4o", "openai", 1700000000L));
        when(delegate.listModels()).thenReturn(models);

        ResilientLlmProvider resilient = createProvider();
        assertThat(resilient.listModels()).isEqualTo(models);
    }

    /**
     * Minimal {@link RequestAttributes} backed by a {@link Map}, so the propagation tests need no
     * servlet API on the classpath. Only {@code SCOPE_REQUEST} is implemented.
     */
    private static final class MapBackedRequestAttributes extends AbstractRequestAttributes {
        private final Map<String, Object> attrs;

        MapBackedRequestAttributes(Map<String, Object> initial) {
            this.attrs = new HashMap<>(initial);
        }

        @Override
        public Object getAttribute(String name, int scope) {
            return scope == SCOPE_REQUEST ? attrs.get(name) : null;
        }

        @Override
        public void setAttribute(String name, Object value, int scope) {
            if (scope == SCOPE_REQUEST) attrs.put(name, value);
        }

        @Override
        public void removeAttribute(String name, int scope) {
            if (scope == SCOPE_REQUEST) attrs.remove(name);
        }

        @Override
        public String[] getAttributeNames(int scope) {
            return scope == SCOPE_REQUEST ? attrs.keySet().toArray(new String[0]) : new String[0];
        }

        @Override
        public void registerDestructionCallback(String name, Runnable callback, int scope) { }

        @Override
        public Object resolveReference(String key) { return null; }

        @Override
        public String getSessionId() { return "test-session"; }

        @Override
        public Object getSessionMutex() { return this; }

        @Override
        protected void updateAccessedSessionAttributes() { }
    }
}