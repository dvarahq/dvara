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
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.support.EnvironmentPostProcessorApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Map;

/**
 * Makes OTLP trace export opt-in by endpoint, and sends traces to the right URL.
 *
 * <p>Spring Boot registers the OTLP exporter only when
 * {@code management.opentelemetry.tracing.export.otlp.endpoint} is present, and an empty value
 * fails context startup rather than disabling the exporter. YAML cannot express "set this key only
 * when that variable is set", so this class does it. Without this, an install with no
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
 * <p>Precedence: an explicit {@code management.opentelemetry.tracing.export.otlp.endpoint} that the
 * operator set (a property, YAML, or its own environment variable) wins over both variables and is
 * used as written. The traces variable wins over the generic one.
 *
 * <p><b>Boot maps the same variables too.</b> Boot has its own environment post-processor that reads
 * the OpenTelemetry variables. It always adds {@code /v1/traces} to the generic one, so a full URL
 * becomes {@code .../v1/traces/v1/traces}, and it puts its result in front of every other source,
 * so it even beats a value the operator set. That source is named
 * {@value #BOOT_OTEL_PROPERTY_SOURCE}. This class therefore runs as a listener on
 * {@link ApplicationEnvironmentPreparedEvent}, ordered just after the listener that runs every
 * environment post-processor, so Boot's has always run by then. It ignores Boot's source when it
 * looks for the operator's value, and puts its own answer in front of Boot's. It still implements
 * {@link EnvironmentPostProcessor} so code can call it directly; it is registered only as a listener.
 *
 * <p>{@code TRACING_SAMPLING_PROBABILITY} is left alone: with no exporter registered the sampler
 * costs nothing.
 */
public class TracingExporterEnvironmentPostProcessor
        implements EnvironmentPostProcessor, ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

    static final String BOOT_ENDPOINT_PROPERTY = "management.opentelemetry.tracing.export.otlp.endpoint";
    static final String OTEL_ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_ENDPOINT";
    static final String OTEL_TRACES_ENDPOINT_ENV = "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT";
    static final String TRACES_PATH = "/v1/traces";
    /** The name Boot gives the property source its OpenTelemetry variable mapping writes. */
    static final String BOOT_OTEL_PROPERTY_SOURCE = "openTelemetryEnvironmentVariables";
    static final String PROPERTY_SOURCE_NAME = "dvaraTracingExporter";

    /** Just after the listener that runs the environment post-processors, Boot's among them. */
    static final int ORDER = EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER + 1;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        postProcessEnvironment(event.getEnvironment(), event.getSpringApplication());
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var sources = environment.getPropertySources();
        sources.remove(PROPERTY_SOURCE_NAME);
        boolean bootMapped = sources.contains(BOOT_OTEL_PROPERTY_SOURCE)
                && sources.get(BOOT_OTEL_PROPERTY_SOURCE).containsProperty(BOOT_ENDPOINT_PROPERTY);

        String operatorValue = operatorEndpoint(environment);
        if (operatorValue != null) {
            // The operator's value wins and is used as written. It only needs restating when Boot's
            // mapping sits in front of it.
            if (bootMapped) {
                sources.addFirst(new MapPropertySource(
                        PROPERTY_SOURCE_NAME, Map.of(BOOT_ENDPOINT_PROPERTY, operatorValue)));
            }
            return;
        }

        String endpoint = resolveEndpoint(
                environment.getProperty(OTEL_TRACES_ENDPOINT_ENV), environment.getProperty(OTEL_ENDPOINT_ENV));
        if (endpoint == null) {
            // Nothing added: the property stays absent, Boot's condition does not match, and no
            // exporter is registered.
            return;
        }

        // First, so it beats Boot's own mapping of the same variables. The operator's own value
        // was handled above.
        sources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(BOOT_ENDPOINT_PROPERTY, endpoint)));
    }

    /**
     * The endpoint the operator set, or {@code null}. Boot's mapping of the OpenTelemetry variables
     * and Boot's view over all sources are not the operator's, so both are skipped.
     */
    private static String operatorEndpoint(ConfigurableEnvironment environment) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (BOOT_OTEL_PROPERTY_SOURCE.equals(source.getName())
                    || PROPERTY_SOURCE_NAME.equals(source.getName())
                    || ConfigurationPropertySources.isAttachedConfigurationPropertySource(source)) {
                continue;
            }
            Object value = source.getProperty(BOOT_ENDPOINT_PROPERTY);
            if (value != null) {
                String text = environment.resolvePlaceholders(value.toString());
                return hasText(text) ? text.trim() : null;
            }
        }
        return null;
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