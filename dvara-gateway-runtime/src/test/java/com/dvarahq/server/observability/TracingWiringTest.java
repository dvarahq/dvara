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
package com.dvarahq.server.observability;

import com.dvarahq.autoconfigure.ObservabilityAutoConfiguration;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tracing chain is wired at runtime: a real {@link Tracer}, an OTLP {@link SpanExporter} and an
 * {@link ObservationRegistry} that is not the no-op one. The other tracing tests inject a test
 * registry and bypass this chain, so this is the one that proves the real beans exist.
 */
class TracingWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues(
                    "management.tracing.sampling.probability=1.0",
                    // Base URL (the OTel SDK appends /v1/traces). Presence of this property is what
                    // creates the OtlpTracingConnectionDetails → OTLP span exporter.
                    "management.opentelemetry.tracing.export.otlp.endpoint=http://localhost:4318")
            .withConfiguration(AutoConfigurations.of(
                    ObservationAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    OpenTelemetrySdkAutoConfiguration.class,
                    OpenTelemetryTracingAutoConfiguration.class,
                    OtlpTracingAutoConfiguration.class,
                    ObservabilityAutoConfiguration.class));

    @Test
    void tracingChainIsWired() {
        runner.run(context -> {
            // A real micrometer Tracer proves the OTel bridge autoconfig is active.
            assertThat(context).hasSingleBean(Tracer.class);
            // An OTLP SpanExporter proves the exporter chain is created.
            assertThat(context.getBeansOfType(SpanExporter.class))
                    .as("an OTLP SpanExporter must be created when the OTLP endpoint is configured")
                    .isNotEmpty();
            // And the registry is the real one, not the NOOP fallback.
            assertThat(context.getBean(ObservationRegistry.class))
                    .as("real ObservationRegistry, not the NOOP fallback")
                    .isNotSameAs(ObservationRegistry.NOOP);
        });
    }
}