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
package com.dvarahq.ratelimit;

import com.dvarahq.core.ratelimit.RateLimiter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers the in-process {@link RateLimiter} when no other limiter bean is present.
 *
 * <p>It runs at {@code LOWEST_PRECEDENCE} so that a limiter registered by another module or by
 * the application already exists when {@link ConditionalOnMissingBean} is evaluated, and it is not
 * {@code @Primary}, so it never outranks one. The failure those two guard against is silent: a
 * per-process limiter winning over a shared one admits N times the configured rate across N
 * replicas, and nothing errors.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
public class InProcessRateLimiterAutoConfiguration {

    /** Reads its settings with {@code @Value}, so no properties bean needs to be present. */
    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    public RateLimiter inProcessRateLimiter(
            @Value("${dvara.llm-gateway.rate-limit.enabled:false}") boolean enabled,
            @Value("${dvara.llm-gateway.rate-limit.per-key.requests-per-minute:100}") int requestLimit,
            @Value("${dvara.llm-gateway.rate-limit.per-key.tokens-per-minute:100000}") int tokenLimit,
            // Burst capacity. Zero means the same as the rate.
            @Value("${dvara.llm-gateway.rate-limit.per-key.requests-burst:0}") int requestBurst,
            @Value("${dvara.llm-gateway.rate-limit.per-key.tokens-burst:0}") int tokenBurst,
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new InProcessRateLimiter(enabled, requestLimit, tokenLimit,
                requestBurst <= 0 ? requestLimit : requestBurst,
                tokenBurst <= 0 ? tokenLimit : tokenBurst,
                meterRegistry.getIfAvailable(), io.github.bucket4j.TimeMeter.SYSTEM_MILLISECONDS);
    }
}