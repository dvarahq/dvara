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

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.providers.mock.MockProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResilienceAutoConfigurationTest {

    private final ResilienceAutoConfiguration autoConfig = new ResilienceAutoConfiguration();

    // -------------------------------------------------------------------------
    // providerHealthRegistry()
    // -------------------------------------------------------------------------

    @Test
    void providerHealthRegistry_createsResilience4jRegistry() {
        Resilience4jProviderHealthRegistry registry = autoConfig.providerHealthRegistry();

        assertThat(registry).isNotNull();
        assertThat(registry).isInstanceOf(ProviderHealthRegistry.class);
        assertThat(registry.getHealth("any")).isEqualTo(ProviderHealthStatus.HEALTHY);
    }

    // -------------------------------------------------------------------------
    // fallbackResolver()
    // -------------------------------------------------------------------------

    @Test
    void fallbackResolver_whenFallbackEnabled_returnsConfigurableResolver() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().getFallback().setEnabled(true);

        FallbackResolver resolver = autoConfig.fallbackResolver(properties);

        assertThat(resolver).isInstanceOf(ConfigurableFallbackResolver.class);
    }

    @Test
    void fallbackResolver_whenFallbackDisabled_returnsEmptyResolver() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().getFallback().setEnabled(false);

        FallbackResolver resolver = autoConfig.fallbackResolver(properties);

        LlmProvider failedProvider = mockProvider("openai");
        LlmProvider otherProvider = mockProvider("anthropic");
        List<LlmProvider> fallbacks = resolver.resolve(
                chatRequest(), failedProvider, List.of(failedProvider, otherProvider));

        assertThat(fallbacks).isEmpty();
    }

    // -------------------------------------------------------------------------
    // BeanPostProcessor wrapping
    // -------------------------------------------------------------------------

    @Test
    void beanPostProcessor_wrapsLlmProviderWithResilientProvider() {
        BeanPostProcessor processor = createProcessor(new GatewayProperties());

        LlmProvider rawProvider = mockProvider("openai");
        Object result = processor.postProcessAfterInitialization(rawProvider, "openAiProvider");

        assertThat(result).isInstanceOf(ResilientLlmProvider.class);
        ResilientLlmProvider resilient = (ResilientLlmProvider) result;
        assertThat(resilient.name()).isEqualTo("openai");
        assertThat(resilient.getDelegate()).isSameAs(rawProvider);
    }

    @Test
    void beanPostProcessor_doesNotDoubleWrapResilientProvider() {
        BeanPostProcessor processor = createProcessor(new GatewayProperties());

        LlmProvider rawProvider = mockProvider("openai");
        Object wrapped = processor.postProcessAfterInitialization(rawProvider, "openAiProvider");
        assertThat(wrapped).isInstanceOf(ResilientLlmProvider.class);

        // A second pass over an already wrapped bean returns it unchanged.
        Object doubleWrapped = processor.postProcessAfterInitialization(wrapped, "openAiProvider");
        assertThat(doubleWrapped).isSameAs(wrapped);
    }

    @Test
    void beanPostProcessor_ignoresNonProviderBeans() {
        BeanPostProcessor processor = createProcessor(new GatewayProperties());

        String nonProvider = "not a provider";
        Object result = processor.postProcessAfterInitialization(nonProvider, "someBean");

        assertThat(result).isSameAs(nonProvider);
    }

    @Test
    void beanPostProcessor_registersCircuitBreakerInHealthRegistry() {
        Resilience4jProviderHealthRegistry healthRegistry = new Resilience4jProviderHealthRegistry();
        BeanPostProcessor processor = createProcessor(new GatewayProperties(), healthRegistry);

        LlmProvider rawProvider = mockProvider("openai");
        processor.postProcessAfterInitialization(rawProvider, "openAiProvider");

        // Wrapping registers the provider's circuit breaker with the health registry.
        assertThat(healthRegistry.getHealth("openai")).isEqualTo(ProviderHealthStatus.HEALTHY);
    }

    @Test
    void beanPostProcessor_wrapsMultipleProviders() {
        Resilience4jProviderHealthRegistry healthRegistry = new Resilience4jProviderHealthRegistry();
        BeanPostProcessor processor = createProcessor(new GatewayProperties(), healthRegistry);

        LlmProvider openai = mockProvider("openai");
        LlmProvider anthropic = mockProvider("anthropic");

        Object wrapped1 = processor.postProcessAfterInitialization(openai, "openAiProvider");
        Object wrapped2 = processor.postProcessAfterInitialization(anthropic, "anthropicProvider");

        assertThat(wrapped1).isInstanceOf(ResilientLlmProvider.class);
        assertThat(wrapped2).isInstanceOf(ResilientLlmProvider.class);
        assertThat(((ResilientLlmProvider) wrapped1).name()).isEqualTo("openai");
        assertThat(((ResilientLlmProvider) wrapped2).name()).isEqualTo("anthropic");

        // Both providers are registered with the health registry.
        assertThat(healthRegistry.getHealth("openai")).isEqualTo(ProviderHealthStatus.HEALTHY);
        assertThat(healthRegistry.getHealth("anthropic")).isEqualTo(ProviderHealthStatus.HEALTHY);
    }

    // -------------------------------------------------------------------------
    // Per-provider config overrides
    // -------------------------------------------------------------------------

    @Test
    void beanPostProcessor_appliesPerProviderOverrides() {
        GatewayProperties properties = new GatewayProperties();

        GatewayProperties.ProviderResilienceOverride override = new GatewayProperties.ProviderResilienceOverride();
        GatewayProperties.TimeoutOverride timeoutOverride = new GatewayProperties.TimeoutOverride();
        timeoutOverride.setChatTimeoutMs(45_000L);
        timeoutOverride.setStreamingTimeoutMs(180_000L);
        override.setTimeout(timeoutOverride);
        properties.getResilience().getProviders().put("openai", override);

        BeanPostProcessor processor = createProcessor(properties);

        LlmProvider rawProvider = mockProvider("openai");
        Object result = processor.postProcessAfterInitialization(rawProvider, "openAiProvider");

        // Wrapping succeeds with the override in place.
        assertThat(result).isInstanceOf(ResilientLlmProvider.class);
    }

    @Test
    void beanPostProcessor_usesGlobalConfigWhenNoOverride() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().getRetry().setMaxAttempts(5);
        properties.getResilience().getRetry().setInitialBackoffMs(1000);

        BeanPostProcessor processor = createProcessor(properties);

        LlmProvider rawProvider = mockProvider("anthropic");
        Object result = processor.postProcessAfterInitialization(rawProvider, "anthropicProvider");

        assertThat(result).isInstanceOf(ResilientLlmProvider.class);
    }

    @Test
    void beanPostProcessor_perProviderRetryOverride() {
        GatewayProperties properties = new GatewayProperties();

        GatewayProperties.ProviderResilienceOverride override = new GatewayProperties.ProviderResilienceOverride();
        GatewayProperties.RetryOverride retryOverride = new GatewayProperties.RetryOverride();
        retryOverride.setMaxAttempts(5);
        retryOverride.setInitialBackoffMs(100L);
        retryOverride.setBackoffMultiplier(1.5);
        retryOverride.setMaxBackoffMs(5_000L);
        override.setRetry(retryOverride);
        properties.getResilience().getProviders().put("openai", override);

        BeanPostProcessor processor = createProcessor(properties);

        LlmProvider rawProvider = mockProvider("openai");
        Object result = processor.postProcessAfterInitialization(rawProvider, "openAiProvider");

        assertThat(result).isInstanceOf(ResilientLlmProvider.class);
    }

    @Test
    void beanPostProcessor_perProviderCircuitBreakerOverride() {
        GatewayProperties properties = new GatewayProperties();

        GatewayProperties.ProviderResilienceOverride override = new GatewayProperties.ProviderResilienceOverride();
        GatewayProperties.CircuitBreakerOverride cbOverride = new GatewayProperties.CircuitBreakerOverride();
        cbOverride.setFailureRateThreshold(80f);
        cbOverride.setSlidingWindowSize(20);
        cbOverride.setMinimumNumberOfCalls(10);
        cbOverride.setWaitDurationInOpenStateMs(60_000L);
        cbOverride.setPermittedCallsInHalfOpen(5);
        override.setCircuitBreaker(cbOverride);
        properties.getResilience().getProviders().put("openai", override);

        BeanPostProcessor processor = createProcessor(properties);

        LlmProvider rawProvider = mockProvider("openai");
        Object result = processor.postProcessAfterInitialization(rawProvider, "openAiProvider");

        assertThat(result).isInstanceOf(ResilientLlmProvider.class);
    }

    @Test
    void beanPostProcessor_skipsMockProvider() {
        BeanPostProcessor processor = createProcessor(new GatewayProperties());

        MockProvider mockProv = new MockProvider("test response", 0, 0, 0.0);
        Object result = processor.postProcessAfterInitialization(mockProv, "mockProvider");

        // MockProvider is returned as it is, never wrapped.
        assertThat(result).isSameAs(mockProv);
        assertThat(result).isNotInstanceOf(ResilientLlmProvider.class);
    }

    // -------------------------------------------------------------------------
    // Wrapped provider functional verification
    // -------------------------------------------------------------------------

    @Test
    void wrappedProvider_chatDelegatesToOriginal() {
        BeanPostProcessor processor = createProcessor(new GatewayProperties());

        LlmProvider rawProvider = mockProvider("openai");
        ChatResponse expected = ChatResponse.builder()
                .id("resp-1").model("gpt-4o").choices(List.of()).build();
        when(rawProvider.chat(any())).thenReturn(expected);

        ResilientLlmProvider wrapped = (ResilientLlmProvider)
                processor.postProcessAfterInitialization(rawProvider, "openAiProvider");

        ChatResponse result = wrapped.chat(chatRequest());
        assertThat(result.getId()).isEqualTo("resp-1");
    }

    @Test
    void wrappedProvider_retriesOnProviderError() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().getRetry().setMaxAttempts(2);
        properties.getResilience().getRetry().setInitialBackoffMs(10);

        BeanPostProcessor processor = createProcessor(properties);

        LlmProvider rawProvider = mockProvider("openai");
        java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger();
        when(rawProvider.chat(any())).thenAnswer(inv -> {
            if (callCount.incrementAndGet() < 2) {
                throw new GatewayException("PROVIDER_ERROR", "transient error");
            }
            return ChatResponse.builder().id("recovered").model("gpt-4o").choices(List.of()).build();
        });

        ResilientLlmProvider wrapped = (ResilientLlmProvider)
                processor.postProcessAfterInitialization(rawProvider, "openAiProvider");

        ChatResponse result = wrapped.chat(chatRequest());
        assertThat(result.getId()).isEqualTo("recovered");
    }

    // -------------------------------------------------------------------------
    // Retry counter
    // -------------------------------------------------------------------------

    // Every retry must show up on gateway_retries_total, not only in the log.
    @Test
    void aRetriedCallIncrementsTheRetryCounter() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().getRetry().setMaxAttempts(3);
        properties.getResilience().getRetry().setInitialBackoffMs(1);

        LlmProvider flaky = mockProvider("openai");
        when(flaky.chat(any())).thenThrow(new com.dvarahq.core.exception.GatewayException(
                "PROVIDER_ERROR", "upstream 503"));

        LlmProvider wrapped = (LlmProvider) createProcessor(properties, registry)
                .postProcessAfterInitialization(flaky, "openai");
        try {
            wrapped.chat(com.dvarahq.core.model.ChatRequest.builder().model("gpt-4o").build());
        } catch (RuntimeException expected) {
            // the call still fails; what matters is that the retries were counted
        }

        io.micrometer.core.instrument.Counter counter =
                registry.find("gateway_retries_total").tag("provider", "openai").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(2.0);   // three attempts, two of them retries
    }

    // -------------------------------------------------------------------------
    // What is retried, what counts against the shared circuit, and what a timeout stops
    // -------------------------------------------------------------------------

    /** Calls the wrapped provider {@code times} times and returns each outcome's code. */
    private static List<String> codesOf(LlmProvider wrapped, int times) {
        List<String> codes = new java.util.ArrayList<>();
        for (int i = 0; i < times; i++) {
            try {
                wrapped.chat(chatRequest());
                codes.add("OK");
            } catch (GatewayException e) {
                codes.add(e.getCode());
            }
        }
        return codes;
    }

    private static GatewayProperties fastRetries() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().getRetry().setMaxAttempts(3);
        properties.getResilience().getRetry().setInitialBackoffMs(1);
        return properties;
    }

    // A 4xx is about one caller's credential. Retrying it wastes upstream calls, and counting it
    // against the circuit would let one workspace's bad key cut the provider off for everyone.
    @Test
    void aClientErrorIsNotRetriedAndDoesNotOpenTheSharedCircuit() {
        LlmProvider raw = mockProvider("openai");
        java.util.concurrent.atomic.AtomicInteger upstream = new java.util.concurrent.atomic.AtomicInteger();
        when(raw.chat(any())).thenAnswer(inv -> {
            upstream.incrementAndGet();
            throw GatewayException.upstream(401, "OpenAI API error 401");
        });
        LlmProvider wrapped = (LlmProvider) createProcessor(fastRetries())
                .postProcessAfterInitialization(raw, "openAiProvider");

        assertThat(codesOf(wrapped, 10)).containsOnly("PROVIDER_ERROR");
        assertThat(upstream.get()).as("each client error is sent once").isEqualTo(10);
    }

    @Test
    void aServerErrorIsStillRetriedAndStillOpensTheCircuit() {
        LlmProvider raw = mockProvider("openai");
        java.util.concurrent.atomic.AtomicInteger upstream = new java.util.concurrent.atomic.AtomicInteger();
        when(raw.chat(any())).thenAnswer(inv -> {
            upstream.incrementAndGet();
            throw GatewayException.upstream(503, "OpenAI API error 503");
        });
        LlmProvider wrapped = (LlmProvider) createProcessor(fastRetries())
                .postProcessAfterInitialization(raw, "openAiProvider");

        List<String> codes = codesOf(wrapped, 10);
        assertThat(codes.getFirst()).isEqualTo("PROVIDER_ERROR");
        assertThat(codes).contains("PROVIDER_CIRCUIT_OPEN");
        assertThat(upstream.get()).as("server errors are retried").isGreaterThan(codes.indexOf("PROVIDER_CIRCUIT_OPEN"));
    }

    // A 429 says one credential is over its quota: worth a retry after backoff, not a reason to stop
    // every other workspace calling the provider.
    @Test
    void aRateLimitIsRetriedButDoesNotOpenTheSharedCircuit() {
        LlmProvider raw = mockProvider("openai");
        java.util.concurrent.atomic.AtomicInteger upstream = new java.util.concurrent.atomic.AtomicInteger();
        when(raw.chat(any())).thenAnswer(inv -> {
            upstream.incrementAndGet();
            throw GatewayException.upstream(429, "OpenAI API error 429");
        });
        LlmProvider wrapped = (LlmProvider) createProcessor(fastRetries())
                .postProcessAfterInitialization(raw, "openAiProvider");

        assertThat(codesOf(wrapped, 10)).containsOnly("PROVIDER_ERROR");
        assertThat(upstream.get()).as("three attempts per call").isEqualTo(30);
    }

    @Test
    void aRefusalThatIsNotAboutTheUpstreamDoesNotOpenTheCircuit() {
        LlmProvider raw = mockProvider("openai");
        when(raw.chat(any())).thenThrow(new GatewayException("WORKSPACE_CREDENTIAL_REQUIRED", "no key"));
        LlmProvider wrapped = (LlmProvider) createProcessor(fastRetries())
                .postProcessAfterInitialization(raw, "openAiProvider");

        assertThat(codesOf(wrapped, 10)).containsOnly("WORKSPACE_CREDENTIAL_REQUIRED");
    }

    // Once the caller has been told the call timed out, the retry loop must not keep calling the
    // upstream in the background.
    @Test
    void aTimedOutCallStopsCallingTheUpstream() throws Exception {
        GatewayProperties properties = fastRetries();
        properties.getResilience().getTimeout().setChatTimeoutMs(150);
        LlmProvider raw = mockProvider("openai");
        java.util.concurrent.atomic.AtomicInteger upstream = new java.util.concurrent.atomic.AtomicInteger();
        when(raw.chat(any())).thenAnswer(inv -> {
            upstream.incrementAndGet();
            Thread.sleep(400);
            throw GatewayException.upstream(503, "OpenAI API error 503");
        });
        LlmProvider wrapped = (LlmProvider) createProcessor(properties)
                .postProcessAfterInitialization(raw, "openAiProvider");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> wrapped.chat(chatRequest()))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("timed out");
        Thread.sleep(1500);
        assertThat(upstream.get()).as("no attempt after the timeout").isEqualTo(1);
    }

    private static BeanPostProcessor createProcessor(GatewayProperties properties) {
        return createProcessor(properties, new Resilience4jProviderHealthRegistry());
    }

    private static BeanPostProcessor createProcessor(GatewayProperties properties,
                                                      io.micrometer.core.instrument.MeterRegistry registry) {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(GatewayProperties.class)).thenReturn(properties);
        // created outside when(...): Mockito reports a mock created inside a stubbing as unfinished
        var healthProvider = healthRegistryProvider(new Resilience4jProviderHealthRegistry());
        when(context.getBeanProvider(Resilience4jProviderHealthRegistry.class)).thenReturn(healthProvider);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> p =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(registry);
        when(context.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)).thenReturn(p);
        return ResilienceAutoConfiguration.resilientProviderPostProcessor(context);
    }

    private static BeanPostProcessor createProcessor(GatewayProperties properties,
                                                      Resilience4jProviderHealthRegistry healthRegistry) {
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getBean(GatewayProperties.class)).thenReturn(properties);
        // created outside when(...): Mockito reports a mock created inside a stubbing as unfinished
        var healthProvider = healthRegistryProvider(healthRegistry);
        when(context.getBeanProvider(Resilience4jProviderHealthRegistry.class)).thenReturn(healthProvider);
        // the retry counter's registry is optional: a host application without actuator has none,
        // and a retry that cannot be counted must still be retried
        org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> noRegistry =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(noRegistry.getIfAvailable()).thenReturn(null);
        when(context.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)).thenReturn(noRegistry);
        return ResilienceAutoConfiguration.resilientProviderPostProcessor(context);
    }

    /**
     * The registry is resolved through an {@code ObjectProvider} rather than {@code getBean}: an
     * application that supplies its own {@code ProviderHealthRegistry} stands the Resilience4j one
     * down, and the wrapping must still happen with nowhere to record circuit state.
     */
    @SuppressWarnings("unchecked")
    private static org.springframework.beans.factory.ObjectProvider<Resilience4jProviderHealthRegistry>
            healthRegistryProvider(Resilience4jProviderHealthRegistry registry) {
        org.springframework.beans.factory.ObjectProvider<Resilience4jProviderHealthRegistry> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    private static LlmProvider mockProvider(String name) {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn(name);
        when(provider.supports(any())).thenReturn(true);
        when(provider.capabilities()).thenReturn(new ProviderCapabilities(true, false, false, false, false, 128000));
        return provider;
    }

    private static ChatRequest chatRequest() {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("hello")))
                .build();
    }

    // -------------------------------------------------------------------------
    // A partial override keeps the global values for the fields it does not name
    // -------------------------------------------------------------------------
    //
    // The override tests above set every field of a section, so they cannot tell the resolution
    // rule apart. These set one field and assert the others come from the global section.

    @Test
    void partialTimeoutOverride_keepsTheGlobalStreamingTimeout() {
        // Lowering chat-timeout-ms for one provider must not reset its streaming timeout to the
        // class default; the global value applies.
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().getTimeout().setStreamingTimeoutMs(300_000);

        var override = new GatewayProperties.ProviderResilienceOverride();
        var timeout = new GatewayProperties.TimeoutOverride();
        timeout.setChatTimeoutMs(5_000L);
        override.setTimeout(timeout);
        properties.getResilience().getProviders().put("openai", override);

        var resolved = ResilienceAutoConfiguration.resolveTimeoutConfig("openai", properties.getResilience());

        assertThat(resolved.getChatTimeoutMs()).isEqualTo(5_000);
        assertThat(resolved.getStreamingTimeoutMs())
                .as("the global streaming timeout, not the class default")
                .isEqualTo(300_000);
    }

    @Test
    void partialRetryOverride_keepsTheGlobalBackoff() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().getRetry().setInitialBackoffMs(5_000);

        var override = new GatewayProperties.ProviderResilienceOverride();
        var retry = new GatewayProperties.RetryOverride();
        retry.setMaxAttempts(5);
        override.setRetry(retry);
        properties.getResilience().getProviders().put("anthropic", override);

        var resolved = ResilienceAutoConfiguration.resolveRetryConfig("anthropic", properties.getResilience());

        assertThat(resolved.getMaxAttempts()).isEqualTo(5);
        assertThat(resolved.getInitialBackoffMs())
                .as("the global backoff, not the class default of 500")
                .isEqualTo(5_000);
    }

    @Test
    void partialCircuitBreakerOverride_keepsTheGlobalWindow() {
        GatewayProperties properties = new GatewayProperties();
        properties.getResilience().getCircuitBreaker().setSlidingWindowSize(100);
        properties.getResilience().getCircuitBreaker().setMinimumNumberOfCalls(50);

        var override = new GatewayProperties.ProviderResilienceOverride();
        var cb = new GatewayProperties.CircuitBreakerOverride();
        cb.setFailureRateThreshold(80f);
        override.setCircuitBreaker(cb);
        properties.getResilience().getProviders().put("openai", override);

        var resolved = ResilienceAutoConfiguration.resolveCbConfig("openai", properties.getResilience());

        assertThat(resolved.getFailureRateThreshold()).isEqualTo(80);
        assertThat(resolved.getSlidingWindowSize()).isEqualTo(100);
        assertThat(resolved.getMinimumNumberOfCalls()).isEqualTo(50);
    }

    @Test
    void noOverride_isTheGlobalObjectItself() {
        // Nothing to merge means nothing copied: the global instance is returned as it is.
        GatewayProperties properties = new GatewayProperties();

        assertThat(ResilienceAutoConfiguration.resolveRetryConfig("openai", properties.getResilience()))
                .isSameAs(properties.getResilience().getRetry());
    }
}
