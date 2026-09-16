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

import com.dvarahq.core.ratelimit.RateLimitResult;
import com.dvarahq.core.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@link RateLimiter} a context ends up with.
 *
 * <p>With nothing else present, the in-process limiter is used. When an application or another
 * module registers its own {@link RateLimiter}, that one must be the only limiter in the context:
 * a shared limiter that lost to the in-process one would leave every replica admitting the full
 * rate on its own, and nothing about the running system would say so.
 */
class InProcessRateLimiterWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InProcessRateLimiterAutoConfiguration.class));

    @Test
    void withNothingElsePresentTheInProcessLimiterIsUsed() {
        runner.run(context -> assertThat(context.getBean(RateLimiter.class))
                .as("with no other limiter registered, the in-process one must be used")
                .isInstanceOf(InProcessRateLimiter.class));
    }

    @Test
    void anotherLimiterReplacesTheInProcessOne() {
        runner.withUserConfiguration(PrimaryLimiterConfig.class).run(context -> {
            assertThat(context.getBean(RateLimiter.class))
                    .as("a limiter registered by the application must be the one in use")
                    .isNotInstanceOf(InProcessRateLimiter.class);
            assertThat(context.getBeansOfType(RateLimiter.class))
                    .as("the in-process limiter must not be registered alongside it")
                    .hasSize(1);
        });
    }

    /** A limiter an application might register in place of the in-process one. */
    @Configuration
    static class PrimaryLimiterConfig {
        @Bean
        @Primary
        @ConditionalOnMissingBean(name = "neverPresent")
        RateLimiter distributedRateLimiter() {
            return new RateLimiter() {
                @Override public boolean tryAcquire(String key) { return true; }
                @Override public boolean tryAcquire(String key, int permits) { return true; }
                @Override public RateLimitResult checkLimit(String key) { return RateLimitResult.allow(); }
            };
        }
    }
}