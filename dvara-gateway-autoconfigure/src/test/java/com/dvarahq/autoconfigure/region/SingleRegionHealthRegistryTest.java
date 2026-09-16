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
package com.dvarahq.autoconfigure.region;

import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.region.RegionHealthRegistry;
import com.dvarahq.core.region.RegionHealthStatus;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SingleRegionHealthRegistryTest {

    @Test
    void currentRegion_alwaysHealthy() {
        RegionContext context = () -> Optional.of("us-east-1");
        SingleRegionHealthRegistry registry = new SingleRegionHealthRegistry(context);

        assertThat(registry.getHealth("us-east-1")).isEqualTo(RegionHealthStatus.HEALTHY);
        assertThat(registry.isAvailable("us-east-1")).isTrue();
    }

    /**
     * A region this registry does not track is UNKNOWN, and UNKNOWN is routable. The registry only
     * knows about its own region, so it cannot honestly call any other region unreachable.
     */
    @Test
    void aRegionItDoesNotTrackIsUnknownAndStillRoutable() {
        RegionContext context = () -> Optional.of("us-east-1");
        SingleRegionHealthRegistry registry = new SingleRegionHealthRegistry(context);

        assertThat(registry.getHealth("eu-west-1")).isEqualTo(RegionHealthStatus.UNKNOWN);
        assertThat(registry.isAvailable("eu-west-1")).isTrue();
    }

    /** Only a positive report of UNREACHABLE excludes a region, whoever makes it. */
    @Test
    void onlyAPositiveUnreachableExcludesARegion() {
        RegionHealthRegistry tracking = new RegionHealthRegistry() {
            @Override
            public RegionHealthStatus getHealth(String region) {
                return switch (region) {
                    case "healthy" -> RegionHealthStatus.HEALTHY;
                    case "degraded" -> RegionHealthStatus.DEGRADED;
                    case "unknown" -> RegionHealthStatus.UNKNOWN;
                    default -> RegionHealthStatus.UNREACHABLE;
                };
            }

            @Override
            public Map<String, RegionHealthStatus> allRegionHealth() {
                return Map.of();
            }
        };

        assertThat(tracking.isAvailable("healthy")).isTrue();
        assertThat(tracking.isAvailable("degraded")).isTrue();
        assertThat(tracking.isAvailable("unknown")).isTrue();
        assertThat(tracking.isAvailable("gone")).isFalse();
    }

    @Test
    void allRegionHealth_returnsCurrentRegionOnly() {
        RegionContext context = () -> Optional.of("us-east-1");
        SingleRegionHealthRegistry registry = new SingleRegionHealthRegistry(context);

        Map<String, RegionHealthStatus> all = registry.allRegionHealth();

        assertThat(all).hasSize(1);
        assertThat(all).containsEntry("us-east-1", RegionHealthStatus.HEALTHY);
    }

    @Test
    void allRegionHealth_returnsEmptyWhenNoRegion() {
        RegionContext context = () -> Optional.empty();
        SingleRegionHealthRegistry registry = new SingleRegionHealthRegistry(context);

        Map<String, RegionHealthStatus> all = registry.allRegionHealth();

        assertThat(all).isEmpty();
    }

    /**
     * With no region configured, the default on a single-region install, the registry knows nothing
     * about anywhere, so every region is UNKNOWN and routable.
     */
    @Test
    void noRegionConfigured_everyRegionIsUnknownAndRoutable() {
        RegionContext context = () -> Optional.empty();
        SingleRegionHealthRegistry registry = new SingleRegionHealthRegistry(context);

        assertThat(registry.getHealth("us-east-1")).isEqualTo(RegionHealthStatus.UNKNOWN);
        assertThat(registry.isAvailable("us-east-1")).isTrue();
    }
}