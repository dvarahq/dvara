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
package com.dvarahq.autoconfigure.config.bootstrap;

import com.dvarahq.autoconfigure.config.yaml.GatewayYamlLoader;
import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyGenerator;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.core.apikey.ApiKeyStatus;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.routing.Route;
import com.dvarahq.core.routing.RouteConfig;
import com.dvarahq.core.routing.RouteRepository;
import com.dvarahq.core.routing.RoutingEngine;
import com.dvarahq.core.routing.RoutingStrategyFactory;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceIds;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.WorkspaceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Seeds workspaces, API keys, and routes from a {@code bootstrap.yaml} file into repositories that
 * accept writes. It runs on every start while the file is set, so each entry that already exists
 * is skipped: a workspace or route by id, a static API key by its hash, and a generated API key by
 * its workspace and name. {@link BootstrapLoaderAutoConfiguration} registers it, and leaves it out
 * of a deployment configured from {@code gateway.yaml}, where every repository is read-only.
 * <p>
 * Enabled by setting the {@code DVARA_BOOTSTRAP_FILE} env var to the file path. The legacy
 * {@code GATEWAY_BOOTSTRAP_FILE} name is read as a fallback.
 * <p>
 * It lives beside the configuration stores rather than on the request path, so an application that
 * stores configuration without serving requests can seed it too. Such an application has no routing
 * engine; the routes are seeded and the routing-table update is skipped, since nothing there routes.
 */
