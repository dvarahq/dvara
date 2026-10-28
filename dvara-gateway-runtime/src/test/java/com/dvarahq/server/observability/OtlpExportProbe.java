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
import io.micrometer.tracing.Tracer;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * A small application that starts the tracing chain the way a real start does, sends one span and
 * stops. {@link TracingWiringTest} runs it in its own JVM, because Boot's mapping of the
 * OpenTelemetry variables reads the real process environment and nothing else.
 */
public final class OtlpExportProbe {

    private OtlpExportProbe() {
    }

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(ProbeConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = application.run(args)) {
            System.out.println("probe endpoint="
                    + context.getEnvironment().getProperty("management.opentelemetry.tracing.export.otlp.endpoint"));
            context.getBean(Tracer.class).nextSpan().name("probe").start().end();
            context.getBean(SdkTracerProvider.class).forceFlush().join(10, TimeUnit.SECONDS);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
            ObservationAutoConfiguration.class,
            MicrometerTracingAutoConfiguration.class,
            OpenTelemetrySdkAutoConfiguration.class,
            OpenTelemetryTracingAutoConfiguration.class,
            OtlpTracingAutoConfiguration.class,
            ObservabilityAutoConfiguration.class})
    static class ProbeConfiguration {
    }
}
