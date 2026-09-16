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

import com.dvarahq.core.resilience.ProviderHealthStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class Resilience4jProviderHealthRegistryTest {

    // -------------------------------------------------------------------------
    // getHealth()
    // -------------------------------------------------------------------------

    @Test
    void unregisteredProvider_returnsHealthy() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        assertThat(registry.getHealth("unknown")).isEqualTo(ProviderHealthStatus.HEALTHY);
    }

    @Test
    void closedCircuitBreaker_returnsHealthy() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
        registry.register("openai", cb);

        assertThat(registry.getHealth("openai")).isEqualTo(ProviderHealthStatus.HEALTHY);
    }

    @Test
    void openCircuitBreaker_returnsUnhealthy() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        CircuitBreaker cb = createCircuitBreaker();
        registry.register("openai", cb);

        // Force open by recording failures
        cb.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
        cb.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(registry.getHealth("openai")).isEqualTo(ProviderHealthStatus.UNHEALTHY);
    }

    @Test
    void halfOpenCircuitBreaker_returnsDegraded() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMillis(1))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        registry.register("openai", cb);

        cb.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
        cb.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
        cb.transitionToHalfOpenState();

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(registry.getHealth("openai")).isEqualTo(ProviderHealthStatus.DEGRADED);
    }

    @Test
    void forcedOpenCircuitBreaker_returnsUnhealthy() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
        registry.register("openai", cb);

        cb.transitionToForcedOpenState();

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.FORCED_OPEN);
        assertThat(registry.getHealth("openai")).isEqualTo(ProviderHealthStatus.UNHEALTHY);
    }

    /**
     * A DISABLED breaker permits every call, so the provider is healthy. In resilience4j, DISABLED
     * means the breaker records nothing and lets all requests through; an operator disables one to
     * keep sending traffic to a provider whose failures they have judged tolerable.
     */
    @Test
    void disabledCircuitBreaker_isHealthyBecauseItPermitsEveryCall() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
        registry.register("openai", cb);

        cb.transitionToDisabledState();

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.DISABLED);
        assertThat(registry.getHealth("openai")).isEqualTo(ProviderHealthStatus.HEALTHY);
        assertThat(registry.isAvailable("openai")).isTrue();
    }

    @Test
    void metricsOnlyCircuitBreaker_returnsHealthy() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
        registry.register("openai", cb);

        cb.transitionToMetricsOnlyState();

        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.METRICS_ONLY);
        assertThat(registry.getHealth("openai")).isEqualTo(ProviderHealthStatus.HEALTHY);
    }

    // -------------------------------------------------------------------------
    // isAvailable()
    // -------------------------------------------------------------------------

    @Test
    void isAvailable_trueForHealthy() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.ofDefaults());
        registry.register("openai", cb);

        assertThat(registry.isAvailable("openai")).isTrue();
    }

    @Test
    void isAvailable_trueForDegraded() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        CircuitBreaker cb = CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMillis(1))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
        registry.register("openai", cb);

        cb.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
        cb.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
        cb.transitionToHalfOpenState();

        assertThat(registry.isAvailable("openai")).isTrue();
    }

    @Test
    void isAvailable_falseForUnhealthy() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        CircuitBreaker cb = createCircuitBreaker();
        registry.register("openai", cb);

        cb.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
        cb.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());

        assertThat(registry.isAvailable("openai")).isFalse();
    }

    @Test
    void isAvailable_trueForUnregisteredProvider() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();
        assertThat(registry.isAvailable("unknown")).isTrue();
    }

    // -------------------------------------------------------------------------
    // Multiple providers
    // -------------------------------------------------------------------------

    @Test
    void multipleProviders_independentHealth() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();

        CircuitBreaker healthy = CircuitBreaker.of("healthy", CircuitBreakerConfig.ofDefaults());
        CircuitBreaker unhealthy = createCircuitBreaker();
        registry.register("openai", healthy);
        registry.register("anthropic", unhealthy);

        // Force anthropic open
        unhealthy.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
        unhealthy.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());

        assertThat(registry.getHealth("openai")).isEqualTo(ProviderHealthStatus.HEALTHY);
        assertThat(registry.getHealth("anthropic")).isEqualTo(ProviderHealthStatus.UNHEALTHY);
        assertThat(registry.isAvailable("openai")).isTrue();
        assertThat(registry.isAvailable("anthropic")).isFalse();
    }

    @Test
    void register_updatesExistingProvider() {
        Resilience4jProviderHealthRegistry registry = new Resilience4jProviderHealthRegistry();

        CircuitBreaker first = createCircuitBreaker();
        registry.register("openai", first);
        first.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
        first.onError(0, TimeUnit.MILLISECONDS, new RuntimeException());
        assertThat(registry.getHealth("openai")).isEqualTo(ProviderHealthStatus.UNHEALTHY);

        // Re-register with a fresh circuit breaker
        CircuitBreaker second = CircuitBreaker.of("replacement", CircuitBreakerConfig.ofDefaults());
        registry.register("openai", second);
        assertThat(registry.getHealth("openai")).isEqualTo(ProviderHealthStatus.HEALTHY);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static CircuitBreaker createCircuitBreaker() {
        return CircuitBreaker.of("test", CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build());
    }
}