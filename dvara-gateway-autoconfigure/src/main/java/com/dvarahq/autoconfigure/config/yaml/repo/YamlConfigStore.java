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
package com.dvarahq.autoconfigure.config.yaml.repo;

import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyStatus;
import com.dvarahq.core.guardrail.OutputSchemaConfig;
import com.dvarahq.core.policy.Policy;
import com.dvarahq.core.policy.PolicyStatus;
import com.dvarahq.core.prompt.PromptTemplate;
import com.dvarahq.core.prompt.PromptTemplateRenderer;
import com.dvarahq.core.prompt.PromptTemplateStatus;
import com.dvarahq.core.routing.Route;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything {@code gateway.yaml} configures, decoded once and held immutable.
 *
 * <p>The file is the live source of configuration for the life of the process; nothing is copied
 * anywhere else.
 *
 * <h2>Parsed once, held immutable</h2>
 *
 * <p>Deliberately not watched: a single process reading a file beside itself has one generation of
 * configuration, built before any request is served, and an edit means a restart. That removes the
 * whole class of half-applied-config bugs. The maps are built once in the constructor and never
 * mutated, so no synchronisation is needed on the read path.
 *
 * <h2>Identity comes from the file</h2>
 *
 * <p>A workspace's id is the string you wrote, not a generated one, so ids are stable across
 * restarts and anything else may reference them.
 */
public final class YamlConfigStore {

    /** The only accepted fingerprint form in {@code api_keys}. */
    private static final String HASH_PREFIX = "sha256:";
    private static final java.util.regex.Pattern HEX_64 = java.util.regex.Pattern.compile("[0-9a-f]{64}");


    private static final Logger log = LoggerFactory.getLogger(YamlConfigStore.class);

    /** What an {@code api_keys} entry gets when it names no workspace. */
    static final String DEFAULT_WORKSPACE = "default";

    private final Map<String, Workspace> workspacesById;

    /**
     * workspace id → its own provider credentials, keyed by logical secret key.
     *
     * <p>Deliberately not on {@link Workspace}: that model is serialized and shared beyond this
     * file, and the values here are secrets. They are held beside the workspaces instead, and
     * reachable only through the narrow
     * {@link com.dvarahq.core.secret.WorkspaceCredentialSource} seam.
     */
    private final Map<String, Map<String, String>> credentialsByWorkspace;
    private final Map<String, ApiKey> keysByHash;
    private final List<ApiKey> keys;
    private final Map<String, Route> routesById;
    private final List<Route> routes;
    private final Map<String, Policy> policiesById;
    private final List<Policy> policies;
    private final Map<String, OutputSchemaConfig> schemasById;
    private final List<OutputSchemaConfig> schemas;
    private final Map<String, PromptTemplate> templatesById;
    private final List<PromptTemplate> templates;

