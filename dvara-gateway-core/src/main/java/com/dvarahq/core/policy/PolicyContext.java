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
package com.dvarahq.core.policy;

import java.util.Map;

/**
 * Who and what a policy decision is being made about.
 *
 * @param workspaceId the governed workspace, or null for a call with no workspace
 * @param userId      the acting user where one is known
 * @param apiKey      <b>the key's opaque ID, never the key itself.</b> A live key copied into a
 *                    record that prints its components would be one {@code log.debug} away from
 *                    the output.
 * @param attributes  everything a rule can match on beyond the three above
 */
public record PolicyContext(
        String workspaceId,
        String userId,
        String apiKey,
        Map<String, Object> attributes) {

    public static PolicyContext empty() {
        return new PolicyContext(null, null, null, Map.of());
    }

    /**
     * Never prints {@code apiKey}.
     *
     * <p>The component is specified as an id, and this is the second line of defence if a caller
     * passes a live key: a record's generated {@code toString} prints every component. Whether it is
     * present is still worth seeing, so absence and presence are distinguished.</p>
     */
    @Override
    public String toString() {
        return "PolicyContext[workspaceId=" + workspaceId
                + ", userId=" + userId
                + ", apiKey=" + (apiKey == null ? "null" : "<redacted>")
                + ", attributes=" + attributes + "]";
    }
}