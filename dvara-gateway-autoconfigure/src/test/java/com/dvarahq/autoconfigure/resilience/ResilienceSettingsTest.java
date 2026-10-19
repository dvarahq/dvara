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
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Resilience settings change while the gateway runs: {@link ResilienceSettings#apply} reconfigures every wrapper in
 * place, a breaker keeps its state unless its own settings change, and a provider wrapped later gets the settings in
 * force then.
 */
class ResilienceSettingsTest {

    private static GatewayProperties.Resilience settings(int maxAttempts, float failureRate) {
        GatewayProperties.Resilience r = new GatewayProperties().getResilience();
        r.getRetry().setMaxAttempts(maxAttempts);
        r.getRetry().setInitialBackoffMs(1);
        r.getRetry().setMaxBackoffMs(1);
        r.getCircuitBreaker().setFailureRateThreshold(failureRate);
        r.getCircuitBreaker().setMinimumNumberOfCalls(100);   // never opens on its own here
        r.getCircuitBreaker().setSlidingWindowSize(100);
        return r;
    }

    /** Fails every chat call with a retryable provider error, counting the calls that reached it. */
    private static LlmProvider failing(String name, AtomicInteger calls) {
        LlmProvider p = mock(LlmProvider.class);
        when(p.name()).thenReturn(name);
        when(p.chat(any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw GatewayException.upstream(503, "down");
        });
        return p;
    }

    private static ChatRequest request() {
        return ChatRequest.builder().model("gpt-4o").build();
    }

    @Test
    void applyChangesTheRetryOfTheNextCall_onAProviderWrappedBeforeIt() {
        ResilienceSettings rs = new ResilienceSettings(settings(3, 100), null, null);
        AtomicInteger calls = new AtomicInteger();
        ResilientLlmProvider wrapped = rs.wrap(failing("openai", calls));

        assertThatThrownBy(() -> wrapped.chat(request())).isInstanceOf(GatewayException.class);
        assertThat(calls.get()).as("three attempts").isEqualTo(3);

        rs.apply(settings(1, 100));
        calls.set(0);
        assertThatThrownBy(() -> wrapped.chat(request())).isInstanceOf(GatewayException.class);
        assertThat(calls.get()).as("one attempt after apply").isEqualTo(1);
        assertThat(rs.current().getRetry().getMaxAttempts()).isEqualTo(1);
        assertThat(rs.environment().getRetry().getMaxAttempts()).as("the environment is kept").isEqualTo(3);
    }

    @Test
    void anOpenBreakerStaysOpen_whenOnlyRetryChanges_andIsReplacedWhenItsOwnSettingsChange() {
        Resilience4jProviderHealthRegistry health = new Resilience4jProviderHealthRegistry();
        ResilienceSettings rs = new ResilienceSettings(settings(1, 50), health, null);
        ResilientLlmProvider wrapped = rs.wrap(failing("openai", new AtomicInteger()));
        CircuitBreaker first = wrapped.policies().circuitBreaker();
        first.transitionToOpenState();
        assertThat(health.getHealth("openai")).isNotEqualTo(ProviderHealthStatus.HEALTHY);

        rs.apply(settings(2, 50));   // retry only
        assertThat(wrapped.policies().circuitBreaker()).as("same breaker").isSameAs(first);
        assertThat(wrapped.policies().circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        rs.apply(settings(2, 75));   // the breaker's own threshold
        CircuitBreaker second = wrapped.policies().circuitBreaker();
        assertThat(second).as("a fresh breaker").isNotSameAs(first);
        assertThat(second.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(second.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(75f);
        assertThat(health.getHealth("openai")).as("health follows the new breaker")
                .isEqualTo(ProviderHealthStatus.HEALTHY);
    }

    @Test
    void aProviderWrappedAfterApply_getsTheSettingsInForce() {
        ResilienceSettings rs = new ResilienceSettings(settings(3, 50), null, null);
        rs.apply(settings(1, 50));
        AtomicInteger calls = new AtomicInteger();
        ResilientLlmProvider wrapped = rs.wrap(failing("added-later", calls));

        assertThatThrownBy(() -> wrapped.chat(request())).isInstanceOf(GatewayException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void aPerProviderOverride_appliedAtRunTime_reachesOnlyThatProvider() {
        ResilienceSettings rs = new ResilienceSettings(settings(3, 100), null, null);
        AtomicInteger openaiCalls = new AtomicInteger();
        AtomicInteger anthropicCalls = new AtomicInteger();
        ResilientLlmProvider openai = rs.wrap(failing("openai", openaiCalls));
        ResilientLlmProvider anthropic = rs.wrap(failing("anthropic", anthropicCalls));

        GatewayProperties.Resilience next = settings(3, 100);
        GatewayProperties.RetryOverride once = new GatewayProperties.RetryOverride();
        once.setMaxAttempts(1);
        GatewayProperties.ProviderResilienceOverride override = new GatewayProperties.ProviderResilienceOverride();
        override.setRetry(once);
        next.getProviders().put("openai", override);
        rs.apply(next);

        assertThatThrownBy(() -> openai.chat(request())).isInstanceOf(GatewayException.class);
        assertThatThrownBy(() -> anthropic.chat(request())).isInstanceOf(GatewayException.class);
        assertThat(openaiCalls.get()).isEqualTo(1);
        assertThat(anthropicCalls.get()).isEqualTo(3);
    }
}
