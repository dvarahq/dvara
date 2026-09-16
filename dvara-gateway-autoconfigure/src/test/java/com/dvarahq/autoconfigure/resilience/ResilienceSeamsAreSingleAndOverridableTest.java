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

import com.dvarahq.autoconfigure.GatewayAutoConfiguration;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ProviderHealthRegistry} and {@code FallbackResolver} each resolve to exactly one bean
 * whether resilience is on or off, and an application's own implementation replaces the framework's
 * without needing {@code @Primary}. Both registrations are conditional on the interface, and
 * {@code GatewayAutoConfiguration} is ordered after {@code ResilienceAutoConfiguration}. Which bean
 * the context ends up holding is only visible by booting each posture, so these tests go through
 * the container.
 */
class ResilienceSeamsAreSingleAndOverridableTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    GatewayAutoConfiguration.class, ResilienceAutoConfiguration.class));

    @Configuration
    static class ApplicationOwnSeams {

        @Bean
        ProviderHealthRegistry applicationHealthRegistry() {
            return providerName -> ProviderHealthStatus.UNHEALTHY;
        }

        @Bean
        FallbackResolver applicationFallbackResolver() {
            return (request, failedProvider, allProviders) -> List.of();
        }
    }

    @Test
    @DisplayName("resilience enabled leaves exactly one bean per seam")
    void enabledPostureHoldsOneEach() {
        runner.withPropertyValues("dvara.llm-gateway.resilience.enabled=true").run(context -> {
            assertThat(context.getBeanNamesForType(ProviderHealthRegistry.class))
                    .containsExactly("providerHealthRegistry");
            assertThat(context.getBeanNamesForType(FallbackResolver.class))
                    .containsExactly("fallbackResolver");
        });
    }

    @Test
    @DisplayName("resilience disabled leaves exactly one bean per seam")
    void disabledPostureHoldsOneEach() {
        runner.withPropertyValues("dvara.llm-gateway.resilience.enabled=false").run(context -> {
            assertThat(context.getBeanNamesForType(ProviderHealthRegistry.class))
                    .containsExactly("alwaysHealthyRegistry");
            assertThat(context.getBeanNamesForType(FallbackResolver.class))
                    .containsExactly("disabledFallbackResolver");
        });
    }

    @Test
    @DisplayName("an application bean wins in both postures, without @Primary")
    void applicationBeansWin() {
        for (String posture : List.of("true", "false")) {
            runner.withUserConfiguration(ApplicationOwnSeams.class)
                    .withPropertyValues("dvara.llm-gateway.resilience.enabled=" + posture)
                    .run(context -> {
                        assertThat(context.getBeanNamesForType(ProviderHealthRegistry.class))
                                .describedAs("resilience.enabled=%s", posture)
                                .containsExactly("applicationHealthRegistry");
                        assertThat(context.getBeanNamesForType(FallbackResolver.class))
                                .describedAs("resilience.enabled=%s", posture)
                                .containsExactly("applicationFallbackResolver");
                        assertThat(context.getBean(ProviderHealthRegistry.class).getHealth("anything"))
                                .isEqualTo(ProviderHealthStatus.UNHEALTHY);
                    });
        }
    }

    @Test
    @DisplayName("provider wrapping still starts when the application owns the registry")
    void wrappingSurvivesAnApplicationRegistry() {
        runner.withUserConfiguration(ApplicationOwnSeams.class, OneProvider.class)
                .withPropertyValues("dvara.llm-gateway.resilience.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LlmProvider.class))
                            .describedAs("the breaker, retry and timeout are still applied; only the "
                                    + "recording of circuit state has nowhere to go")
                            .isInstanceOf(ResilientLlmProvider.class);
                });
    }

    @Configuration
    static class OneProvider {

        @Bean
        LlmProvider aProvider() {
            LlmProvider provider = org.mockito.Mockito.mock(LlmProvider.class);
            org.mockito.Mockito.when(provider.name()).thenReturn("stub");
            return provider;
        }
    }
}
