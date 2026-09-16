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

import java.util.List;

/**
 * Enforces data residency constraints during routing and failover.
 *
 * <p>Default ({@code PassthroughDataResidencyPolicy}) allows all regions. Another module or the
 * application may register an implementation that enforces workspace-level geographic
 * restrictions.</p>
 */
public interface DataResidencyPolicy {

    /**
     * Filters candidate regions to those the given workspace may be served from.
     *
     * <p><b>The first argument is the workspace id, not a region.</b> An implementation looks the
     * workspace up by it; a caller passing a region name would miss every lookup and read the empty
     * allow-set as "no restrictions configured".
     *
     * <p>Nothing in main code calls this yet; {@code GeoAwareRoutingStrategy} asks one region at a
     * time through {@link #isAllowed}.
     *
     * @param workspaceId      the workspace being served (may be null — no workspace, no constraint)
     * @param candidateRegions regions to filter
     * @return regions that satisfy residency constraints
     */
    List<String> allowedRegions(String workspaceId, List<String> candidateRegions);

    /**
     * Returns {@code true} if {@code workspaceId} may be served from {@code targetRegion}.
     */
    boolean isAllowed(String workspaceId, String targetRegion);
}