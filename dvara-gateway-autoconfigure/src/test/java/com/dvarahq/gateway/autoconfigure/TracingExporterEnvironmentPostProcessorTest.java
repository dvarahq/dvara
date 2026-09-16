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
package com.dvarahq.gateway.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OTLP trace export is opt-in by endpoint.
 */
class TracingExporterEnvironmentPostProcessorTest {

    private final TracingExporterEnvironmentPostProcessor processor =
            new TracingExporterEnvironmentPostProcessor();

    @Test
    void withNoEndpointConfigured_thePropertyStaysABSENT() {
        ConfigurableEnvironment env = new MockEnvironment();

        processor.postProcessEnvironment(env, null);

        // Absent, not empty. Boot gates the exporter on @ConditionalOnProperty(endpoint): an absent
        // value registers no exporter, while an empty value fails context startup. So
        // containsProperty is the assertion that matters; "is blank" would pass against the
        // variant that refuses to boot.
        assertThat(env.containsProperty(
                TracingExporterEnvironmentPostProcessor.BOOT_ENDPOINT_PROPERTY)).isFalse();
    }

    @Test
    void theOtelEnvironmentVariableStillTurnsExportOn() {
        // The variable the docs and every OpenTelemetry user reach for. Boot's own relaxed binding
        // would want MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT, so without this
        // mapping an operator who configured a collector would be silently ignored.
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("env",
                Map.of(TracingExporterEnvironmentPostProcessor.OTEL_ENDPOINT_ENV, "http://collector:4318")));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(TracingExporterEnvironmentPostProcessor.BOOT_ENDPOINT_PROPERTY))
                .isEqualTo("http://collector:4318");
    }

    @Test
    void aBlankVariableIsTreatedAsUnsetRatherThanAsAnEmptyEndpoint() {
        // An operator who exports OTEL_EXPORTER_OTLP_ENDPOINT="" has configured nothing. Passing
        // that through would set the property to empty and take the whole app down at startup.
        MockEnvironment env = new MockEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("env",
                Map.of(TracingExporterEnvironmentPostProcessor.OTEL_ENDPOINT_ENV, "   ")));

        processor.postProcessEnvironment(env, null);

        assertThat(env.containsProperty(
                TracingExporterEnvironmentPostProcessor.BOOT_ENDPOINT_PROPERTY)).isFalse();
    }

    @Test
    void anExplicitBootPropertyIsLeftAlone() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty(TracingExporterEnvironmentPostProcessor.BOOT_ENDPOINT_PROPERTY, "http://chosen:4318");
        env.getPropertySources().addLast(new MapPropertySource("env",
                Map.of(TracingExporterEnvironmentPostProcessor.OTEL_ENDPOINT_ENV, "http://ignored:4318")));

        processor.postProcessEnvironment(env, null);

        assertThat(env.getProperty(TracingExporterEnvironmentPostProcessor.BOOT_ENDPOINT_PROPERTY))
                .isEqualTo("http://chosen:4318");
    }
}