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
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A deployment without Spring Security gets actuator authentication from this filter.
 *
 * <p>This module has no Spring Security on its test classpath, so the runner exercises the branch
 * the gateway server takes. The other branch, where a {@code SecurityFilterChain} owns the surface
 * and the filter stands down, can only be asserted from a module that has Spring Security.
 */
class ActuatorAuthAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ActuatorAuthAutoConfiguration.class));

    @Test
    void withNoSpringSecurityTheFilterIsRegistered() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter())
                    .as("without Spring Security the filter must protect /actuator itself — nothing else will")
                    .isInstanceOf(ActuatorBearerAuthFilter.class);
            // Every path: the filter decides, so a context path or a moved base path cannot put an
            // endpoint outside the pattern.
            assertThat(registration.getUrlPatterns()).containsExactly("/*");
        });
    }

    @Test
    void itRunsAheadOfTheOtherFilters() {
        // Ordered first so an unauthenticated probe is refused before it is given a trace id, logged,
        // or allowed to consume a rate-limit budget.
        runner.run(context -> assertThat(context.getBean(FilterRegistrationBean.class).getOrder())
                .isEqualTo(org.springframework.core.Ordered.HIGHEST_PRECEDENCE));
    }

    @Test
    void itRegistersEvenWithNoKeysConfigured() {
        // Registering only when keys are present would make a forgotten key mean "open". It
        // registers and refuses; the WARN says why.
        runner.run(context -> assertThat(context).hasSingleBean(FilterRegistrationBean.class));
    }

    @Test
    void theConfiguredKeysReachTheFilter() throws Exception {
        runner.withPropertyValues(
                        "dvara.actuator.api-key=op-key",
                        "dvara.actuator.metrics-api-key=scrape-key")
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    Filter filter = context.getBean(FilterRegistrationBean.class).getFilter();

                    var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/actuator/prometheus");
                    request.setRequestURI("/actuator/prometheus");
                    request.addHeader("Authorization", "Bearer scrape-key");
                    var response = new org.springframework.mock.web.MockHttpServletResponse();
                    filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());

                    assertThat(response.getStatus())
                            .as("a property that does not reach the filter is a key nobody can use")
                            .isEqualTo(200);
                });
    }

    @Test
    void aMovedBasePathReachesTheFilter() {
        runner.withPropertyValues("management.endpoints.web.base-path=/manage", "dvara.actuator.api-key=op-key")
                .run(context -> {
                    Filter filter = context.getBean(FilterRegistrationBean.class).getFilter();
                    var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/manage/gateway-status");
                    var response = new org.springframework.mock.web.MockHttpServletResponse();
                    filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
                    assertThat(response.getStatus()).isEqualTo(401);
                });
    }

    @Test
    void aSeparateManagementPortRefusesToStart() {
        runner.withPropertyValues("management.server.port=9090")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("management.server.port=9090"));
    }

    @Test
    void aManagementPortThatIsOffOrTheApplicationPortIsAccepted() {
        runner.withPropertyValues("management.server.port=-1")
                .run(context -> assertThat(context).hasSingleBean(FilterRegistrationBean.class));
        runner.withPropertyValues("server.port=8080", "management.server.port=8080")
                .run(context -> assertThat(context).hasSingleBean(FilterRegistrationBean.class));
    }
}
