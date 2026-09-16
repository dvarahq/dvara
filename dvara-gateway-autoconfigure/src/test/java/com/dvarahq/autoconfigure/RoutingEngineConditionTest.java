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
package com.dvarahq.autoconfigure;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.RoutingStrategy;
import com.dvarahq.autoconfigure.routing.DefaultRoutingStrategyFactory;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The default routing engine backs off for a custom engine, not for a custom strategy. An
 * application that registers its own strategy keeps the default engine, and the engine drives that
 * strategy.
 */
class RoutingEngineConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RoutingAutoConfiguration.class))
            .withBean(RoutingStrategyFactory.class, DefaultRoutingStrategyFactory::new);

    private static LlmProvider provider(String name) {
        LlmProvider p = mock(LlmProvider.class);
        when(p.name()).thenReturn(name);
        return p;
    }

    @Test
    void withNothingRegistered_theDefaultEngineRoutesByModelPrefix() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RoutingEngine.class);
            LlmProvider openai = provider("openai");
            when(openai.supports(org.mockito.ArgumentMatchers.any(ChatRequest.class))).thenReturn(true);
            assertThat(context.getBean(RoutingEngine.class)
                    .route(ChatRequest.builder().model("gpt-4o").build(), List.of(openai)))
                    .isSameAs(openai);
        });
    }

    @Test
    void aCustomStrategyBean_keepsTheDefaultEngine_andDrivesIt() {
        AtomicInteger calls = new AtomicInteger();
        runner.withBean(RoutingStrategy.class, () -> (req, providers) -> { calls.incrementAndGet(); return providers.get(0); })
                .run(context -> {
                    assertThat(context).hasSingleBean(RoutingEngine.class);
                    LlmProvider any = provider("custom-pick");
                    assertThat(context.getBean(RoutingEngine.class)
                            .route(ChatRequest.builder().model("whatever").build(), List.of(any)))
                            .isSameAs(any);
                    assertThat(calls.get()).as("the application's strategy made the choice").isEqualTo(1);
                });
    }

    @Test
    void aCustomEngineBean_replacesTheDefault() {
        runner.withUserConfiguration(CustomEngine.class).run(context -> {
            assertThat(context).hasSingleBean(RoutingEngine.class);
            assertThat(context.getBean(RoutingEngine.class)).isSameAs(CustomEngine.INSTANCE);
        });
    }

    /**
     * The provider dispatcher injects an unqualified RoutingStrategy. With an application strategy
     * present there are two candidates; the engine must be the one injected, and the application's
     * strategy must reach requests through it.
     */
    @Test
    void anUnqualifiedRoutingStrategyConsumer_getsTheEngine_whichUsesTheCustomStrategy() {
        AtomicInteger calls = new AtomicInteger();
        runner.withBean("myStrategy", RoutingStrategy.class, () -> (req, providers) -> { calls.incrementAndGet(); return providers.get(0); })
                .withUserConfiguration(StrategyConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    StrategyConsumer consumer = context.getBean(StrategyConsumer.class);
                    assertThat(consumer.strategy).isInstanceOf(RoutingEngine.class);
                    LlmProvider any = provider("custom-pick");
                    assertThat(consumer.strategy.route(ChatRequest.builder().model("whatever").build(), List.of(any))).isSameAs(any);
                    assertThat(calls.get()).isEqualTo(1);
                });
    }

    /** The bean name the dispatcher's parameter would match by name: still the engine, by primacy. */
    @Test
    void aCustomStrategyNamedRoutingStrategy_doesNotBypassTheEngine() {
        runner.withBean("routingStrategy", RoutingStrategy.class, () -> (req, providers) -> providers.get(0))
                .withUserConfiguration(StrategyConsumer.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StrategyConsumer.class).strategy).isInstanceOf(RoutingEngine.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class StrategyConsumer {
        final RoutingStrategy strategy;
        StrategyConsumer(RoutingStrategy strategy) { this.strategy = strategy; }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomEngine {
        static final RoutingEngine INSTANCE = new RoutingEngine((req, providers) -> providers.get(0), List.of());
        @Bean RoutingEngine routingEngine() { return INSTANCE; }
    }
}
