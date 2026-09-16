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

import java.util.Map;

/**
 * Default {@link RegionHealthRegistry}: it knows about one region, this instance's own, and says
 * so. Its own region is {@link RegionHealthStatus#HEALTHY}; every other region is
 * {@link RegionHealthStatus#UNKNOWN}, because this registry has no way to find out.
 *
 * <p>{@code UNKNOWN} rather than {@code UNREACHABLE}: a routing strategy that filters candidates on
 * availability would otherwise drop every provider outside this region, and with no region
 * configured, every provider at all. A registry that tracks regions can still report
 * {@code UNREACHABLE}; silence is not bad news.
 */
public class SingleRegionHealthRegistry implements RegionHealthRegistry {

    private final RegionContext regionContext;

    public SingleRegionHealthRegistry(RegionContext regionContext) {
        this.regionContext = regionContext;
    }

    @Override
    public RegionHealthStatus getHealth(String region) {
        return regionContext.currentRegion()
                .filter(current -> current.equals(region))
                .map(r -> RegionHealthStatus.HEALTHY)
                .orElse(RegionHealthStatus.UNKNOWN);
    }

    @Override
    public Map<String, RegionHealthStatus> allRegionHealth() {
        return regionContext.currentRegion()
                .map(r -> Map.of(r, RegionHealthStatus.HEALTHY))
                .orElse(Map.of());
    }
}