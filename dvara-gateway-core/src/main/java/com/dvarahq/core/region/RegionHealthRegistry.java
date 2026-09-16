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
package com.dvarahq.core.region;

import java.util.Map;

/**
 * Tracks region health across the multi-region topology.
 *
 * <p>Default ({@code SingleRegionHealthRegistry}) reports the current region
 * as always {@link RegionHealthStatus#HEALTHY}. Another module or the application may register an
 * implementation that aggregates cross-region heartbeats.</p>
 */
public interface RegionHealthRegistry {

    /**
     * Returns the health status of the specified region.
     */
    RegionHealthStatus getHealth(String region);

    /**
     * Returns health status for all known regions.
     *
     * <p>Nothing in this repository consumes this; it is the shape an operator view of the
     * topology would want, and a registry that tracks regions has the data anyway.
     */
    Map<String, RegionHealthStatus> allRegionHealth();

    /**
     * Whether a region may be routed to: true unless this registry <b>positively reports it
     * unreachable</b>.
     *
     * <p>{@link RegionHealthStatus#UNKNOWN} means the registry has no information, and a registry
     * that tracks a single region has none about any other, so treating it as unavailable would
     * exclude every other region. A routing control must fail closed on evidence of a problem, not
     * on the absence of evidence, which is why this tests for the one status that is a claim.
     */
    default boolean isAvailable(String region) {
        return getHealth(region) != RegionHealthStatus.UNREACHABLE;
    }
}