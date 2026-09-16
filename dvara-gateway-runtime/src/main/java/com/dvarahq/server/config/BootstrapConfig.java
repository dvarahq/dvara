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
package com.dvarahq.server.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * POJO model for the {@code bootstrap.yaml} configuration schema.
 * <p>
 * Used by {@link BootstrapLoader} to seed workspaces, API keys, and routes
 * into the gateway's repositories on first startup.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BootstrapConfig {

    private List<WorkspaceEntry> workspaces;

    @JsonProperty("api_keys")
    private List<ApiKeyEntry> apiKeys;

    private List<RouteEntry> routes;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkspaceEntry {
        private String id;
        private String name;
        private String status;
        private String region;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiKeyEntry {
        private String workspace;
        private String name;
        private String key;
        private boolean generate;
        private List<String> scopes;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RouteEntry {
        private String id;
        private String model;
        private String provider;
        private String strategy;
        private List<RouteProviderEntry> providers;
        private String fallback;

        @JsonProperty("pinned_model_version")
        private String pinnedModelVersion;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RouteProviderEntry {
        private String provider;
        private Integer weight;
    }
}