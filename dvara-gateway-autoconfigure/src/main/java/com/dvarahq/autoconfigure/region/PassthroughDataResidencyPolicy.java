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

import com.dvarahq.core.region.DataResidencyPolicy;

import java.util.List;

/**
 * Default {@link DataResidencyPolicy}: all regions are allowed,
 * no data residency constraints enforced.
 */
public class PassthroughDataResidencyPolicy implements DataResidencyPolicy {

    @Override
    public List<String> allowedRegions(String workspaceId, List<String> candidateRegions) {
        return candidateRegions;
    }

    @Override
    public boolean isAllowed(String workspaceId, String targetRegion) {
        return true;
    }
}