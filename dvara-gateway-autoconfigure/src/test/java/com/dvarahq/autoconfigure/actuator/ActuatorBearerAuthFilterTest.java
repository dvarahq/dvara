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

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ActuatorBearerAuthFilter} guards the actuator endpoints of a deployment with no Spring
 * Security on the classpath; where a {@code SecurityFilterChain} is present the auto-configuration
 * backs off. These tests assert the refusals rather than the happy path, because the metrics
 * endpoint reports every workspace's spend.
 */
class ActuatorBearerAuthFilterTest {

    private static final String KEY = "operator-key";
    private static final String METRICS_KEY = "scrape-key";

    private final ActuatorBearerAuthFilter filter = new ActuatorBearerAuthFilter(KEY, METRICS_KEY);

    private MockHttpServletRequest get(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRequestURI(path);
        return request;
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    // --- what must stay open ---------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/actuator/health/liveness",
            "/actuator/health/readiness",
            "/actuator/info"})
    void theProbesAndBuildInfoNeedNoCredential(String path) throws Exception {
        // Kubernetes probes cannot present a credential, so locking these out would be an outage.
        assertThat(run(get(path)).getStatus()).isEqualTo(200);
    }

    @Test
    void nonActuatorPathsAreNotThisFiltersBusiness() throws Exception {
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = get("/v1/chat/completions");
        assertThat(filter.shouldNotFilter(request))
                .as("/v1/* is ApiKeyAuthFilter's surface; this filter must not touch it")
                .isTrue();
        verify(chain, never()).doFilter(request, new MockHttpServletResponse());
    }

    // --- what must be closed ----------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/gateway-status", "/actuator/prometheus", "/actuator/health/db"})
    void anAuthenticatedPathWithNoTokenIs401(String path) throws Exception {
        MockHttpServletResponse response = run(get(path));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }

    @Test
    void aHealthSubIndicatorIsNotCoveredByTheProbeCarveOut() throws Exception {
        // Only the two probe groups are anonymous. /actuator/health/db would expose datasource
        // internals.
        assertThat(run(get("/actuator/health/db")).getStatus()).isEqualTo(401);
    }

    @Test
    void aWrongTokenIs401() throws Exception {
        MockHttpServletRequest request = get("/actuator/gateway-status");
        request.addHeader("Authorization", "Bearer not-the-key");

        assertThat(run(request).getStatus()).isEqualTo(401);
    }

    @Test
    void aNonBearerSchemeIs401() throws Exception {
        MockHttpServletRequest request = get("/actuator/gateway-status");
        request.addHeader("Authorization", "Basic " + KEY);

        assertThat(run(request).getStatus()).isEqualTo(401);
    }

    // --- the two keys are genuinely separate -------------------------------------------------

    @Test
    void theOperatorKeyOpensGatewayStatus() throws Exception {
        MockHttpServletRequest request = get("/actuator/gateway-status");
        request.addHeader("Authorization", "Bearer " + KEY);

        assertThat(run(request).getStatus()).isEqualTo(200);
    }

    @Test
    void theMetricsKeyOpensPrometheus() throws Exception {
        MockHttpServletRequest request = get("/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + METRICS_KEY);

        assertThat(run(request).getStatus()).isEqualTo(200);
    }

    @Test
    void theMetricsKeyDoesNotOpenGatewayStatus() throws Exception {
        // The whole point of two keys: a scrape credential is handed to every Prometheus in the
        // estate, and must not also read the route table and provider list.
        MockHttpServletRequest request = get("/actuator/gateway-status");
        request.addHeader("Authorization", "Bearer " + METRICS_KEY);

        assertThat(run(request).getStatus()).isEqualTo(401);
    }

    @Test
    void theOperatorKeyDoesNotOpenPrometheus() throws Exception {
        MockHttpServletRequest request = get("/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + KEY);

        assertThat(run(request).getStatus()).isEqualTo(401);
    }

    // --- unset keys refuse, they do not admit -------------------------------------------------

    @Test
    void anUnconfiguredKeyRefusesRatherThanAdmits() throws Exception {
        // An unset credential meaning "open" would make the filter a no-op on exactly the
        // installs that never configured it.
        ActuatorBearerAuthFilter unconfigured = new ActuatorBearerAuthFilter("", "");
        MockHttpServletResponse response = new MockHttpServletResponse();
        unconfigured.doFilter(get("/actuator/prometheus"), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void anUnconfiguredKeyRefusesEvenWhenATokenIsPresented() throws Exception {
        ActuatorBearerAuthFilter unconfigured = new ActuatorBearerAuthFilter("", "");
        MockHttpServletRequest request = get("/actuator/gateway-status");
        request.addHeader("Authorization", "Bearer anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        unconfigured.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus())
                .as("a blank configured key must never match a presented one")
                .isEqualTo(401);
    }

    @Test
    void anUnsetMetricsKeyDoesNotFallBackToTheOperatorKey() throws Exception {
        // Falling back would silently widen the operator key to every Prometheus scraper.
        ActuatorBearerAuthFilter partial = new ActuatorBearerAuthFilter(KEY, "");
        MockHttpServletRequest request = get("/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + KEY);
        MockHttpServletResponse response = new MockHttpServletResponse();
        partial.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    // --- where the endpoints are served ------------------------------------------------------

    @Test
    void aContextPathDoesNotLetAnActuatorPathThrough() throws Exception {
        MockHttpServletRequest request = get("/api/actuator/prometheus");
        request.setContextPath("/api");

        assertThat(run(request).getStatus()).isEqualTo(401);
    }

    @Test
    void theProbesStayOpenUnderAContextPath() throws Exception {
        MockHttpServletRequest request = get("/api/actuator/health/readiness");
        request.setContextPath("/api");

        assertThat(run(request).getStatus()).isEqualTo(200);
    }

    @Test
    void theBareDiscoveryPageNeedsAToken() throws Exception {
        assertThat(run(get("/actuator")).getStatus()).isEqualTo(401);
    }

    @Test
    void anEncodedOrParameterisedPathIsStillTheEndpoint() throws Exception {
        assertThat(run(get("/actuator/%70rometheus")).getStatus()).isEqualTo(401);
        assertThat(run(get("/actuator/prometheus;x=1")).getStatus()).isEqualTo(401);
    }

    @Test
    void aMovedBasePathIsProtectedAndKeepsItsTwoKeys() throws Exception {
        ActuatorBearerAuthFilter moved = new ActuatorBearerAuthFilter(KEY, METRICS_KEY, "/manage/");

        MockHttpServletRequest noToken = get("/manage/prometheus");
        MockHttpServletResponse refused = new MockHttpServletResponse();
        moved.doFilter(noToken, refused, new MockFilterChain());
        assertThat(refused.getStatus()).isEqualTo(401);

        MockHttpServletRequest scrape = get("/manage/prometheus");
        scrape.addHeader("Authorization", "Bearer " + METRICS_KEY);
        MockHttpServletResponse served = new MockHttpServletResponse();
        moved.doFilter(scrape, served, new MockFilterChain());
        assertThat(served.getStatus()).isEqualTo(200);

        MockHttpServletRequest probe = get("/manage/health/liveness");
        MockHttpServletResponse probed = new MockHttpServletResponse();
        moved.doFilter(probe, probed, new MockFilterChain());
        assertThat(probed.getStatus()).isEqualTo(200);
    }

    @Test
    void endpointsServedAtTheRootAreRefusedAtConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ActuatorBearerAuthFilter(KEY, METRICS_KEY, "/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-path");
    }
}
