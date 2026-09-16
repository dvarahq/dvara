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
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers a no-op {@link ObservationRegistry} only when no other one is present, which is the
 * case when micrometer-tracing and the OTel bridge are absent from the classpath.
 *
 * <p>When those dependencies are present, Spring Boot's {@code ObservationAutoConfiguration}
 * supplies a real registry. This class is ordered after it and the bean is
 * {@link ConditionalOnMissingBean}, so the real registry is already in the context when this
 * evaluates and the no-op backs off. An unconditional no-op here would suppress Boot's registry,
 * which is itself conditional, and silently disable tracing.
 */
@AutoConfiguration(afterName = "org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration")
public class ObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObservationRegistry.class)
    public ObservationRegistry noOpObservationRegistry() {
        return ObservationRegistry.NOOP;
    }
}