public class BootstrapLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapLoader.class);
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private final WorkspaceRepository workspaceRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final RouteRepository routeRepository;
    private final RoutingEngine routingEngine;
    private final RoutingStrategyFactory strategyFactory;
    private final Environment environment;

    /**
     * @param routingEngine   the routing table to update after seeding routes, or null where no
     *                        requests are routed
     * @param strategyFactory builds each route's strategy for that table; null alongside a null engine
     */
    public BootstrapLoader(WorkspaceRepository workspaceRepository,
                           ApiKeyRepository apiKeyRepository,
                           RouteRepository routeRepository,
                           RoutingEngine routingEngine,
                           RoutingStrategyFactory strategyFactory,
                           Environment environment) {
        this.workspaceRepository = workspaceRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.routeRepository = routeRepository;
        this.routingEngine = routingEngine;
        this.strategyFactory = strategyFactory;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Canonical name first, then the legacy GATEWAY_* name as a fallback.
        String bootstrapFile = environment.getProperty("DVARA_BOOTSTRAP_FILE");
        if (bootstrapFile == null || bootstrapFile.isBlank()) {
            bootstrapFile = environment.getProperty("GATEWAY_BOOTSTRAP_FILE");
        }
        if (bootstrapFile == null || bootstrapFile.isBlank()) {
            return;
        }

        Path path = Path.of(bootstrapFile);
        if (!Files.exists(path)) {
            log.warn("Bootstrap file not found: {}", path.toAbsolutePath());
            return;
        }

        log.info("Loading bootstrap config from: {}", path.toAbsolutePath());

        try {
            String raw = Files.readString(path);
            String resolved = GatewayYamlLoader.resolveEnvVars(raw,
                    name -> environment.getProperty(name));
            BootstrapConfig config = YAML_MAPPER.readValue(resolved, BootstrapConfig.class);
            seed(config);
        } catch (IOException e) {
            log.warn("Failed to parse bootstrap file {}: {}", path, e.getMessage());
        }
    }

    private void seed(BootstrapConfig config) {
        int tenantsCreated = 0, tenantsSkipped = 0, tenantsFailed = 0;
        int keysCreated = 0, keysSkipped = 0, keysFailed = 0;
        int routesCreated = 0, routesSkipped = 0, routesFailed = 0;
        List<String> generatedKeys = new ArrayList<>();
        boolean seeded = false;

        // Seed workspaces
        if (config.getWorkspaces() != null) {
            for (BootstrapConfig.WorkspaceEntry entry : config.getWorkspaces()) {
                try {
                    if (entry.getId() != null && workspaceRepository.findById(entry.getId()).isPresent()) {
                        tenantsSkipped++;
                        log.debug("Skipping workspace '{}' — already exists", entry.getId());
                        continue;
                    }
                    seedWorkspace(entry);
                    tenantsCreated++;
                    seeded = true;
                } catch (Exception e) {
                    tenantsFailed++;
                    log.warn("Failed to seed workspace '{}' (id={}): {}",
                            entry.getName(), entry.getId(), e.getMessage());
                }
            }
        }

        // Seed API keys
        if (config.getApiKeys() != null) {
            for (BootstrapConfig.ApiKeyEntry entry : config.getApiKeys()) {
                try {
                    String result = seedApiKey(entry, generatedKeys);
                    if ("created".equals(result)) {
                        keysCreated++;
                        seeded = true;
                    } else {
                        keysSkipped++;
                    }
                } catch (Exception e) {
                    keysFailed++;
                    log.warn("Failed to seed API key '{}': {}", entry.getName(), e.getMessage());
                }
            }
        }

        // Seed routes
        if (config.getRoutes() != null) {
            for (BootstrapConfig.RouteEntry entry : config.getRoutes()) {
                try {
                    if (entry.getId() != null && routeRepository.findById(entry.getId()).isPresent()) {
                        routesSkipped++;
                        log.debug("Skipping route '{}' — already exists", entry.getId());
                        continue;
                    }
                    seedRoute(entry);
                    routesCreated++;
                    seeded = true;
                } catch (Exception e) {
                    routesFailed++;
                    log.warn("Failed to seed route '{}': {}", entry.getId(), e.getMessage());
                }
            }
        }

        // Print generated keys banner
        if (!generatedKeys.isEmpty()) {
            printGeneratedKeysBanner(generatedKeys);
        }

        // Propagate routes and bump version
        if (seeded) {
            propagateRoutes();
        }

        // Log summary
        log.info("Bootstrap seeding complete — workspaces: {} created, {} skipped, {} failed; "
                        + "API keys: {} created, {} skipped, {} failed; "
                        + "routes: {} created, {} skipped, {} failed",
                tenantsCreated, tenantsSkipped, tenantsFailed,
                keysCreated, keysSkipped, keysFailed,
                routesCreated, routesSkipped, routesFailed);
    }

    private void seedWorkspace(BootstrapConfig.WorkspaceEntry entry) {
        // An id from the file is stored verbatim and becomes part of the credential cache key, the
        // guardrail plugin key and the audit record, so it is validated here. Refused per entry:
        // the loop counts the failure and carries on with the rest of the file.
        if (entry.getId() != null) {
            WorkspaceIds.require(entry.getId());
        }
        Instant now = Instant.now();
        WorkspaceStatus status = parseWorkspaceStatus(entry.getStatus());

        Workspace workspace = Workspace.builder()
                .id(entry.getId() != null ? entry.getId() : Ids.newId())
                .name(entry.getName() != null ? entry.getName() : entry.getId())
                .status(status)
                .region(entry.getRegion())
                .metadata(Map.of())
                .createdAt(now)
                .updatedAt(now)
                .build();

        workspaceRepository.save(workspace);
        log.info("Seeded workspace '{}' (id={})", workspace.getName(), workspace.getId());
    }

    private String seedApiKey(BootstrapConfig.ApiKeyEntry entry, List<String> generatedKeys) {
        String workspaceId = resolveWorkspaceId(entry.getWorkspace());
        if (workspaceId == null) {
            log.warn("Skipping API key '{}' — workspace '{}' not found", entry.getName(), entry.getWorkspace());
            return "skipped";
        }

        String name = entry.getName() != null ? entry.getName() : "bootstrap-key";
        String plaintext;
        if (entry.isGenerate()) {
            // A generated key has no value in the file to recognise it by, so its workspace and name are
            // its identity; otherwise every restart would mint another working key. A revoked key of
            // that name counts too: a restart must not undo a revocation by minting a replacement.
            if (apiKeyRepository.findByWorkspaceId(workspaceId).stream()
                    .anyMatch(existing -> name.equals(existing.getName()))) {
                log.debug("Skipping API key '{}' for workspace '{}' — already exists", name, entry.getWorkspace());
                return "skipped";
            }
            plaintext = ApiKeyGenerator.generatePlaintext();
            String label = (entry.getName() != null ? entry.getName() : "key")
                    + " (" + (entry.getWorkspace() != null ? entry.getWorkspace() : "default") + ")";
            generatedKeys.add(label + ": " + plaintext);
        } else {
            plaintext = entry.getKey();
        }

        if (plaintext == null || plaintext.isBlank()) {
            log.warn("Skipping API key entry with no key value (name={})", entry.getName());
            return "skipped";
        }

        String keyHash = ApiKeyGenerator.hash(plaintext);
        if (apiKeyRepository.findByKeyHash(keyHash).isPresent()) {
            // Saving it again would break the unique index on the hash and log a failure for a key that is fine.
            log.debug("Skipping API key '{}' — a key with that value already exists", name);
            return "skipped";
        }
        String keyPrefix = ApiKeyGenerator.extractPrefix(plaintext);
        Instant now = Instant.now();

        ApiKey apiKey = ApiKey.builder()
                .id(Ids.newId())
                .workspaceId(workspaceId)
                .name(name)
                .keyPrefix(keyPrefix)
                .keyHash(keyHash)
                .scopes(entry.getScopes() != null ? entry.getScopes() : List.of())   // no scopes = unrestricted
                .status(ApiKeyStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        apiKeyRepository.save(apiKey);
        log.info("Seeded API key '{}' for workspace '{}'", apiKey.getName(), entry.getWorkspace());
        warnAboutScopesNoV1EndpointRecognises(apiKey);
        return "created";
    }

    /**
     * Warns about each scope value no /v1 endpoint recognises. Such a value grants nothing on a /v1 request, so a key
     * carrying only unknown values is refused on every /v1 endpoint. A warning rather than a refusal, because another
     * module may recognise scope values of its own on the same key.
     */
    private void warnAboutScopesNoV1EndpointRecognises(ApiKey apiKey) {
        if (apiKey.getScopes() == null) {
            return;
        }
        for (String scope : apiKey.getScopes()) {
            if (!com.dvarahq.core.apikey.ApiKeyScope.isKnown(scope)) {
                log.warn("API key '{}' has scope '{}', which no /v1 endpoint recognises (valid: {}). It grants nothing "
                                + "on a /v1 request; omit scopes for an unrestricted key.",
                        apiKey.getName(), scope,
                        java.util.Arrays.stream(com.dvarahq.core.apikey.ApiKeyScope.values())
                                .map(com.dvarahq.core.apikey.ApiKeyScope::value)
                                .collect(java.util.stream.Collectors.joining(", ")));
            }
        }
    }

    private void seedRoute(BootstrapConfig.RouteEntry entry) {
        String routeId = entry.getId() != null ? entry.getId() : Ids.newId();
        Instant now = Instant.now();

        List<Route.RouteProvider> providers = buildRouteProviders(entry);
        String strategy = entry.getStrategy() != null ? entry.getStrategy() : "model-prefix";

        Route route = Route.builder()
                .id(routeId)
                .modelPattern(entry.getModel())
                .strategy(strategy)
                .providers(providers)
                .pinnedModelVersion(entry.getPinnedModelVersion())
                .version(1)
                .createdAt(now)
                .updatedAt(now)
                .build();

        routeRepository.save(route);
        log.info("Seeded route '{}' for model pattern '{}'", routeId, entry.getModel());
    }

    private List<Route.RouteProvider> buildRouteProviders(BootstrapConfig.RouteEntry entry) {
        List<Route.RouteProvider> providers = new ArrayList<>();

        if (entry.getProvider() != null) {
            providers.add(Route.RouteProvider.builder()
                    .provider(entry.getProvider())
                    .weight(1)
                    .build());
        }

        if (entry.getProviders() != null) {
            for (BootstrapConfig.RouteProviderEntry rp : entry.getProviders()) {
                providers.add(Route.RouteProvider.builder()
                        .provider(rp.getProvider())
                        .weight(rp.getWeight() != null ? rp.getWeight() : 1)
                        .build());
            }
        }

        if (entry.getFallback() != null && !entry.getFallback().isBlank()) {
            providers.add(Route.RouteProvider.builder()
                    .provider(entry.getFallback())
                    .weight(0)
                    .build());
        }

        return providers;
    }

    private String resolveWorkspaceId(String workspaceName) {
        if (workspaceName == null || workspaceName.isBlank()) {
            workspaceName = "default";
        }
        // Try to find by ID first
        if (workspaceRepository.findById(workspaceName).isPresent()) {
            return workspaceName;
        }
        // Try to find by name
        String finalWorkspaceName = workspaceName;
        return workspaceRepository.findAll().stream()
                .filter(t -> finalWorkspaceName.equals(t.getName()))
                .map(Workspace::getId)
                .findFirst()
                .orElseGet(() -> {
                    // Auto-create workspace
                    Instant now = Instant.now();
                    Workspace workspace = Workspace.builder()
                            .id(Ids.newId())
                            .name(finalWorkspaceName)
                            .status(WorkspaceStatus.ACTIVE)
                            .metadata(Map.of())
                            .createdAt(now)
                            .updatedAt(now)
                            .build();
                    workspaceRepository.save(workspace);
                    log.info("Auto-created workspace '{}' for API key", finalWorkspaceName);
                    return workspace.getId();
                });
    }

    private WorkspaceStatus parseWorkspaceStatus(String status) {
        if (status == null || status.isBlank()) {
            return WorkspaceStatus.ACTIVE;
        }
        try {
            return WorkspaceStatus.valueOf(status.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown workspace status '{}', defaulting to ACTIVE", status);
            return WorkspaceStatus.ACTIVE;
        }
    }

    private void propagateRoutes() {
        if (routingEngine == null || strategyFactory == null) {
            return; // nothing here routes requests; whatever serves them reads the seeded routes
        }
        List<Route> allRoutes = routeRepository.findAll();
        List<RoutingEngine.ResolvedRoute> resolved = allRoutes.stream()
                .map(this::toResolvedRoute)
                .collect(Collectors.toList());
        routingEngine.updateRoutes(resolved);
    }

    private RoutingEngine.ResolvedRoute toResolvedRoute(Route route) {
        RouteConfig.Strategy strategy = switch (route.getStrategy()) {
            case "round-robin" -> RouteConfig.Strategy.ROUND_ROBIN;
            case "weighted" -> RouteConfig.Strategy.WEIGHTED;
            case "latency-aware" -> RouteConfig.Strategy.LATENCY_AWARE;
            case "cost-aware" -> RouteConfig.Strategy.COST_AWARE;
            case "canary" -> RouteConfig.Strategy.CANARY;
            case "geo-aware" -> RouteConfig.Strategy.GEO_AWARE;
            case "intelligent" -> RouteConfig.Strategy.INTELLIGENT;
            default -> RouteConfig.Strategy.MODEL_PREFIX;
        };

        List<RouteConfig.ProviderWeight> weights = route.getProviders().stream()
                .map(p -> RouteConfig.ProviderWeight.builder()
                        .provider(p.getProvider())
                        .weight(p.getWeight())
                        .build())
                .collect(Collectors.toList());

        RouteConfig config = RouteConfig.builder()
                .id(route.getId())
                .modelPattern(route.getModelPattern())
                .strategy(strategy)
                .providers(weights)
                .pinnedModelVersion(route.getPinnedModelVersion())
                .costTolerancePct(route.getCostTolerancePct() != null ? route.getCostTolerancePct() : 0)
                .modelTiers(route.getModelTiers())
                .build();

        return new RoutingEngine.ResolvedRoute(config, strategyFactory.create(config));
    }

    private void printGeneratedKeysBanner(List<String> lines) {
        int contentWidth = lines.stream().mapToInt(String::length).max().orElse(40);
        contentWidth = Math.max(contentWidth, 48);
        int boxWidth = contentWidth + 4;
        String border = "\u2550".repeat(boxWidth);
        String headerText = "GENERATED API KEYS (bootstrap)";
        int headerPad = (contentWidth - headerText.length()) / 2;
        String warningText = "Save these keys! They will NOT be shown again.";

        StringBuilder banner = new StringBuilder();
        banner.append("\n");
        banner.append("\u2554").append(border).append("\u2557\n");
        banner.append("\u2551  ").append(" ".repeat(headerPad)).append(headerText)
                .append(" ".repeat(contentWidth - headerPad - headerText.length())).append("  \u2551\n");
        banner.append("\u2560").append(border).append("\u2563\n");
        for (String line : lines) {
            banner.append("\u2551  ").append(line)
                    .append(" ".repeat(contentWidth - line.length())).append("  \u2551\n");
        }
        banner.append("\u2560").append(border).append("\u2563\n");
        banner.append("\u2551  ").append(warningText)
                .append(" ".repeat(contentWidth - warningText.length())).append("  \u2551\n");
        banner.append("\u255a").append(border).append("\u255d");
        log.info(banner.toString());
    }
}