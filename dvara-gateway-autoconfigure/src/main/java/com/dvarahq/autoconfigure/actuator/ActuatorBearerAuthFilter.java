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
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

/**
 * Bearer authentication for {@code /actuator/**} in a build with no Spring Security.
 *
 * <p>No module in this repository depends on Spring Security, so without this filter the actuator
 * surface would be served to anyone who could reach the port: {@code /actuator/prometheus} carries
 * every workspace id, model and provider with their token and spend counters, and
 * {@code /actuator/gateway-status} lists providers, routes, rate limits, region, bundle and
 * deny-list state.
 *
 * <p>It is a plain servlet filter rather than a security chain, the same choice
 * {@code ApiKeyAuthFilter} makes: it keeps {@code spring-boot-starter-security} out of the
 * dependencies of everyone consuming the published artifact.
 *
 * <p>The rules:
 * <ul>
 *   <li>The k8s probes and {@code /actuator/info}, which carries only a version, are open.
 *       {@code /actuator/health/<indicator>} is not in that set; only the two probe groups are.</li>
 *   <li>{@code /actuator/prometheus} takes {@code dvara.actuator.metrics-api-key}; everything else
 *       under {@code /actuator/} takes {@code dvara.actuator.api-key}. Two keys, so a leaked scrape
 *       credential does not also unlock gateway-status.</li>
 *   <li>An unset key refuses rather than admits. A missing credential must not be a way in, and
 *       a 401 on a scrape is loud where an open metrics endpoint is silent. The auto-configuration
 *       warns at startup so the cause is not a mystery.</li>
 * </ul>
 *
 * <p>Token comparison is {@link MessageDigest#isEqual}, which is constant-time.
 */
public class ActuatorBearerAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ActuatorBearerAuthFilter.class);

    /** Where Spring Boot serves the endpoints unless {@code management.endpoints.web.base-path} moves them. */
    public static final String DEFAULT_BASE_PATH = "/actuator";

    private static final String BEARER = "Bearer ";

    /**
     * Paths are compared within the application, as a security chain's request matchers compare
     * them: without the servlet context path, decoded, and with {@code ;} parameters removed. The
     * raw request URI includes the context path, so comparing against it would let every actuator
     * path skip this filter when {@code server.servlet.context-path} is set.
     */
    private static final UrlPathHelper PATHS = new UrlPathHelper();

    private final String apiKey;
    private final String metricsApiKey;
    private final String basePath;
    private final String prometheusPath;

    /**
     * Open without a credential: the k8s probe targets and the build-info endpoint. They carry no
     * workspace-scoped data: {@code /actuator/health} is configured {@code show-details:
     * when-authorized}, and with no Spring Security present nobody is ever authorized, so it renders
     * status only.
     */
    private final Set<String> anonymousPaths;

    public ActuatorBearerAuthFilter(String apiKey, String metricsApiKey) {
        this(apiKey, metricsApiKey, DEFAULT_BASE_PATH);
    }

    /**
     * @param basePath {@code management.endpoints.web.base-path}; must name a path below the root,
     *                 because endpoints served at the root cannot be told apart from the application's
     *                 own paths
     */
    public ActuatorBearerAuthFilter(String apiKey, String metricsApiKey, String basePath) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.metricsApiKey = metricsApiKey == null ? "" : metricsApiKey;
        this.basePath = normaliseBasePath(basePath);
        this.prometheusPath = this.basePath + "/prometheus";
        this.anonymousPaths = Set.of(
                this.basePath + "/health",
                this.basePath + "/health/liveness",
                this.basePath + "/health/readiness",
                this.basePath + "/info");
    }

    /** {@code /manage/} and {@code manage} both mean {@code /manage}; the root is refused. */
    static String normaliseBasePath(String basePath) {
        String base = basePath == null ? DEFAULT_BASE_PATH : basePath.trim();
        if (!base.startsWith("/")) {
            base = "/" + base;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            throw new IllegalStateException("management.endpoints.web.base-path is the root, and this"
                    + " filter cannot tell actuator endpoints served there from the application's own"
                    + " paths. Serve them below a path such as /actuator.");
        }
        return base;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = PATHS.getPathWithinApplication(request);
        // The bare base path is covered too: it is the discovery page listing every exposed endpoint.
        return path == null || !(path.equals(basePath) || path.startsWith(basePath + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = PATHS.getPathWithinApplication(request);
        if (anonymousPaths.contains(path)) {
            chain.doFilter(request, response);
            return;
        }

        String expected = prometheusPath.equals(path) ? metricsApiKey : apiKey;
        if (expected.isBlank()) {
            // Fail closed. An unconfigured key is the operator not having decided yet, and the
            // safe reading of that is "no", not "everyone".
            reject(response, "actuator authentication is not configured");
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER)) {
            reject(response, "a Bearer token is required");
            return;
        }

        String presented = header.substring(BEARER.length()).trim();
        if (presented.isBlank() || !constantTimeEquals(presented, expected)) {
            // Debug, not warn: actuator paths are scanned constantly, and a warn per probe is how
            // a log stops being read.
            log.debug("Actuator bearer mismatch on {}", path);
            reject(response, "invalid token");
            return;
        }

        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, String reason) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"error\":{\"message\":\"" + reason + "\",\"type\":\"actuator_auth_error\"}}");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}