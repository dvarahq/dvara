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
package com.dvarahq.server.actuator;

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.autoconfigure.GatewayRegionProperties;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.routing.Route;
import com.dvarahq.core.routing.RouteRepository;
import com.dvarahq.core.status.GatewayStatusSection;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@Endpoint(id = "gateway-status")
public class GatewayStatusEndpoint {

    private final List<LlmProvider> providers;
    private final ProviderHealthRegistry healthRegistry;
    private final RouteRepository routeRepository;
    private final GatewayProperties gatewayProperties;
    private final GatewayRegionProperties regionProperties;
    private final RegionContext regionContext;
    private final ApplicationContext applicationContext;
    private final BuildProperties buildProperties;
    /** Blocks from modules this endpoint does not know: bundle client, revocation list, spool, caches. */
    private final List<GatewayStatusSection> sections;

    public GatewayStatusEndpoint(List<LlmProvider> providers,
                                  ProviderHealthRegistry healthRegistry,
                                  RouteRepository routeRepository,
                                  GatewayProperties gatewayProperties,
                                  GatewayRegionProperties regionProperties,
                                  RegionContext regionContext,
                                  ApplicationContext applicationContext,
                                  @Autowired(required = false) BuildProperties buildProperties,
                                  ObjectProvider<GatewayStatusSection> sections) {
        this.providers = providers;
        this.healthRegistry = healthRegistry;
        this.routeRepository = routeRepository;
        this.gatewayProperties = gatewayProperties;
        this.regionProperties = regionProperties;
        this.regionContext = regionContext;
        this.applicationContext = applicationContext;
        this.buildProperties = buildProperties;
        this.sections = sections.orderedStream().toList();
    }

    /**
     * Builds the status payload: providers with their health, routes, rate limits, region, the
     * blocks contributed by registered {@link GatewayStatusSection}s, and a list of warnings.
     */
    @ReadOperation
    public GatewayStatusInfo status() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> sectionValues = new LinkedHashMap<>();
        Map<String, String> claimedBy = new LinkedHashMap<>();
        for (GatewayStatusSection section : sections) {
            collectSection(section, sectionValues, claimedBy, warnings);
        }

        List<GatewayStatusInfo.ProviderInfo> providerInfos = providers.stream()
                .map(p -> {
                    ProviderHealthStatus health = healthRegistry.getHealth(p.name());
                    if (health == ProviderHealthStatus.UNHEALTHY) {
                        warnings.add("Provider '" + p.name() + "' is UNHEALTHY");
                    } else if (health == ProviderHealthStatus.DEGRADED) {
                        warnings.add("Provider '" + p.name() + "' is DEGRADED");
                    }
                    return toProviderInfo(p, health);
                })
                .toList();

        // A failed route read must not take the endpoint down, and must not be reported as "no
        // routes configured" either: the pod could not read its routes, which is a different thing.
        List<Route> routes;
        boolean routesKnown;
        try {
            routes = routeRepository.findAll();
            routesKnown = true;
        } catch (RuntimeException e) {
            routes = List.of();
            routesKnown = false;
            warnings.add("Route list unavailable (" + describe(e) + ") — this pod could not read its "
                    + "routes, which is not the same as having none");
        }
        List<GatewayStatusInfo.RouteInfo> routeInfos = routes.stream()
                .map(this::toRouteInfo)
                .toList();

        if (routesKnown && routes.isEmpty()) {
            warnings.add("No routes configured (using default model-prefix routing)");
        }

        GatewayProperties.RateLimitConfig rl = gatewayProperties.getRateLimit();

        long uptimeSeconds = (Instant.now().toEpochMilli()
                - applicationContext.getStartupDate()) / 1000;

        GatewayStatusInfo.RegionInfo regionInfo = GatewayStatusInfo.RegionInfo.builder()
                .id(regionContext.currentRegion().orElse(null))
                .name(regionProperties.getName())
                .regionAware(regionContext.isRegionAware())
                .build();

