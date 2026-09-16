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
package com.dvarahq.autoconfigure.config.yaml;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * POJO model for the {@code gateway.yaml} configuration schema.
 *
 * <p>The file has two readers, and they take different halves of it. {@code
 * GatewayConfigEnvironmentPostProcessor} runs before the context and turns {@code providers} and
 * {@code rate_limits} into Spring properties; the file config store reads {@code workspaces},
 * {@code api_keys}, {@code routes}, {@code policies}, {@code output_schemas} and
 * {@code prompt_templates} and serves them as repositories for the whole life of the process.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayYamlConfig {

    private List<ProviderEntry> providers;
    private List<RouteEntry> routes;
    private List<WorkspaceEntry> workspaces;
    private List<PolicyEntry> policies;

    @JsonProperty("output_schemas")
    private List<OutputSchemaEntry> outputSchemas;

    @JsonProperty("prompt_templates")
    private List<PromptTemplateEntry> promptTemplates;

    @JsonProperty("api_keys")
    private List<ApiKeyEntry> apiKeys;

    @JsonProperty("rate_limits")
    private RateLimitsEntry rateLimits;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProviderEntry {
        private String name;
        private String type;

        @JsonProperty("api_key")
        private String apiKey;

        @JsonProperty("base_url")
        private String baseUrl;

        // Bedrock-specific
        @JsonProperty("access_key")
        private String accessKey;

        @JsonProperty("secret_key")
        private String secretKey;

        private String region;
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

    /**
     * A workspace, and the settings that govern it.
     *
     * <p>Optional: an {@code api_keys} entry naming a workspace that has no block of its own gets a
     * bare ACTIVE workspace with no metadata, which is what a quickstart wants. Declare one when you
     * need {@link #metadata}.
     *
     * <p>{@code metadata} is where per-workspace enforcement lives: {@code pii.action},
     * {@code guardrail.action}, {@code rate-limit.requests-per-minute} and the rest. The typed
     * settings repositories are injected as {@code ObjectProvider} and fall back to this map when
     * absent, so a workspace can be fully governed from the file without any of them.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkspaceEntry {
        private String id;
        private String name;
        private String status;
        private String region;
        private Map<String, Object> metadata;

        /**
         * The provider credentials this workspace brought, keyed by the logical secret key —
         * {@code provider.openai.api-key}, {@code provider.bedrock.access-key}. Absent or empty
         * means the workspace uses whatever the installation configured for everybody.
         *
         * <p>A map cannot hold the same key twice, which is the cardinality wanted: one live
         * credential per workspace per key, many keys per workspace, many providers per workspace.
         *
         * <p>Write {@code ${VAR}} rather than the value. Encrypting into this file would need the
         * key beside it, which protects nothing the file's permissions do not.
         */
        private Map<String, String> credentials;
    }

    /**
     * A policy, carrying the policy YAML DSL.
     *
     * <p>{@code workspace} is optional; omitting it makes the policy platform-global, applying to
     * every workspace, exactly as a {@code workspace_id IS NULL} row does.
     *
     * <p>The DSL is compiled at startup by {@code DefaultPolicyEngine.rebuildIndex()}. A policy that
     * will not compile is skipped and named rather than taking the others with it, so one
     * typo does not disarm enforcement for the whole file.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PolicyEntry {
        private String id;
        private String workspace;
        private String status;
        private String dsl;
    }

    /**
     * An API key, given as a fingerprint rather than as the key itself.
     *
     * <p>The gateway never compares keys; it compares SHA-256 hashes, so the file need not hold a
     * live credential and can stay in version control.
     *
     * <p>Produce the pair with {@code --generate-key}, or fingerprint a key you already hold with
     * {@code --hash-key}. Neither starts the gateway.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ApiKeyEntry {

        /**
         * {@code sha256:} followed by 64 lowercase hex characters. The only accepted form.
         *
         * <p>The YAML name is spelled out because this file binds every snake_case key explicitly
         * rather than through a naming strategy; without it, {@code key_hash:} binds to nothing and
         * the entry looks like one that forgot to declare a key.
         */
        @JsonProperty("key_hash")
        private String keyHash;

        /**
         * Retained only so that a file that still uses {@code key:} is refused by name instead of
         * being ignored. This class is {@code ignoreUnknown = true}, so without the field the entry
         * would produce no key, every caller would be served as anonymous, and no per-workspace
         * control would apply. Nothing reads it as a key; {@code YamlConfigStore} reads it to refuse
         * the boot.
         */
        @Deprecated
        private String key;

        /**
         * Retained for refusal only, for the same reason as {@link #key}. A key minted at startup
         * and stored nowhere would not survive a restart, and two replicas would mint two different
         * ones; {@code require-api-key} defaults to false, so nothing needs minting to try the
         * gateway.
         */
        @Deprecated
        private boolean generate;

        private String workspace;
        private String name;
        private List<String> scopes;
    }

    /**
     * A JSON-schema contract for a route or a model pattern.
     *
     * <p>When one matches, a response that does not satisfy the schema is refused. It is validated
     * once, with no retry — so this is the difference between asking a model for JSON and requiring
     * it.
     *
     * <p>{@code model} and {@code route} are the scope, and at least one is required: a schema
     * scoped to neither would never match any request, which the gateway already refuses at the API
     * with {@code INVALID_OUTPUT_SCHEMA_SCOPE}. Validating it here means a file cannot express what
     * the API rejects.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OutputSchemaEntry {
        private String id;
        private String model;
        private String route;
        private Map<String, Object> schema;

        // Unknown keys are read and dropped (ignoreUnknown above), so removing an optional field
        // from this class is safe for existing files.
        private Boolean enabled;
    }

    /**
     * A named prompt, resolved when a request carries {@code metadata.prompt_template_id}.
     *
     * <p>{@code variables} is derived, not declared: the store extracts {@code {{name}}}
     * placeholders from {@code system} and {@code template} with
     * {@code PromptTemplateRenderer.extractVariables}. A hand-written list that disagreed with the
     * text would refuse a request with {@code PROMPT_VARIABLE_MISSING} for a variable plainly in it.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PromptTemplateEntry {
        private String id;
        private String workspace;
        private String name;
        private String description;
        private String model;
        private String system;
        private String template;
        private String status;
        private List<String> tags;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RateLimitsEntry {
        @JsonProperty("requests_per_minute")
        private Integer requestsPerMinute;

        @JsonProperty("tokens_per_minute")
        private Integer tokensPerMinute;

        /** Burst capacity; absent means the same as the rate. */
        @JsonProperty("requests_burst")
        private Integer requestsBurst;

        @JsonProperty("tokens_burst")
        private Integer tokensBurst;
    }
}