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
package com.dvarahq.core.workspace.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A workspace's own per-key request and token ceilings, overriding the installation-wide ones.
 * Only settings something in this build acts on belong here.
 *
 * <p>Two ceilings, and the rule for both is the same: a value that is absent, unreadable or not
 * positive means <b>inherit the installation-wide limit</b>, never "no limit". A sentinel of
 * {@code 0} or {@code -1} meaning unset is how a limit of zero becomes unenforceable, so it is not
 * used: null is the only way to say nothing was configured.
 */
public record WorkspaceRateLimits(String workspaceId,
                                  Integer requestsPerMinute,
                                  Integer tokensPerMinute) {

    /** A workspace with neither ceiling configured, which inherits both. */
    public static WorkspaceRateLimits unset(String workspaceId) {
        return new WorkspaceRateLimits(workspaceId, null, null);
    }

    public boolean isUnset() {
        return requestsPerMinute == null && tokensPerMinute == null;
    }

    /**
     * Reads the two legacy keys out of a metadata map.
     *
     * <p>Lenient by necessity: it reads values a store that validated nothing accepted, so a
     * non-numeric or non-positive value becomes null — inherit — rather than an error. A workspace
     * whose map says {@code rate-limit.requests-per-minute: "lots"} gets the installation's limit,
     * which is the behaviour that cannot black-hole a workspace.
     */
    public static WorkspaceRateLimits fromMetadata(String workspaceId, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return unset(workspaceId);
        }
        return new WorkspaceRateLimits(workspaceId,
                positiveInt(metadata.get("rate-limit.requests-per-minute")),
                positiveInt(metadata.get("rate-limit.tokens-per-minute")));
    }

    /** Only the fields that are set, so a round trip does not invent keys the read path rejected. */
    public Map<String, Object> toMetadata() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (requestsPerMinute != null) {
            out.put("rate-limit.requests-per-minute", requestsPerMinute);
        }
        if (tokensPerMinute != null) {
            out.put("rate-limit.tokens-per-minute", tokensPerMinute);
        }
        return out;
    }

    private static Integer positiveInt(Object value) {
        if (value == null) {
            return null;
        }
        try {
            int parsed = value instanceof Number n
                    ? n.intValue()
                    : Integer.parseInt(String.valueOf(value).trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
