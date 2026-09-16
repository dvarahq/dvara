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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GatewayStatusInfo {

    private String status;
    private String mode;
    private String version;
    private long uptimeSeconds;
    private RegionInfo region;
    private List<ProviderInfo> providers;
    private List<RouteInfo> routes;
    private RateLimitInfo rateLimits;
    /**
     * Blocks contributed by modules the endpoint does not know, written under their own keys —
     * see {@code GatewayStatusSection}. A bundle client contributes {@code configBundle}, the
     * revocation channel {@code denyList}.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private Map<String, Object> sections;
    private List<String> warnings;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProviderInfo {
        private String name;
        private String type;
        private String health;
        private CapabilitiesInfo capabilities;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CapabilitiesInfo {
        private boolean streaming;
        private boolean vision;
        private boolean toolCalls;
        private boolean structuredOutputs;
        private boolean jsonMode;
        private int maxContextTokens;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RouteInfo {
        private String id;
        private String modelPattern;
        private String strategy;
        private List<String> providers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RateLimitInfo {
        private boolean enabled;
        /** Requests per API key per 60 seconds; how they are counted is the limiter's. */
        private int perKeyRequestsPerMinute;
        /** Tokens per API key per 60 seconds; how they are counted is the limiter's. */
        private int perKeyTokensPerMinute;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RegionInfo {
        private String id;
        private String name;
        private boolean regionAware;
    }

    @com.fasterxml.jackson.annotation.JsonAnyGetter
    public Map<String, Object> anySections() {
        return sections == null ? Map.of() : sections;
    }
}