    public YamlConfigStore(GatewayYamlConfig config) {
        Instant now = Instant.now();
        Map<String, Workspace> workspaces = new LinkedHashMap<>();
        Map<String, Map<String, String>> credentials = new LinkedHashMap<>();

        for (GatewayYamlConfig.WorkspaceEntry entry : nullSafe(config.getWorkspaces())) {
            if (entry.getId() == null || entry.getId().isBlank()) {
                continue;
            }
            if (entry.getCredentials() != null && !entry.getCredentials().isEmpty()) {
                // Blank values dropped rather than stored: an unresolved ${VAR} arrives as an empty
                // string, and an empty credential that shadowed the installation-wide one would send
                // no key upstream at all rather than falling back.
                Map<String, String> own = new LinkedHashMap<>();
                entry.getCredentials().forEach((secretKey, value) -> {
                    if (secretKey != null && !secretKey.isBlank() && value != null && !value.isBlank()) {
                        own.put(secretKey, value);
                    }
                });
                if (!own.isEmpty()) {
                    credentials.put(entry.getId(), Map.copyOf(own));
                }
            }
            workspaces.put(entry.getId(), Workspace.builder()
                    .id(entry.getId())
                    .name(entry.getName() != null ? entry.getName() : entry.getId())
                    // Fail closed, as the bundle repositories do: an unreadable status must not
                    // promote a workspace into serving. "ACTIVE" or nothing.
                    .status(entry.getStatus() == null || "ACTIVE".equalsIgnoreCase(entry.getStatus())
                            ? WorkspaceStatus.ACTIVE : WorkspaceStatus.SUSPENDED)
                    .region(entry.getRegion())
                    .metadata(entry.getMetadata() != null ? Map.copyOf(entry.getMetadata()) : Map.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
        }

        List<ApiKey> decodedKeys = new ArrayList<>();
        Map<String, ApiKey> byHash = new LinkedHashMap<>();

        for (GatewayYamlConfig.ApiKeyEntry entry : nullSafe(config.getApiKeys())) {
            String workspaceId = entry.getWorkspace() != null && !entry.getWorkspace().isBlank()
                    ? entry.getWorkspace() : DEFAULT_WORKSPACE;
            // A key may name a workspace with no block of its own — the quickstart case. Synthesize
            // it rather than refuse: the workspace exists to scope the key, and an ACTIVE workspace
            // with no metadata is exactly what an undeclared one means.
            workspaces.computeIfAbsent(workspaceId, id -> Workspace.builder()
                    .id(id).name(id).status(WorkspaceStatus.ACTIVE).metadata(Map.of())
                    .createdAt(now).updatedAt(now).build());

            String label = entry.getName() != null ? entry.getName() : "(unnamed)";

            // A key: or generate: entry is refused, not ignored. Ignoring it would drop the key,
            // and a gateway with no keys serves every caller as anonymous with no workspace, so
            // every per-workspace control silently stops applying.
            if (entry.getKey() != null && !entry.getKey().isBlank()) {
                throw new IllegalStateException("api_keys entry '" + label + "' uses 'key:', which is"
                        + " no longer accepted: a configuration file should not hold a live credential."
                        + " Fingerprint the key you already have with --hash-key - (the key on standard"
                        + " input) or --hash-key-file <path>, and put the result in 'key_hash:'. Your"
                        + " callers keep the same key.");
            }
            if (entry.isGenerate()) {
                throw new IllegalStateException("api_keys entry '" + label + "' uses 'generate: true',"
                        + " which is no longer accepted: it minted a key that no restart survived and"
                        + " that two replicas disagreed about. To try the gateway, send no key at all"
                        + " — dvara.llm-gateway.data-plane.require-api-key defaults to false. For a key"
                        + " that lasts, run --generate-key.");
            }

            String keyHash = normalizeKeyHash(entry.getKeyHash(), label);
            if (keyHash == null) {
                log.warn("Ignoring an api_keys entry with no 'key_hash' (name={})", label);
                continue;
            }

            // An unnamed key's id is derived from its hash: stable across restarts, and never shaped
            // like a display prefix (gw_ + 8 hex), which the spend counter treats as a legacy
            // attribution and drops.
            ApiKey key = ApiKey.builder()
                    .id(entry.getName() != null ? entry.getName() : "key-" + keyHash.substring(0, 16))
                    .workspaceId(workspaceId)
                    .name(entry.getName() != null ? entry.getName() : "gateway-yaml-key")
                    // There is no plaintext here to take a display prefix from, and inventing one
                    // from the hash would look like a key and match nothing. Empty is honest: what
                    // identifies a key in this build is its name.
                    .keyPrefix("")
                    .keyHash(keyHash)
                    // No scopes means unrestricted.
                    .scopes(entry.getScopes() != null ? List.copyOf(entry.getScopes()) : List.of())
                    .status(ApiKeyStatus.ACTIVE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            decodedKeys.add(key);
            ApiKey displaced = byHash.put(key.getKeyHash(), key);
            if (displaced != null) {
                // Silently keeping the last one would mean the earlier entry's workspace and scopes
                // simply do not apply, with the file looking correct — the same shape of failure as
                // a mis-spelled field being dropped by ignoreUnknown.
                log.warn("Two api_keys entries in gateway.yaml carry the same key ('{}' and '{}'). "
                                + "Only the last one applies, so the earlier entry's workspace and "
                                + "scopes are being ignored.",
                        displaced.getName(), key.getName());
            }
        }

        List<Route> decodedRoutes = new ArrayList<>();
        Map<String, Route> routeById = new LinkedHashMap<>();
        int unnamed = 0;
        for (GatewayYamlConfig.RouteEntry entry : nullSafe(config.getRoutes())) {
            String id = entry.getId() != null && !entry.getId().isBlank()
                    ? entry.getId() : "route-" + (++unnamed);
            Route route = Route.builder()
                    .id(id)
                    .modelPattern(entry.getModel())
                    .strategy(entry.getStrategy() != null ? entry.getStrategy() : "model-prefix")
                    .providers(routeProviders(entry))
                    .pinnedModelVersion(entry.getPinnedModelVersion())
                    .version(1)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            decodedRoutes.add(route);
            routeById.put(id, route);
        }

        List<Policy> decodedPolicies = new ArrayList<>();
        Map<String, Policy> policyById = new LinkedHashMap<>();
        for (GatewayYamlConfig.PolicyEntry entry : nullSafe(config.getPolicies())) {
            if (entry.getId() == null || entry.getId().isBlank()) {
                continue;
            }
            Policy policy = Policy.builder()
                    .id(entry.getId())
                    .dsl(entry.getDsl())
                    // Reading an unknown status as inactive would silently stop enforcing a policy,
                    // which is the permissive direction, so validation rejects anything but ACTIVE.
                    .status(PolicyStatus.ACTIVE)
                    .version(1)
                    .workspaceId(entry.getWorkspace())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            decodedPolicies.add(policy);
            policyById.put(policy.getId(), policy);
        }

        List<OutputSchemaConfig> decodedSchemas = new ArrayList<>();
        Map<String, OutputSchemaConfig> schemaById = new LinkedHashMap<>();
        for (GatewayYamlConfig.OutputSchemaEntry entry : nullSafe(config.getOutputSchemas())) {
            if (entry.getId() == null || entry.getId().isBlank()) {
                continue;
            }
            OutputSchemaConfig schema = OutputSchemaConfig.builder()
                    .id(entry.getId())
                    .modelPattern(entry.getModel())
                    .routeId(entry.getRoute())
                    .schema(entry.getSchema() != null ? Map.copyOf(entry.getSchema()) : Map.of())
                    // Enabled unless it says otherwise: writing a schema into the file is the act of
                    // asking for it. Defaulting the other way would make every entry inert until a
                    // second field was found and set.
                    .enabled(entry.getEnabled() == null || entry.getEnabled())
                    .build();
            decodedSchemas.add(schema);
            schemaById.put(schema.getId(), schema);
        }

        List<PromptTemplate> decodedTemplates = new ArrayList<>();
        Map<String, PromptTemplate> templateById = new LinkedHashMap<>();
        for (GatewayYamlConfig.PromptTemplateEntry entry : nullSafe(config.getPromptTemplates())) {
            if (entry.getId() == null || entry.getId().isBlank()) {
                continue;
            }
            PromptTemplate template = PromptTemplate.builder()
                    .id(entry.getId())
                    .workspaceId(entry.getWorkspace())
                    .name(entry.getName() != null ? entry.getName() : entry.getId())
                    .description(entry.getDescription())
                    .model(entry.getModel())
                    .systemPrompt(entry.getSystem())
                    .userTemplate(entry.getTemplate())
                    // Derived, not declared — the same extraction the rest of the product uses. A
                    // hand-written list that disagreed with the text would refuse a request with
                    // PROMPT_VARIABLE_MISSING for a variable plainly present in it.
                    .variables(variablesOf(entry))
                    // ACTIVE unless it says otherwise. DRAFT is the API's default because a template
                    // is drafted before it is used; a template written into a config file has been
                    // decided, and defaulting to DRAFT would make every one of them unusable
                    // (PROMPT_TEMPLATE_NOT_ACTIVE) until a field nobody knew about was set.
                    .status(entry.getStatus() == null
                            ? PromptTemplateStatus.ACTIVE
                            : PromptTemplateStatus.valueOf(entry.getStatus().toUpperCase()))
                    .version(1)
                    .tags(entry.getTags() != null ? List.copyOf(entry.getTags()) : List.of())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            decodedTemplates.add(template);
            templateById.put(template.getId(), template);
        }

        this.schemas = List.copyOf(decodedSchemas);
        this.schemasById = Map.copyOf(schemaById);
        this.templates = List.copyOf(decodedTemplates);
        this.templatesById = Map.copyOf(templateById);
        this.workspacesById = Map.copyOf(workspaces);
        this.credentialsByWorkspace = Map.copyOf(credentials);
        warnAboutSettingsNothingHereReads(workspaces);
        this.keys = List.copyOf(decodedKeys);
        this.keysByHash = Map.copyOf(byHash);
        this.routes = List.copyOf(decodedRoutes);
        this.routesById = Map.copyOf(routeById);
        this.policies = List.copyOf(decodedPolicies);
        this.policiesById = Map.copyOf(policyById);
    }

    private static List<Route.RouteProvider> routeProviders(GatewayYamlConfig.RouteEntry entry) {
        List<Route.RouteProvider> providers = new ArrayList<>();
        if (entry.getProvider() != null) {
            providers.add(Route.RouteProvider.builder().provider(entry.getProvider()).weight(1).build());
        }
        for (GatewayYamlConfig.RouteProviderEntry rp : nullSafe(entry.getProviders())) {
            providers.add(Route.RouteProvider.builder()
                    .provider(rp.getProvider())
                    .weight(rp.getWeight() != null ? rp.getWeight() : 1)
                    .build());
        }
        if (entry.getFallback() != null && !entry.getFallback().isBlank()) {
            // Weight 0 marks a fallback rather than a share of traffic, which is the convention
            // FallbackResolver reads.
            providers.add(Route.RouteProvider.builder().provider(entry.getFallback()).weight(0).build());
        }
        return List.copyOf(providers);
    }

    /** Placeholders in either half of the template, in a stable order and without duplicates. */
    private static List<String> variablesOf(GatewayYamlConfig.PromptTemplateEntry entry) {
        List<String> found = new ArrayList<>(PromptTemplateRenderer.extractVariables(entry.getSystem()));
        for (String v : PromptTemplateRenderer.extractVariables(entry.getTemplate())) {
            if (!found.contains(v)) {
                found.add(v);
            }
        }
        return List.copyOf(found);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list != null ? list : List.of();
    }

    public Map<String, Workspace> workspacesById() {
        return workspacesById;
    }

    public List<Workspace> workspaces() {
        return List.copyOf(workspacesById.values());
    }

    public Map<String, ApiKey> keysByHash() {
        return keysByHash;
    }

    public List<ApiKey> keys() {
        return keys;
    }

    public Map<String, Route> routesById() {
        return routesById;
    }

    public List<Route> routes() {
        return routes;
    }

    public Map<String, Policy> policiesById() {
        return policiesById;
    }

    public List<Policy> policies() {
        return policies;
    }


    public Map<String, OutputSchemaConfig> schemasById() {
        return schemasById;
    }

    public List<OutputSchemaConfig> schemas() {
        return schemas;
    }

    public Map<String, PromptTemplate> templatesById() {
        return templatesById;
    }

    public List<PromptTemplate> templates() {
        return templates;
    }


    /**
     * Accepts {@code sha256:} followed by 64 lowercase hex characters, and returns the bare hash.
     *
     * <p>A malformed fingerprint is refused at startup rather than stored. Stored, it would simply
     * never match, and the operator would meet it as a 401 on a key they believe is configured —
     * a typo presenting as an authentication bug.
     */
    private static String normalizeKeyHash(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if (!value.startsWith(HASH_PREFIX)) {
            throw new IllegalStateException("api_keys entry '" + label + "' has a 'key_hash' that does"
                    + " not start with '" + HASH_PREFIX + "'. Produce one with --generate-key, or"
                    + " fingerprint an existing key with --hash-key - (the key on standard input) or"
                    + " --hash-key-file <path>.");
        }
        String hex = value.substring(HASH_PREFIX.length());
        if (!HEX_64.matcher(hex).matches()) {
            throw new IllegalStateException("api_keys entry '" + label + "' has a 'key_hash' that is"
                    + " not 64 lowercase hex characters after '" + HASH_PREFIX + "' (found "
                    + hex.length() + "). A SHA-256 is always 64.");
        }
        return hex;
    }

    /**
     * The credential this workspace brought for a key, or empty to fall back to the
     * installation-wide one. A null workspace — a call serving with no API key — never has one.
     */
    public java.util.Optional<String> credentialFor(String workspaceId, String secretKey) {
        if (workspaceId == null || secretKey == null) {
            return java.util.Optional.empty();
        }
        Map<String, String> own = credentialsByWorkspace.get(workspaceId);
        return own == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(own.get(secretKey));
    }

    /**
     * Every {@code Workspace.metadata} key an operator can set here that nothing in this build reads.
     *
     * <p>The map is free-form on purpose, so an unrecognised key is not an error and must not be
     * refused: a configuration file written for a build that has these features should not be
     * stopped at the door. But a key that looks like a setting and does nothing reads as configured,
     * so it is named at startup. The warning is driven by the raw map rather than by any parser, so
     * it covers a key whether or not a class here has heard of it.
     */
    private static final List<String> SETTINGS_WITH_NO_CONSUMER_HERE = List.of(
            // Admission, budget pressure and per-call cost.
            "priority-tier",
            "cost.per-call-max-usd",
            "cost.downgrade-threshold-pct",
            "cost.downgrade-rules",
            "cost.anomaly-threshold-pct",
            "budget.cold-start-posture",
            // Human approval gates.
            "approval.required-tools",
            "approval.required-servers",
            "approval.required-skills",
            "approval.required-agents",
            "approval.default-action",
            "approval.timeout-seconds",
            // Agentic loop detection.
            "agentic.loop-detection.enabled",
            "agentic.loop-detection.auto-kill",
            "agentic.loop-detection.repetition-threshold",
            "agentic.loop-detection.max-calls-per-minute",
            // Per-workspace IP allow and deny lists.
            "ip-access.allowlist",
            "ip-access.denylist",
            // Prompt storage and audit archiving, and the strict-BYOK requirement.
            "audit.store-prompts",
            "audit.archive.enabled",
            "audit.archive.retention-days",
            "credentials.require-workspace-credential");

    /**
     * Says once, at startup, which configured workspace settings this build will not act on.
     *
     * <p>A warning rather than a refusal, which is the same posture a bootstrap file naming an
     * unseedable store gets: the setting is never silently ignored, and the boot is never refused
     * over one.
     *
     * <p>It names the workspaces as well as the keys. "priority-tier does nothing here" is a fact
     * about the build; "priority-tier on acme does nothing here" is a fact about a decision somebody
     * made, which is the one worth acting on.
     */
    private static void warnAboutSettingsNothingHereReads(Map<String, Workspace> workspaces) {
        Map<String, List<String>> byKey = new LinkedHashMap<>();
        workspaces.forEach((id, workspace) -> {
            Map<String, Object> metadata = workspace.getMetadata();
            if (metadata == null || metadata.isEmpty()) {
                return;
            }
            for (String key : SETTINGS_WITH_NO_CONSUMER_HERE) {
                if (metadata.containsKey(key)) {
                    byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(id);
                }
            }
        });
        if (byKey.isEmpty()) {
            return;
        }
        StringBuilder detail = new StringBuilder();
        byKey.forEach((key, ids) -> detail.append("\n  ").append(key).append(" — ").append(ids));
        log.warn("These workspace settings are configured and NOTHING IN THIS BUILD READS THEM, so "
                + "they will not take effect: they are parsed and then acted on by no one here, "
                + "because the engines that consume them are not present. Remove them, or run a "
                + "distribution of this gateway that has those engines.{}", detail);
    }
}
