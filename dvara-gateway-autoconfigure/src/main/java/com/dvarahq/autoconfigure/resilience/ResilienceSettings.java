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
import com.dvarahq.core.provider.LlmProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The resilience settings providers run under, and every provider wrapped with them.
 *
 * <p>The settings start as {@code dvara.llm-gateway.resilience.*}. {@link #apply} replaces them while the gateway
 * runs: every wrapper is reconfigured in place, whether it was built at startup or later (a provider added at run time
 * is wrapped with the settings in force then), so the next call uses the new values and a running call keeps its own.
 * Failover reads {@link #current()} on each failover.
 *
 * <p>A circuit breaker keeps its state when its own resolved settings do not change: a new retry or timeout must not
 * close an open breaker. When they change, the provider gets a fresh breaker, registered for health in its place.
 *
 * <p>{@code enabled} is not applied: turning resilience on or off is restart-only.
 */
public class ResilienceSettings {

    private static final Logger log = LoggerFactory.getLogger(ResilienceSettings.class);

    private final GatewayProperties.Resilience environment;
    private volatile GatewayProperties.Resilience current;
    private final Resilience4jProviderHealthRegistry healthRegistry;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    /** Weak, so a provider the registry dropped is not kept alive here. */
    private final Set<ResilientLlmProvider> wrappers = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * @param environment   the settings from the environment; also the starting settings
     * @param healthRegistry where circuit state is reported; null when the application supplies its own
     * @param meterRegistry where retries are counted; null when there is none
     */
    public ResilienceSettings(GatewayProperties.Resilience environment,
                              Resilience4jProviderHealthRegistry healthRegistry,
                              io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.environment = environment;
        this.current = environment;
        this.healthRegistry = healthRegistry;
        this.meterRegistry = meterRegistry;
    }

    /** The settings from the environment, before any {@link #apply}. Do not modify. */
    public GatewayProperties.Resilience environment() {
        return environment;
    }

    /** The settings in force now. Do not modify; pass a new object to {@link #apply}. */
    public GatewayProperties.Resilience current() {
        return current;
    }

    /** Wraps {@code provider} with the settings in force now, and keeps it so {@link #apply} reaches it. */
    public ResilientLlmProvider wrap(LlmProvider provider) {
        String name = provider.name();
        ResilientLlmProvider wrapped = new ResilientLlmProvider(provider, policiesFor(name, current, null),
                retrySent(name));
        synchronized (wrappers) {
            wrappers.add(wrapped);
        }
        return wrapped;
    }

    /** Replaces the settings and reconfigures every wrapper. */
    public synchronized void apply(GatewayProperties.Resilience next) {
        this.current = java.util.Objects.requireNonNull(next, "settings");
        List<ResilientLlmProvider> all;
        synchronized (wrappers) {
            all = new ArrayList<>(wrappers);
        }
        for (ResilientLlmProvider wrapper : all) {
            wrapper.reconfigure(policiesFor(wrapper.name(), next, wrapper.policies()));
        }
        log.info("Resilience settings applied to {} provider(s): retry={}, cb-threshold={}%, timeout={}ms, "
                + "fallback={}", all.size(), next.getRetry().getMaxAttempts(),
                next.getCircuitBreaker().getFailureRateThreshold(), next.getTimeout().getChatTimeoutMs(),
                next.getFallback().isEnabled());
    }

    /**
     * The policies {@code name} runs under with {@code settings}. Keeps {@code previous}'s breaker when the breaker's
     * resolved settings are unchanged.
     */
    ResilientLlmProvider.Policies policiesFor(String name, GatewayProperties.Resilience settings,
                                              ResilientLlmProvider.Policies previous) {
        GatewayProperties.RetryConfig retryConfig = ResilienceAutoConfiguration.resolveRetryConfig(name, settings);
        GatewayProperties.CircuitBreakerConfig cbConfig = ResilienceAutoConfiguration.resolveCbConfig(name, settings);
        GatewayProperties.TimeoutConfig timeoutConfig = ResilienceAutoConfiguration.resolveTimeoutConfig(name, settings);

        CircuitBreaker circuitBreaker;
        if (previous != null && cbConfig.equals(previous.circuitBreakerSettings())) {
            circuitBreaker = previous.circuitBreaker();
        } else {
            circuitBreaker = circuitBreaker(name, cbConfig);
            if (healthRegistry != null) {
                healthRegistry.register(name, circuitBreaker);
            }
        }

        RetryConfig retryCfg = RetryConfig.custom()
                .maxAttempts(retryConfig.getMaxAttempts())
                .intervalFunction(attempt -> {
                    long delay = (long) (retryConfig.getInitialBackoffMs()
                            * Math.pow(retryConfig.getBackoffMultiplier(), attempt - 1));
                    return Math.min(delay, retryConfig.getMaxBackoffMs());
                })
                .retryOnException(ResilienceAutoConfiguration::isRetryable)
                .build();
        Retry retry = Retry.of("provider-" + name, retryCfg);
        retry.getEventPublisher()
                .onRetry(event -> log.warn("Retry attempt #{} for provider [{}]: {}",
                        event.getNumberOfRetryAttempts(), name, event.getLastThrowable().getMessage()));

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

        log.info("Provider [{}] resilience: retry={}, cb-threshold={}%, timeout={}ms",
                name, retryConfig.getMaxAttempts(), cbConfig.getFailureRateThreshold(), timeoutConfig.getChatTimeoutMs());
        return new ResilientLlmProvider.Policies(circuitBreaker, retry, chatTimeLimiter, streamingTimeLimiter, cbConfig);
    }

    private static CircuitBreaker circuitBreaker(String name, GatewayProperties.CircuitBreakerConfig cbConfig) {
        CircuitBreakerConfig cbCfg = CircuitBreakerConfig.custom()
                .failureRateThreshold(cbConfig.getFailureRateThreshold())
                .slidingWindowSize(cbConfig.getSlidingWindowSize())
                .minimumNumberOfCalls(cbConfig.getMinimumNumberOfCalls())
                .waitDurationInOpenState(Duration.ofMillis(cbConfig.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(cbConfig.getPermittedCallsInHalfOpen())
                // Move to half-open when the wait is over, without waiting for a call. The dispatcher
                // sends nothing to a provider whose breaker is open, so a breaker that only moves when a
                // call arrives never gets its trial call, and the provider stays out until a restart.
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // One breaker serves every workspace on this provider, so only what says the provider
                // itself is failing may count against it: not a caller's bad key or oversized prompt.
                .recordException(ResilienceAutoConfiguration::isProviderFailure)
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("provider-" + name, cbCfg);
        breaker.getEventPublisher()
                .onStateTransition(event ->
                        log.info("Circuit breaker [{}] state transition: {}", name, event.getStateTransition()));
        return breaker;
    }

    /**
     * Counts a retry when it is sent (see {@link ResilientLlmProvider}), as {@code gateway_retries_total}. Here rather
     * than through the runtime's metrics, which this module cannot see.
     */
    private Runnable retrySent(String name) {
        return meterRegistry == null ? null
                : () -> io.micrometer.core.instrument.Counter.builder("gateway_retries_total")
                        .description("Total provider call retries")
                        .tag("provider", name)
                        .register(meterRegistry)
                        .increment();
    }
}
