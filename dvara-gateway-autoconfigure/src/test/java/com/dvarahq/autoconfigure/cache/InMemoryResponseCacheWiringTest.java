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
package com.dvarahq.autoconfigure.cache;

import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryResponseCacheWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(InMemoryResponseCacheAutoConfiguration.class));

    @Test
    void absentUnlessAskedFor() {
        // A per-process cache changes what a second identical request returns, and its hit rate
        // depends on the replica count. That is a posture an operator chooses.
        runner.run(context -> assertThat(context).doesNotHaveBean(ResponseCache.class));
    }

    @Test
    void registeredWhenEnabled() {
        runner.withPropertyValues("dvara.llm-gateway.cache.in-memory.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(ResponseCache.class)
                        .getBean(ResponseCache.class).isInstanceOf(InMemoryResponseCache.class));
    }

    @Test
    void anotherCacheWins_soThisCannotStandDownAFleetSharedOne() {
        // Another module may register a fleet-shared cache @ConditionalOnMissingBean so that an
        // application's cache stands it down. A bean registered here first would stand that one
        // down instead, swapping a shared cache for a map in one JVM with nothing failing.
        runner.withPropertyValues("dvara.llm-gateway.cache.in-memory.enabled=true")
                .withUserConfiguration(SomebodyElsesCache.class)
                .run(context -> assertThat(context).hasSingleBean(ResponseCache.class)
                        .getBean(ResponseCache.class).isNotInstanceOf(InMemoryResponseCache.class));
    }

    @Test
    void evaluatedLast_whichIsWhatMakesThatOrderingAFactRatherThanAHope() {
        // Conditions are evaluated in auto-configuration order, so the guard above only holds
        // because this one runs after everything that might supply a real cache. Without the
        // annotation the outcome would depend on classpath order.
        assertThat(InMemoryResponseCacheAutoConfiguration.class
                .getAnnotation(org.springframework.boot.autoconfigure.AutoConfigureOrder.class))
                .isNotNull()
                .extracting(org.springframework.boot.autoconfigure.AutoConfigureOrder::value)
                .isEqualTo(org.springframework.core.Ordered.LOWEST_PRECEDENCE);
    }

    @Configuration(proxyBeanMethods = false)
    static class SomebodyElsesCache {
        @Bean
        ResponseCache theirs() {
            return new ResponseCache() {
                @Override
                public Optional<ChatResponse> get(ChatRequest request) {
                    return Optional.empty();
                }

                @Override
                public void put(ChatRequest request, ChatResponse response) {
                }
            };
        }
    }
}
