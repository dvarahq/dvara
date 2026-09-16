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
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import com.dvarahq.core.routing.Route;
import com.dvarahq.core.routing.RouteRepository;
import com.dvarahq.core.status.GatewayStatusSection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * One broken contributor does not cost an operator the whole status payload.
 *
 * <p>Every section reports a channel, such as an audit spool, a config bundle or a revocation list,
 * so the moment one of them cannot answer is the moment somebody opens this endpoint. A section
 * that throws loses only its own block, and a section reusing another's key is dropped with a
 * warning rather than silently overwriting it.
 */
class GatewayStatusSectionIsolationTest {

    private record FixedSection(String key, Object value, List<String> warnings)
            implements GatewayStatusSection {
    }

    /** A section whose channel is broken — which is the state it exists to report. */
    private static final class ThrowingSection implements GatewayStatusSection {
        @Override
        public String key() {
            return "spool";
        }

        @Override
        public Object value() {
            throw new IllegalStateException("spool.db is not a database");
        }
    }

    private GatewayStatusEndpoint endpoint(RouteRepository routes, GatewayStatusSection... sections) {
        ProviderHealthRegistry health = mock(ProviderHealthRegistry.class);
        when(health.getHealth(anyString())).thenReturn(ProviderHealthStatus.HEALTHY);
        RegionContext region = mock(RegionContext.class);
        when(region.currentRegion()).thenReturn(Optional.of("eu-west-1"));
        ApplicationContext context = mock(ApplicationContext.class);
        when(context.getStartupDate()).thenReturn(System.currentTimeMillis());

        @SuppressWarnings("unchecked")
        ObjectProvider<GatewayStatusSection> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(sections));

        return new GatewayStatusEndpoint(List.<LlmProvider>of(), health, routes,
                new GatewayProperties(), new GatewayRegionProperties(), region, context, null, provider);
    }

    private static RouteRepository emptyRoutes() {
        RouteRepository routes = mock(RouteRepository.class);
        when(routes.findAll()).thenReturn(List.<Route>of());
        return routes;
    }

    @Test
    void aSectionThatThrowsCostsItsOwnBlockAndNothingElse() {
        GatewayStatusInfo info = endpoint(emptyRoutes(),
                new ThrowingSection(),
                new FixedSection("denyList", java.util.Map.of("version", 7), List.of())).status();

        assertThat(info.getSections()).containsOnlyKeys("denyList");
        assertThat(info.getStatus()).isEqualTo("running");
        assertThat(info.getWarnings())
                .anySatisfy(w -> assertThat(w)
                        .contains("ThrowingSection")
                        .contains("spool.db is not a database"));
    }

    @Test
    void aSectionReusingAnotherKeyIsDroppedAndSaidSo() {
        GatewayStatusInfo info = endpoint(emptyRoutes(),
                new FixedSection("configBundle", java.util.Map.of("stale", false), List.of()),
                new FixedSection("configBundle", java.util.Map.of("stale", true), List.of())).status();

        assertThat(info.getSections()).containsOnlyKeys("configBundle");
        assertThat(info.getSections().get("configBundle"))
                .as("the first claim keeps the key")
                .isEqualTo(java.util.Map.of("stale", false));
        assertThat(info.getWarnings())
                .anySatisfy(w -> assertThat(w).contains("configBundle").contains("dropped"));
    }

    /**
     * A section may not take a key the payload already writes.
     *
     * <p>The blocks go out through {@code @JsonAnyGetter}, beside the declared properties, so a
     * section keyed {@code providers} would emit that key twice in one object. Most parsers keep the
     * last, which means a consumer would read the section where the provider list should be. The
     * sibling guard above cannot see this: no other section is involved.</p>
     */
    @Test
    void aSectionClaimingAFieldsKeyIsDroppedAndSaidSo() {
        GatewayStatusInfo info = endpoint(emptyRoutes(),
                new FixedSection("providers", java.util.Map.of("mine", true), List.of())).status();

        assertThat(info.getSections()).doesNotContainKey("providers");
        assertThat(info.getProviders())
                .as("the payload's own field is untouched")
                .isNotNull();
        assertThat(info.getWarnings())
                .anySatisfy(w -> assertThat(w)
                        .contains("providers")
                        .contains("collides")
                        .contains("dropped"));
    }

    @Test
    void everyFieldOfThePayloadIsReserved() {
        // Derived from the type, not listed: a field added to GatewayStatusInfo must not silently
        // leave the reserved set behind. Checking a few of the names it must contain is enough to
        // catch a derivation that stopped working, which is the failure that would matter.
        for (String key : List.of("status", "mode", "version", "uptimeSeconds",
                "region", "providers", "routes", "rateLimits", "warnings")) {
            GatewayStatusInfo info = endpoint(emptyRoutes(),
                    new FixedSection(key, java.util.Map.of("x", 1), List.of())).status();

            assertThat(info.getSections())
                    .as("section '%s' must not shadow the field of the same name", key)
                    .doesNotContainKey(key);
        }
    }

    @Test
    void theSectionCarrierIsNotItselfReserved() {
        // `sections` is @JsonIgnore — the carrier, not a key — so a section may legitimately use it.
        GatewayStatusInfo info = endpoint(emptyRoutes(),
                new FixedSection("sections", java.util.Map.of("x", 1), List.of())).status();

        assertThat(info.getSections()).containsKey("sections");
    }

    /**
     * A route read that fails is reported as a failure, not as an empty configuration.
     *
     * <p>"No routes configured" sends an operator looking for a missing configuration; the truth is
     * that this pod could not ask. And whoever polls this endpoint treats a 500 as the gateway being
     * unreachable, which it is not — it serves requests from its cache while the read fails.
     */
    @Test
    void aRouteReadThatFailsIsNotReportedAsNoRoutes() {
        RouteRepository broken = mock(RouteRepository.class);
        when(broken.findAll()).thenThrow(new IllegalStateException("connection refused"));

        GatewayStatusInfo info = endpoint(broken).status();

        assertThat(info.getStatus()).isEqualTo("running");
        assertThat(info.getRoutes()).isEmpty();
        assertThat(info.getWarnings())
                .anySatisfy(w -> assertThat(w).contains("Route list unavailable").contains("connection refused"));
        assertThat(info.getWarnings())
                .as("an unreadable list must not be reported as an empty one")
                .noneSatisfy(w -> assertThat(w).contains("No routes configured"));
    }
}