        return GatewayStatusInfo.builder()
                .status("running")
                .mode("full")
                .version(buildProperties != null ? buildProperties.getVersion() : "dev")
                .uptimeSeconds(uptimeSeconds)
                .region(regionInfo)
                .providers(providerInfos)
                .routes(routeInfos)
                .rateLimits(GatewayStatusInfo.RateLimitInfo.builder()
                        .enabled(rl.isEnabled())
                        .perKeyRequestsPerMinute(rl.getPerKey().getRequestsPerMinute())
                        .perKeyTokensPerMinute(rl.getPerKey().getTokensPerMinute())
                        .build())
                .sections(sectionValues)
                .warnings(warnings)
                .build();
    }

    /**
     * The keys {@link GatewayStatusInfo} writes itself, which no section may claim. Derived from
     * the type so a field added to the payload is reserved automatically. The {@code sections}
     * carrier field is excluded by its {@code @JsonIgnore}.
     */
    private static final Set<String> RESERVED_KEYS =
            Arrays.stream(GatewayStatusInfo.class.getDeclaredFields())
                    .filter(f -> !f.isSynthetic())
                    .filter(f -> !Modifier.isStatic(f.getModifiers()))
                    .filter(f -> f.getAnnotation(JsonIgnore.class) == null)
                    .map(Field::getName)
                    .collect(Collectors.toUnmodifiableSet());

    /**
     * Takes one section's block and warnings without letting it spoil anybody else's. A section
     * that throws costs its own block and a warning naming it, not the whole payload. A section
     * reusing a key another section already claimed is dropped with a warning naming both.
     */
    private void collectSection(GatewayStatusSection section,
                                Map<String, Object> sectionValues,
                                Map<String, String> claimedBy,
                                List<String> warnings) {
        String name = section.getClass().getSimpleName();
        String key;
        try {
            key = section.key();
            warnings.addAll(section.warnings());
            Object value = section.value();
            if (value == null) {
                return;
            }
            // The contributed blocks go out through @JsonAnyGetter beside the declared properties,
            // so a section keyed like a field (say `providers`) would emit that key twice in one
            // object, and most parsers keep the last. The check below catches two sections claiming
            // one key; this one catches a section claiming a field's.
            if (RESERVED_KEYS.contains(key)) {
                warnings.add("Status section '" + key + "' from " + name + " collides with a field of "
                        + "the status payload and was dropped");
                return;
            }
            String owner = claimedBy.putIfAbsent(key, name);
            if (owner != null) {
                warnings.add("Status section '" + key + "' was reported by " + owner
                        + " and again by " + name + "; the second was dropped");
                return;
            }
            sectionValues.put(key, value);
        } catch (RuntimeException e) {
            warnings.add("Status section from " + name + " could not be read (" + describe(e) + ")");
        }
    }

    /** An exception as one short clause, since this lands in an operator-facing warning. */
    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + message;
    }

    private GatewayStatusInfo.ProviderInfo toProviderInfo(LlmProvider provider, ProviderHealthStatus health) {
        ProviderCapabilities caps = provider.capabilities();
        return GatewayStatusInfo.ProviderInfo.builder()
                .name(provider.name())
                .type(provider.getClass().getSimpleName())
                .health(health.name())
                .capabilities(GatewayStatusInfo.CapabilitiesInfo.builder()
                        .streaming(caps.supportsStreaming())
                        .vision(caps.supportsVision())
                        .toolCalls(caps.supportsToolCalls())
                        .structuredOutputs(caps.supportsStructuredOutputs())
                        .jsonMode(caps.supportsJsonMode())
                        .maxContextTokens(caps.maxContextTokens())
                        .build())
                .build();
    }

    private GatewayStatusInfo.RouteInfo toRouteInfo(Route route) {
        List<String> providerNames = route.getProviders() != null
                ? route.getProviders().stream().map(Route.RouteProvider::getProvider).toList()
                : List.of();
        return GatewayStatusInfo.RouteInfo.builder()
                .id(route.getId())
                .modelPattern(route.getModelPattern())
                .strategy(route.getStrategy())
                .providers(providerNames)
                .build();
    }
}