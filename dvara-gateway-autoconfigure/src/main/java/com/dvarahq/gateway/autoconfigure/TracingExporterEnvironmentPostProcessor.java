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

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Makes OTLP trace export opt-in by endpoint.
 *
 * <p>Spring Boot registers the OTLP exporter only when
 * {@code management.opentelemetry.tracing.export.otlp.endpoint} is present, and an empty value
 * fails context startup rather than disabling the exporter. YAML cannot express "set this key only
 * when that variable is set", so this post-processor does it. Without this, an install with no
 * collector logs a failed export at ERROR for every span batch.
 *
 * <p>The variables follow the OpenTelemetry convention:
 * <ul>
 *   <li>{@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT} is the full traces URL and is used as written.</li>
 *   <li>{@code OTEL_EXPORTER_OTLP_ENDPOINT} is a base URL, such as {@code http://collector:4318}.
 *       The traces path {@code /v1/traces} is added to it, unless the value already ends with it.</li>
 *   <li>If neither is set, the Boot property stays absent and no exporter is registered.</li>
 * </ul>
 *
 * <p>Boot's OTLP HTTP exporter posts to the endpoint exactly as given and adds no path, so the
 * path has to be added here.
 *
 * <p>Precedence: an explicit {@code management.opentelemetry.tracing.export.otlp.endpoint} wins
 * over both variables and is used as written. The traces variable wins over the generic one.
 *
 * <p>{@code TRACING_SAMPLING_PROBABILITY} is left alone: with no exporter registered the sampler
 * costs nothing.
 */
public class TracingExporterEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String BOOT_ENDPOINT_PROPERTY = "management.opentelemetry.tracing.export.otlp.endpoint";
    static final String OTEL_ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_ENDPOINT";
    static final String OTEL_TRACES_ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT";
    static final String TRACES_PATH = "/v1/traces";
    private static final String PROPERTY_SOURCE_NAME = "dvaraTracingExporter";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // An explicit Boot property wins and is left alone.
        if (hasText(environment.getProperty(BOOT_ENDPOINT_PROPERTY))) {
            return;
        }

        String endpoint = resolveEndpoint(
                environment.getProperty(OTEL_TRACES_ENDPOINT_ENV), environment.getProperty(OTEL_ENDPOINT_ENV));
        if (endpoint == null) {
            // Nothing added: the property stays absent, Boot's condition does not match, and no
            // exporter is registered.
            return;
        }

        // addLast, so anything more specific still wins.
        environment.getPropertySources().addLast(new MapPropertySource(
                PROPERTY_SOURCE_NAME, Map.of(BOOT_ENDPOINT_PROPERTY, endpoint)));
    }

    /**
     * The traces URL for the two OpenTelemetry variables, or {@code null} when neither is set.
     */
    static String resolveEndpoint(String tracesEndpoint, String baseEndpoint) {
        if (hasText(tracesEndpoint)) {
            return tracesEndpoint.trim();
        }
        if (!hasText(baseEndpoint)) {
            return null;
        }
        String base = baseEndpoint.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith(TRACES_PATH)) {
            return base;
        }
        return base + TRACES_PATH;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}