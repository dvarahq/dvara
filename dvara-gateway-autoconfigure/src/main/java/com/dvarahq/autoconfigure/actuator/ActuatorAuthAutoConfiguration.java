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
package com.dvarahq.autoconfigure.actuator;

import jakarta.servlet.Filter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Actuator authentication for a build with no Spring Security on the classpath.
 *
 * <p>{@code @ConditionalOnMissingClass} on Spring Security's {@code SecurityFilterChain}: where
 * Spring Security is present, the application's security chain owns the actuator surface and this
 * filter must not exist, since two things authenticating one request is how a rule ends up applied
 * twice or once and then undone. The condition names the absence of Spring Security because that
 * is the fact this filter exists on: there is no security chain to do the job.
 *
 * <p>The gateway server in this repository has no Spring Security, so this filter is what guards
 * its actuator endpoints.
 *
 * <p>Registered at {@code HIGHEST_PRECEDENCE}, ahead of the trace-id, rate-limit and access-log
 * filters: an unauthenticated actuator probe is refused before it gets a trace id and a log line,
 * and before it can consume anyone's rate-limit budget.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnMissingClass("org.springframework.security.web.SecurityFilterChain")
public class ActuatorAuthAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ActuatorAuthAutoConfiguration.class);

    @Bean
    public FilterRegistrationBean<Filter> actuatorBearerAuthFilterRegistration(
            @Value("${dvara.actuator.api-key:}") String apiKey,
            @Value("${dvara.actuator.metrics-api-key:}") String metricsApiKey,
            @Value("${management.endpoints.web.base-path:" + ActuatorBearerAuthFilter.DEFAULT_BASE_PATH + "}") String basePath,
            @Value("${management.server.port:}") String managementPort,
            @Value("${server.port:8080}") String serverPort) {

        refuseASeparateManagementPort(managementPort, serverPort);
        warnAboutUnsetKeys(apiKey, metricsApiKey);

        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new ActuatorBearerAuthFilter(apiKey, metricsApiKey, basePath));
        // Every path, and the filter decides: a servlet URL pattern cannot follow the base path or
        // strip the context path, and a pattern that missed a path would let it through unchecked.
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("actuatorBearerAuthFilter");
        return registration;
    }

    /**
     * A separate management port starts a child context this registration does not reach, so the
     * endpoints there would be served with no authentication at all. Refusing to start says so; the
     * alternative is finding out from an open metrics port.
     */
    static void refuseASeparateManagementPort(String managementPort, String serverPort) {
        if (managementPort == null || managementPort.isBlank() || "-1".equals(managementPort.trim())
                || managementPort.trim().equals(serverPort == null ? "" : serverPort.trim())) {
            return;
        }
        throw new IllegalStateException("management.server.port=" + managementPort.trim()
                + " serves the actuator endpoints from a separate context, which this build's actuator"
                + " authentication cannot protect. Remove it, set it to the application port, or set -1"
                + " to turn the management endpoints off.");
    }

    /**
     * Say why the endpoints are refusing, at startup, once.
     *
     * <p>Unset keys mean every authenticated actuator path returns 401, which is the correct posture
     * and an opaque one to debug from the outside: without the warning the first symptom is a
     * Prometheus target going down with no explanation anywhere.
     */
    private static void warnAboutUnsetKeys(String apiKey, String metricsApiKey) {
        boolean apiKeyUnset = apiKey == null || apiKey.isBlank();
        boolean metricsUnset = metricsApiKey == null || metricsApiKey.isBlank();

        if (apiKeyUnset && metricsUnset) {
            log.warn("The actuator surface is CLOSED: neither dvara.actuator.api-key"
                    + " (DVARA_ACTUATOR_API_KEY) nor dvara.actuator.metrics-api-key"
                    + " (DVARA_ACTUATOR_METRICS_API_KEY) is set, so every actuator path except the"
                    + " health probes and /actuator/info will answer 401. Generate them with"
                    + " `openssl rand -base64 32` — and use two different values, so a leaked scrape"
                    + " credential does not also unlock /actuator/gateway-status.");
            return;
        }
        if (apiKeyUnset) {
            log.warn("dvara.actuator.api-key is not set — /actuator/gateway-status and any other"
                    + " authenticated actuator path will answer 401. /actuator/prometheus is"
                    + " configured and unaffected.");
        }
        if (metricsUnset) {
            log.warn("dvara.actuator.metrics-api-key is not set — /actuator/prometheus will answer"
                    + " 401 on every scrape.");
        }
        if (!apiKeyUnset && !metricsUnset && apiKey.equals(metricsApiKey)) {
            log.warn("dvara.actuator.api-key and dvara.actuator.metrics-api-key are the SAME value."
                    + " They are separate so that the credential handed to every Prometheus scraper"
                    + " does not also read /actuator/gateway-status; sharing one gives that away.");
        }
    }
}