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

import com.dvarahq.core.ratelimit.EffectiveRateLimit;
import com.dvarahq.core.ratelimit.WorkspaceRateLimitResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which {@link WorkspaceRateLimitResolver} a context ends up with.
 *
 * <p>With nothing else present, the default resolver is used. When an application or another
 * module registers a resolver of its own, the default one must stand down entirely rather than
 * register alongside it: a resolver that is constructed and never consulted looks exactly like one
 * that is consulted and agrees.
 */
class WorkspaceRateLimitResolverSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WorkspaceRateLimitResolverAutoConfiguration.class));

    @Test
    @DisplayName("with nothing else, the default resolver is the resolver")
    void theDefaultResolverIsUsed() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(WorkspaceRateLimitResolver.class);
            assertThat(context.getBeanNamesForType(WorkspaceRateLimitResolver.class))
                    .containsExactly("workspaceRateLimitResolver");
        });
    }

    @Test
    @DisplayName("an application's own resolver is the ONLY resolver, with no @Primary of its own")
    void anApplicationResolverWins() {
        runner.withUserConfiguration(AnApplicationsOwnResolver.class).run(context -> {
            assertThat(context)
                    .describedAs("the default resolver must stand down, not merely lose the selection")
                    .hasSingleBean(WorkspaceRateLimitResolver.class);
            assertThat(context.getBeanNamesForType(WorkspaceRateLimitResolver.class))
                    .containsExactly("theApplicationsResolver");
            assertThat(context.getBean(WorkspaceRateLimitResolver.class).resolve("w1"))
                    .describedAs("and it is the one actually consulted")
                    .isSameAs(APPLICATION_ANSWER);
        });
    }

    @Test
    @DisplayName("a @Primary resolver from another module stands the default one down")
    void aPrimaryResolverFromAnotherModuleWins() {
        runner.withUserConfiguration(APrimaryResolverFromAnotherModule.class).run(context -> {
            assertThat(context).hasSingleBean(WorkspaceRateLimitResolver.class);
            assertThat(context.getBeanNamesForType(WorkspaceRateLimitResolver.class))
                    .containsExactly("anotherModulesResolver");
        });
    }

    @Test
    @DisplayName("if both ever registered, @Primary still decides")
    void theOrderingFailureIsSafe() {
        new ApplicationContextRunner()
                .withUserConfiguration(APrimaryResolverFromAnotherModule.class, TheDefaultResolverForcedIn.class)
                .run(context -> {
                    assertThat(context.getBeanNamesForType(WorkspaceRateLimitResolver.class))
                            .hasSize(2);
                    assertThat(context.getBean(WorkspaceRateLimitResolver.class))
                            .describedAs("no ambiguity: the @Primary resolver is chosen")
                            .isSameAs(context.getBean("anotherModulesResolver"));
                });
    }

    private static final EffectiveRateLimit APPLICATION_ANSWER = new EffectiveRateLimit(7, 7);

    @Configuration(proxyBeanMethods = false)
    static class AnApplicationsOwnResolver {
        @Bean
        WorkspaceRateLimitResolver theApplicationsResolver() {
            return workspaceId -> APPLICATION_ANSWER;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class APrimaryResolverFromAnotherModule {
        @Bean
        @Primary
        WorkspaceRateLimitResolver anotherModulesResolver() {
            return workspaceId -> EffectiveRateLimit.NONE;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TheDefaultResolverForcedIn {
        @Bean
        WorkspaceRateLimitResolver workspaceRateLimitResolver() {
            return workspaceId -> EffectiveRateLimit.NONE;
        }
    }
}
