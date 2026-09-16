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

import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The fallback NOOP {@link ObservationRegistry} must not displace Spring Boot's own registry, or
 * OTLP tracing silently stops. These tests exercise the auto-configuration wiring itself; the
 * tracing tests inject a {@code TestObservationRegistry} directly and bypass it.
 */
class ObservabilityAutoConfigurationTest {

    @Test
    void realRegistryWinsWhenSpringBootObservationAutoConfigIsPresent() {
        // Both auto-configurations on the classpath, as in a real application. The NOOP must back
        // off (@ConditionalOnMissingBean, ordered after Spring Boot's) so the real registry is used.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ObservationAutoConfiguration.class,    // Spring Boot's real registry
                        ObservabilityAutoConfiguration.class)) // our fallback
                .run(context -> {
                    assertThat(context).hasSingleBean(ObservationRegistry.class);
                    assertThat(context.getBean(ObservationRegistry.class))
                            .as("real ObservationRegistry must win, not the NOOP fallback")
                            .isNotSameAs(ObservationRegistry.NOOP);
                });
    }

    @Test
    void noopFallbackRegistersWhenNoOtherRegistryIsPresent() {
        // Only our auto-configuration present: the NOOP fills in.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(ObservationRegistry.class);
                    assertThat(context.getBean(ObservationRegistry.class))
                            .isSameAs(ObservationRegistry.NOOP);
                });
    }

    @Test
    void noopBacksOffToAnyPreExistingRegistry() {
        // An explicitly provided registry is never replaced by the NOOP.
        new ApplicationContextRunner()
                .withBean(ObservationRegistry.class, ObservationRegistry::create)
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(ObservationRegistry.class);
                    assertThat(context.getBean(ObservationRegistry.class))
                            .isNotSameAs(ObservationRegistry.NOOP);
                });
    }
}