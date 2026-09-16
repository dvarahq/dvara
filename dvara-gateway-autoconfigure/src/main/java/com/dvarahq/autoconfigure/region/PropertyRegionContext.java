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

import com.dvarahq.autoconfigure.GatewayRegionProperties;
import com.dvarahq.core.region.RegionContext;

import java.util.Optional;

/**
 * Default {@link RegionContext} that reads region identity from
 * {@code dvara.region.id}. Returns empty when unconfigured.
 */
public class PropertyRegionContext implements RegionContext {

    private final String regionId;

    public PropertyRegionContext(GatewayRegionProperties properties) {
        this(properties == null ? null : properties.getId());
    }

    /** @param regionId the resolved region id; see {@link RegionId} for the properties it comes from. */
    public PropertyRegionContext(String regionId) {
        this.regionId = (regionId != null && !regionId.isBlank()) ? regionId : null;
    }

    @Override
    public Optional<String> currentRegion() {
        return Optional.ofNullable(regionId);
    }
}