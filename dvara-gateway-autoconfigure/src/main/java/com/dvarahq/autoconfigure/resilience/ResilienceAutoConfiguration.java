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
import com.dvarahq.autoconfigure.ProviderAutoConfiguration;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.providers.mock.MockProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

@AutoConfiguration(after = ProviderAutoConfiguration.class)
@EnableConfigurationProperties(GatewayProperties.class)
@ConditionalOnProperty(name = "dvara.llm-gateway.resilience.enabled", matchIfMissing = true)
public class ResilienceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ResilienceAutoConfiguration.class);

    /**
     * The circuit-breaker-backed view of provider health, and the recorder the wrapping writes into.
     *
     * <p>Conditional on the interface rather than {@code @Primary}: the disabled baseline in
     * {@code GatewayAutoConfiguration} is conditional too and that class is ordered after this one,
     * so exactly one bean of this type exists in every posture. An application that registers its
     * own registry therefore wins without having to mark it {@code @Primary} — and when it does,
     * the wrapping below has nowhere to record circuit state and says so once, rather than failing
     * to start.
     */
    @Bean
    @ConditionalOnMissingBean(ProviderHealthRegistry.class)
    public Resilience4jProviderHealthRegistry providerHealthRegistry() {
        return new Resilience4jProviderHealthRegistry();
    }

    /**
     * Conditional on the interface for the same reason as the registry above: one bean of this type
     * in every posture, and an application's own resolver wins on absence rather than losing to a
     * framework {@code @Primary}.
     */
    @Bean
    @ConditionalOnMissingBean(FallbackResolver.class)
    public FallbackResolver fallbackResolver(GatewayProperties properties) {
        if (properties.getResilience().getFallback().isEnabled()) {
            return new ConfigurableFallbackResolver();
        }
        return (request, failedProvider, allProviders) -> java.util.List.of();
    }

    @Bean
    public static BeanPostProcessor resilientProviderPostProcessor(ApplicationContext context) {
        return new BeanPostProcessor() {
            private GatewayProperties properties;
            private Resilience4jProviderHealthRegistry healthRegistry;
            private io.micrometer.core.instrument.MeterRegistry meterRegistry;

            private void ensureInitialized() {
                if (properties == null) {
                    properties = context.getBean(GatewayProperties.class);
                    // Optional: an application that supplies its own ProviderHealthRegistry stands
                    // this one down, and then there is nowhere to record circuit state. The
                    // wrapping still happens — the breaker, retry and timeout are the point — but
                    // the application's registry answers health questions from whatever it knows.
                    healthRegistry = context.getBeanProvider(Resilience4jProviderHealthRegistry.class)
                            .getIfAvailable();
                    if (healthRegistry == null) {
                        log.info("Provider health is reported by an application-supplied "
                                + "ProviderHealthRegistry; circuit-breaker state will not feed it.");
                    }
                    // Optional: a host application without actuator has no registry, and a retry
                    // that cannot be counted must still be retried.
                    meterRegistry = context.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class)
                            .getIfAvailable();
                }
            }

            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof LlmProvider provider && !(bean instanceof ResilientLlmProvider)) {
                    // MockProvider is always healthy — never wrap with circuit breaker
                    if (bean instanceof MockProvider) {
                        log.info("Skipping resilience wrapping for MockProvider (always healthy)");
                        return bean;
                    }
                    ensureInitialized();
                    return wrapWithResilience(provider, properties, healthRegistry, meterRegistry);
                }
                return bean;
            }
        };
    }

    private static ResilientLlmProvider wrapWithResilience(LlmProvider provider,
                                                    GatewayProperties properties,
                                                    Resilience4jProviderHealthRegistry healthRegistry,
                                                    io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        String name = provider.name();
        GatewayProperties.Resilience resilience = properties.getResilience();

        // Resolve per-provider overrides
        GatewayProperties.RetryConfig retryConfig = resolveRetryConfig(name, resilience);
        GatewayProperties.CircuitBreakerConfig cbConfig = resolveCbConfig(name, resilience);
        GatewayProperties.TimeoutConfig timeoutConfig = resolveTimeoutConfig(name, resilience);

        // Circuit Breaker
        CircuitBreakerConfig cbCfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbConfig.getFailureRateThreshold())
                .slidingWindowSize(cbConfig.getSlidingWindowSize())
                .minimumNumberOfCalls(cbConfig.getMinimumNumberOfCalls())
                .waitDurationInOpenState(Duration.ofMillis(cbConfig.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(cbConfig.getPermittedCallsInHalfOpen())
                // One breaker serves every workspace on this provider, so only what says the provider
                // itself is failing may count against it: not a caller's bad key or oversized prompt.
                .recordException(ResilienceAutoConfiguration::isProviderFailure)
                .build();
        CircuitBreaker circuitBreaker = CircuitBreaker.of("provider-" + name, cbCfg);

        // Log state transitions
        circuitBreaker.getEventPublisher()
                .onStateTransition(event ->
                        log.info("Circuit breaker [{}] state transition: {}", name, event.getStateTransition()));

        if (healthRegistry != null) {
            healthRegistry.register(name, circuitBreaker);
        }

        // Retry
        RetryConfig retryCfg = RetryConfig.custom()
                .maxAttempts(retryConfig.getMaxAttempts())
                .intervalFunction(attempt -> {
                    long delay = (long) (retryConfig.getInitialBackoffMs()
                            * Math.pow(retryConfig.getBackoffMultiplier(), attempt - 1));
                    return Math.min(delay, retryConfig.getMaxBackoffMs());
                })
                .retryOnException(e -> isRetryable(e))
                .build();
        Retry retry = Retry.of("provider-" + name, retryCfg);

        // Counted here rather than through GatewayMetrics, which is in the runtime module this
        // module cannot see, so gateway_retries_total is emitted wherever the retry runs.
        retry.getEventPublisher()
                .onRetry(event -> {
                    log.warn("Retry attempt #{} for provider [{}]: {}",
                            event.getNumberOfRetryAttempts(), name, event.getLastThrowable().getMessage());
                    if (meterRegistry != null) {
                        io.micrometer.core.instrument.Counter.builder("gateway_retries_total")
                                .description("Total provider call retries")
                                .tag("provider", name)
                                .register(meterRegistry)
                                .increment();
                    }
                });

        // Time Limiters
        TimeLimiter chatTimeLimiter = TimeLimiter.of("provider-" + name + "-chat",
                TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMillis(timeoutConfig.getChatTimeoutMs()))
                        .cancelRunningFuture(true)
                        .build());

        TimeLimiter streamingTimeLimiter = TimeLimiter.of("provider-" + name + "-stream",
                TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofMillis(timeoutConfig.getStreamingTimeoutMs()))
                        .cancelRunningFuture(true)
                        .build());

        log.info("Wrapped provider [{}] with resilience (retry={}, cb-threshold={}%, timeout={}ms)",
                name, retryConfig.getMaxAttempts(), cbConfig.getFailureRateThreshold(),
                timeoutConfig.getChatTimeoutMs());

        return new ResilientLlmProvider(provider, circuitBreaker, retry, chatTimeLimiter, streamingTimeLimiter);
    }

    /**
     * Whether a failed attempt is worth repeating. A provider error with no status (a connection
     * error, a broken stream open) or a 5xx, 408 or 429 may succeed a moment later; a 4xx about the
     * request or the key will fail the same way every time, so sending it again only multiplies the
     * upstream calls. A cancelled or interrupted call is never retried: its caller has gone.
     */
    static boolean isRetryable(Throwable throwable) {
        if (isCancellation(throwable)) {
            return false;
        }
        if (throwable instanceof com.dvarahq.core.exception.GatewayException ge) {
            if (!"PROVIDER_ERROR".equals(ge.getCode())) {
                return false;   // not about the upstream: NO_PROVIDER, a credential refusal, a bad request
            }
            Integer status = ge.getUpstreamStatus();
            return status == null || status >= 500 || status == 408 || status == 429;
        }
        return true;   // network errors and other unexpected failures
    }

    /**
     * Whether a failure counts against the provider's circuit. Narrower than {@link #isRetryable}: a
     * 429 is worth retrying after a backoff but says one credential is over its quota, not that the
     * provider is down, and the breaker is shared by every workspace calling this provider.
     */
    static boolean isProviderFailure(Throwable throwable) {
        if (isCancellation(throwable)) {
            return false;
        }
        if (throwable instanceof com.dvarahq.core.exception.GatewayException ge) {
            if (!"PROVIDER_ERROR".equals(ge.getCode())) {
                return false;
            }
            Integer status = ge.getUpstreamStatus();
            return status == null || status >= 500 || status == 408;
        }
        return true;
    }

    private static boolean isCancellation(Throwable throwable) {
        return throwable instanceof InterruptedException
                || throwable instanceof java.util.concurrent.CancellationException;
    }

    /**
     * The settings this provider runs under: the global ones, with any field the provider names
     * replacing its own.
     *
     * <p>Merged per field rather than per section, because each config class carries its own field
     * defaults: taking the override's whole section would reset every field the provider did not
     * name to those defaults rather than to what the operator configured globally.
     */
    static GatewayProperties.RetryConfig resolveRetryConfig(String providerName,
                                                              GatewayProperties.Resilience resilience) {
        GatewayProperties.RetryConfig global = resilience.getRetry();
        GatewayProperties.ProviderResilienceOverride override = resilience.getProviders().get(providerName);
        GatewayProperties.RetryOverride o = override == null ? null : override.getRetry();
        if (o == null) {
            return global;
        }
        GatewayProperties.RetryConfig merged = new GatewayProperties.RetryConfig();
        merged.setMaxAttempts(o.getMaxAttempts() != null ? o.getMaxAttempts() : global.getMaxAttempts());
        merged.setInitialBackoffMs(
                o.getInitialBackoffMs() != null ? o.getInitialBackoffMs() : global.getInitialBackoffMs());
        merged.setBackoffMultiplier(
                o.getBackoffMultiplier() != null ? o.getBackoffMultiplier() : global.getBackoffMultiplier());
        merged.setMaxBackoffMs(o.getMaxBackoffMs() != null ? o.getMaxBackoffMs() : global.getMaxBackoffMs());
        return merged;
    }

    /** Field by field over the global circuit breaker — see {@link #resolveRetryConfig}. */
    static GatewayProperties.CircuitBreakerConfig resolveCbConfig(String providerName,
                                                                    GatewayProperties.Resilience resilience) {
        GatewayProperties.CircuitBreakerConfig global = resilience.getCircuitBreaker();
        GatewayProperties.ProviderResilienceOverride override = resilience.getProviders().get(providerName);
        GatewayProperties.CircuitBreakerOverride o = override == null ? null : override.getCircuitBreaker();
        if (o == null) {
            return global;
        }
        GatewayProperties.CircuitBreakerConfig merged = new GatewayProperties.CircuitBreakerConfig();
        merged.setFailureRateThreshold(o.getFailureRateThreshold() != null
                ? o.getFailureRateThreshold() : global.getFailureRateThreshold());
        merged.setSlidingWindowSize(o.getSlidingWindowSize() != null
                ? o.getSlidingWindowSize() : global.getSlidingWindowSize());
        merged.setMinimumNumberOfCalls(o.getMinimumNumberOfCalls() != null
                ? o.getMinimumNumberOfCalls() : global.getMinimumNumberOfCalls());
        merged.setWaitDurationInOpenStateMs(o.getWaitDurationInOpenStateMs() != null
                ? o.getWaitDurationInOpenStateMs() : global.getWaitDurationInOpenStateMs());
        merged.setPermittedCallsInHalfOpen(o.getPermittedCallsInHalfOpen() != null
                ? o.getPermittedCallsInHalfOpen() : global.getPermittedCallsInHalfOpen());
        return merged;
    }

    /** Field by field over the global timeouts — see {@link #resolveRetryConfig}. */
    static GatewayProperties.TimeoutConfig resolveTimeoutConfig(String providerName,
                                                                  GatewayProperties.Resilience resilience) {
        GatewayProperties.TimeoutConfig global = resilience.getTimeout();
        GatewayProperties.ProviderResilienceOverride override = resilience.getProviders().get(providerName);
        GatewayProperties.TimeoutOverride o = override == null ? null : override.getTimeout();
        if (o == null) {
            return global;
        }
        GatewayProperties.TimeoutConfig merged = new GatewayProperties.TimeoutConfig();
        merged.setChatTimeoutMs(o.getChatTimeoutMs() != null ? o.getChatTimeoutMs() : global.getChatTimeoutMs());
        merged.setStreamingTimeoutMs(o.getStreamingTimeoutMs() != null
                ? o.getStreamingTimeoutMs() : global.getStreamingTimeoutMs());
        return merged;
    }
}
