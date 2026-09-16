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
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.resilience.FallbackResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Switching resilience off performs no fallback.
 *
 * <p>{@code ResilienceAutoConfiguration} is conditional on
 * {@code dvara.llm-gateway.resilience.enabled} and owns the {@code @Primary} resolver. With it
 * switched off, the default resolver in {@code GatewayAutoConfiguration} is the only candidate, and
 * that one must also return nothing. Each resolver's own logic is simple; what matters is which one
 * the context holds under each property, so the postures are booted rather than unit-tested.
 */
class ResilienceDisabledPerformsNoFallbackTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    GatewayAutoConfiguration.class, ResilienceAutoConfiguration.class));

    /** Two providers that both claim the request, so any resolver that can offer a candidate will. */
    private static List<LlmProvider> twoCandidates() {
        LlmProvider failed = Mockito.mock(LlmProvider.class);
        LlmProvider other = Mockito.mock(LlmProvider.class);
        Mockito.when(failed.name()).thenReturn("failed");
        Mockito.when(other.name()).thenReturn("other");
        Mockito.when(other.supports(Mockito.any())).thenReturn(true);
        return List.of(failed, other);
    }

    private static List<LlmProvider> resolve(FallbackResolver resolver) {
        List<LlmProvider> providers = twoCandidates();
        return resolver.resolve(ChatRequest.builder().model("gpt-4o").build(), providers.get(0), providers);
    }

    @Test
    @DisplayName("resilience disabled offers no fallback candidate")
    void resilienceDisabled() {
        runner.withPropertyValues("dvara.llm-gateway.resilience.enabled=false")
                .run(context -> assertThat(resolve(context.getBean(FallbackResolver.class)))
                        .describedAs("resilience is off, so there is no failover")
                        .isEmpty());
    }

    @Test
    @DisplayName("resilience enabled with fallback disabled offers no fallback candidate")
    void fallbackDisabled() {
        runner.withPropertyValues(
                        "dvara.llm-gateway.resilience.enabled=true",
                        "dvara.llm-gateway.resilience.fallback.enabled=false")
                .run(context -> assertThat(resolve(context.getBean(FallbackResolver.class)))
                        .describedAs("fallback is off, so there is no failover")
                        .isEmpty());
    }

    @Test
    @DisplayName("the two disabled postures agree")
    void bothDisabledPosturesAgree() {
        runner.withPropertyValues("dvara.llm-gateway.resilience.enabled=false")
                .run(off -> runner.withPropertyValues(
                                "dvara.llm-gateway.resilience.enabled=true",
                                "dvara.llm-gateway.resilience.fallback.enabled=false")
                        .run(fallbackOff -> assertThat(resolve(off.getBean(FallbackResolver.class)))
                                .describedAs("disabling the feature and disabling its one behaviour "
                                        + "must give the same result")
                                .isEqualTo(resolve(fallbackOff.getBean(FallbackResolver.class)))));
    }

    @Test
    @DisplayName("resilience enabled with fallback enabled still resolves candidates")
    void bothEnabled() {
        runner.withPropertyValues(
                        "dvara.llm-gateway.resilience.enabled=true",
                        "dvara.llm-gateway.resilience.fallback.enabled=true")
                .run(context -> assertThat(context.getBean(FallbackResolver.class))
                        .describedAs("with both switches on, the configurable resolver is in charge")
                        .isInstanceOf(ConfigurableFallbackResolver.class));
    }
}
