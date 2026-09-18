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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static utility for loading and validating {@code gateway.yaml} configuration files.
 * <p>
 * Locates the file via the {@code DVARA_CONFIG_FILE} env var or {@code ./gateway.yaml},
 * resolves {@code ${ENV_VAR}} and {@code ${ENV_VAR:-default}} references, parses with
 * Jackson YAML, and validates the schema.
 */
public final class GatewayYamlLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final Pattern ENV_REF = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-(.*?))?}");
    /** The reserved shape of a legacy API-key display prefix, which a key name must not take. */
    private static final Pattern LEGACY_API_KEY_ID = Pattern.compile("gw_[0-9a-f]{8}");

    private GatewayYamlLoader() {}

    /**
     * Loads and parses the gateway.yaml file using system env vars for resolution.
     *
     * @return parsed config, or empty if no config file exists
     */
    public static Optional<GatewayYamlConfig> load() {
        return load(System::getenv);
    }

    /**
     * Loads and parses the gateway.yaml file using the given env resolver.
     *
     * @param envResolver function to resolve env var names to values (for testability)
     * @return parsed config, or empty if no config file exists
     */
    public static Optional<GatewayYamlConfig> load(Function<String, String> envResolver) {
        Path path = resolveConfigPath(envResolver);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            String raw = Files.readString(path);
            String resolved = resolveEnvVars(raw, envResolver);
            GatewayYamlConfig config = YAML_MAPPER.readValue(resolved, GatewayYamlConfig.class);
            return Optional.of(config);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse gateway.yaml at " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Validates a parsed config and returns human-readable error messages.
     *
     * @param config the parsed config
     * @return list of validation errors (empty means valid)
     */
    public static List<String> validate(GatewayYamlConfig config) {
        List<String> errors = new ArrayList<>();
        Set<String> servedWorkspaces = workspacesThisFileServes(config);

        // Refused by name rather than ignored: a key is always required, and a line that claims to
        // switch that off must not sit in a file that otherwise starts cleanly.
        if (config.getRequireApiKey() != null) {
            errors.add("require_api_key: not a setting. Every request under /v1 carries an API key;"
                    + " the setting that once allowed keyless requests was removed in 1.8.0. Remove"
                    + " the line, and mint keys for your callers with --generate-key.");
        }

        if (config.getProviders() != null) {
            for (int i = 0; i < config.getProviders().size(); i++) {
                GatewayYamlConfig.ProviderEntry p = config.getProviders().get(i);
                String prefix = "providers[" + i + "]";
                if (p.getType() == null || p.getType().isBlank()) {
                    errors.add(prefix + ".type: required field is missing");
                } else {
                    YamlProviderType type = YamlProviderType.of(p.getType()).orElse(null);
                    if (type == null) {
                        errors.add(prefix + ".type: unsupported provider type '" + p.getType()
                                + "' (valid: " + YamlProviderType.namesForMessage() + ")");
                    } else {
                        for (String problem : type.problems(p)) {
                            errors.add(prefix + "." + problem);
                        }
                    }
                }
            }
        }

        if (config.getRoutes() != null) {
            for (int i = 0; i < config.getRoutes().size(); i++) {
                GatewayYamlConfig.RouteEntry r = config.getRoutes().get(i);
                String prefix = "routes[" + i + "]";
                if (r.getModel() == null || r.getModel().isBlank()) {
                    errors.add(prefix + ".model: required field is missing");
                }
                if (r.getProvider() == null && (r.getProviders() == null || r.getProviders().isEmpty())) {
                    errors.add(prefix + ": must specify either 'provider' or 'providers'");
                }
                if (r.getStrategy() != null && !isValidStrategy(r.getStrategy())) {
                    errors.add(prefix + ".strategy: unsupported strategy '" + r.getStrategy()
                            + "' (valid in this build: model-prefix, round-robin, weighted, canary)");
                }
            }
        }

        if (config.getWorkspaces() != null) {
            for (int i = 0; i < config.getWorkspaces().size(); i++) {
                GatewayYamlConfig.WorkspaceEntry w = config.getWorkspaces().get(i);
                String prefix = "workspaces[" + i + "]";
                String idProblem = com.dvarahq.core.workspace.WorkspaceIds.rejectionReason(w.getId());
                if (idProblem != null) {
                    errors.add(prefix + ".id: " + idProblem);
                }
                if (w.getStatus() != null && !isValidWorkspaceStatus(w.getStatus())) {
                    errors.add(prefix + ".status: unsupported status '" + w.getStatus()
                            + "' (valid: ACTIVE, SUSPENDED)");
                }
                if (w.getCredentials() != null) {
                    w.getCredentials().forEach((secretKey, value) -> {
                        if (secretKey == null || secretKey.isBlank()) {
                            errors.add(prefix + ".credentials: a credential needs a key, e.g. "
                                    + "'provider.openai.api-key'");
                            return;
                        }
                        // A key that names no provider field is the likely typo and the one that
                        // fails invisibly: it is simply never looked up, and the workspace quietly
                        // keeps using the installation-wide credential it meant to replace.
                        if (!secretKey.startsWith("provider.")) {
                            errors.add(prefix + ".credentials." + secretKey + ": a credential key "
                                    + "names a provider field, e.g. 'provider.openai.api-key' — this "
                                    + "one would never be looked up, and the workspace would go on "
                                    + "using the installation-wide credential");
                        }
                        if (value == null || value.isBlank()) {
                            errors.add(prefix + ".credentials." + secretKey + ": empty. Remove the "
                                    + "entry to use the installation-wide credential, or set it — an "
                                    + "unresolved ${VAR} arrives blank and reads like a value");
                        }
                    });
                }
            }
        }

        if (config.getPolicies() != null) {
            for (int i = 0; i < config.getPolicies().size(); i++) {
                GatewayYamlConfig.PolicyEntry p = config.getPolicies().get(i);
                String prefix = "policies[" + i + "]";
                if (p.getId() == null || p.getId().isBlank()) {
                    errors.add(prefix + ".id: required field is missing");
                }
                if (p.getDsl() == null || p.getDsl().isBlank()) {
                    errors.add(prefix + ".dsl: required field is missing");
                }
                if (p.getStatus() != null && !isValidPolicyStatus(p.getStatus())) {
                    errors.add(prefix + ".status: unsupported status '" + p.getStatus()
                            + "' (valid: ACTIVE; shadow evaluation is not part of this build)");
                }
                reportWorkspace(errors, prefix + ".workspace", p.getWorkspace(), servedWorkspaces);
            }
        }

        if (config.getOutputSchemas() != null) {
            for (int i = 0; i < config.getOutputSchemas().size(); i++) {
                GatewayYamlConfig.OutputSchemaEntry o = config.getOutputSchemas().get(i);
                String prefix = "output_schemas[" + i + "]";
                if (o.getId() == null || o.getId().isBlank()) {
                    errors.add(prefix + ".id: required field is missing");
                }
                if (o.getSchema() == null || o.getSchema().isEmpty()) {
                    errors.add(prefix + ".schema: required field is missing");
                }
                // The gateway refuses this at the API with INVALID_OUTPUT_SCHEMA_SCOPE. A schema
                // scoped to neither a model nor a route matches nothing, so accepting it here would
                // let a file express what every other surface rejects — and it would look configured.
                if (blank(o.getModel()) && blank(o.getRoute())) {
                    errors.add(prefix + ": must specify 'model', 'route' or both — a schema scoped to "
                            + "neither would never match a request");
                }
            }
        }

        if (config.getPromptTemplates() != null) {
            for (int i = 0; i < config.getPromptTemplates().size(); i++) {
                GatewayYamlConfig.PromptTemplateEntry t = config.getPromptTemplates().get(i);
                String prefix = "prompt_templates[" + i + "]";
                if (blank(t.getId())) {
                    errors.add(prefix + ".id: required field is missing");
                }
                if (blank(t.getTemplate())) {
                    errors.add(prefix + ".template: required field is missing");
                }
                if (t.getStatus() != null && !isValidTemplateStatus(t.getStatus())) {
                    errors.add(prefix + ".status: unsupported status '" + t.getStatus()
                            + "' (valid: DRAFT, ACTIVE, ARCHIVED)");
                }
                reportWorkspace(errors, prefix + ".workspace", t.getWorkspace(), servedWorkspaces);
                // Something brace-shaped that is not a placeholder is not extracted as a variable,
                // not substituted at render, and delivered to the model as literal braces — so the
                // author's first evidence is a customer reading it in a reply. Refused here, where
                // the author is.
                reportUnrecognisedPlaceholders(errors, prefix + ".system", t.getSystem());
                reportUnrecognisedPlaceholders(errors, prefix + ".template", t.getTemplate());
            }
        }

        if (config.getApiKeys() != null) {
            java.util.Map<String, Integer> firstKeyNamed = new java.util.HashMap<>();
            for (int i = 0; i < config.getApiKeys().size(); i++) {
                GatewayYamlConfig.ApiKeyEntry a = config.getApiKeys().get(i);
                String prefix = "api_keys[" + i + "]";
                // a scope nothing recognises would grant nothing at request time, so a typo
                // would silently narrow the key; refuse it here, where the operator can read the message.
                if (a.getScopes() != null) {
                    for (String scope : a.getScopes()) {
                        if (!com.dvarahq.core.apikey.ApiKeyScope.isKnown(scope)) {
                            errors.add(prefix + ".scopes: unknown scope '" + scope + "' (valid: "
                                    + java.util.Arrays.stream(com.dvarahq.core.apikey.ApiKeyScope.values())
                                            .map(com.dvarahq.core.apikey.ApiKeyScope::value).collect(java.util.stream.Collectors.joining(", "))
                                    + "; omit scopes for an unrestricted key)");
                        }
                    }
                }
                // Reported here as well as refused by the store, so `validate` names every
                // offending entry at once rather than one per boot.
                if (a.getKey() != null && !a.getKey().isBlank()) {
                    errors.add(prefix + ": 'key' is no longer accepted — a configuration file should not"
                            + " hold a live credential. Fingerprint it with --hash-key - (the key on"
                            + " standard input) or --hash-key-file <path>, and use 'key_hash'. Your"
                            + " callers keep the same key.");
                } else if (a.isGenerate()) {
                    errors.add(prefix + ": 'generate: true' is no longer accepted — it minted a key that"
                            + " no restart survived and that two replicas disagreed about. Mint one"
                            + " with --generate-key and put its hash in 'key_hash'.");
                } else if (a.getKeyHash() == null || a.getKeyHash().isBlank()) {
                    errors.add(prefix + ": must specify 'key_hash'");
                }
                // A key may name a workspace with no block of its own, and the store then synthesizes
                // one whose id IS that string — a second verbatim way in, and the one nobody would
                // think to look at, since it is on an api_keys entry rather than a workspaces one.
                if (a.getWorkspace() != null && !a.getWorkspace().isBlank()) {
                    String problem = com.dvarahq.core.workspace.WorkspaceIds.rejectionReason(a.getWorkspace());
                    if (problem != null) {
                        errors.add(prefix + ".workspace: " + problem);
                    }
                }
                if (a.getName() != null && LEGACY_API_KEY_ID.matcher(a.getName()).matches()) {
                    errors.add(prefix + ".name: must not have the reserved legacy API-key prefix shape "
                            + "'gw_<8 lowercase hex characters>'");
                }
                // The name is the key's id, and this one is what the log and the limiter call a request
                // that carried no key at all; a key with it would be indistinguishable from none.
                if ("unauthenticated".equals(a.getName())) {
                    errors.add(prefix + ".name: 'unauthenticated' is reserved for a request that carries no key");
                }
                // A named key's name is its id: the rate limiter's bucket, the api_key value on the
                // access log and on usage and cost rows, and what a lookup by id returns. Two keys with
                // one name would share one budget and one record while the file looked correct.
                if (a.getName() != null && !a.getName().isBlank()) {
                    Integer first = firstKeyNamed.putIfAbsent(a.getName(), i);
                    if (first != null) {
                        errors.add(prefix + ".name: '" + a.getName() + "' is already the name of api_keys["
                                + first + "]. A key's name is its id, so the two keys would share one "
                                + "rate-limit budget and be recorded under one api_key value. Give each key "
                                + "its own name.");
                    }
                }
            }
        }

        return errors;
    }

    /**
     * Resolves the config file path from env var or default. Reads {@code DVARA_CONFIG_FILE} first,
     * then the older {@code GATEWAY_CONFIG_FILE}, which is still accepted and not warned about here.
     *
     * <p>Public because the file store's bootstrap-file check compares against it.
     */
    public static Path resolveConfigPath(Function<String, String> envResolver) {
        String envPath = envResolver.apply("DVARA_CONFIG_FILE");
        if (envPath == null || envPath.isBlank()) {
            envPath = envResolver.apply("GATEWAY_CONFIG_FILE");
        }
        if (envPath != null && !envPath.isBlank()) {
            return Path.of(envPath);
        }
        return Path.of("gateway.yaml");
    }

    /**
     * Resolves {@code ${ENV_VAR}} and {@code ${ENV_VAR:-default}} references in the input string.
     *
     * <p>As in a shell, {@code ${ENV_VAR:-default}} uses the default when the variable is unset
     * <em>or</em> set but empty. A compose file writing {@code VAR: ${VAR:-}} is the usual way to
     * produce an empty variable, and taking that empty value would silently leave the field unset.
     * {@code ${ENV_VAR}} with no default resolves an empty or unset variable to an empty string.
     *
     * <p>Public because {@code BootstrapLoader} reads a different file with the same interpolation
     * rules and should not carry a second copy of them.
     */
    public static String resolveEnvVars(String input, Function<String, String> envResolver) {
        Matcher matcher = ENV_REF.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            String defaultValue = matcher.group(2);
            String resolved = envResolver.apply(varName);
            if (resolved == null || (resolved.isEmpty() && defaultValue != null)) {
                resolved = defaultValue != null ? defaultValue : "";
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * The workspace ids a request can carry when this file is the configuration: those declared under
     * {@code workspaces}, those an API key names, and {@code default}, which the store gives a key that
     * names none ({@code YamlConfigStore.DEFAULT_WORKSPACE}).
     */
    private static Set<String> workspacesThisFileServes(GatewayYamlConfig config) {
        Set<String> served = new HashSet<>();
        if (config.getWorkspaces() != null) {
            for (GatewayYamlConfig.WorkspaceEntry w : config.getWorkspaces()) {
                if (!blank(w.getId())) {
                    served.add(w.getId());
                }
            }
        }
        if (config.getApiKeys() != null) {
            for (GatewayYamlConfig.ApiKeyEntry a : config.getApiKeys()) {
                served.add(blank(a.getWorkspace()) ? "default" : a.getWorkspace());
            }
        }
        return served;
    }

    /**
     * A policy or prompt template may be scoped to a workspace. The id must be usable, and it must be one
     * this file serves: a typo scopes the entry to a workspace no request carries, so it applies to
     * nothing while the boot log still counts it as loaded. No workspace means every workspace.
     */
    private static void reportWorkspace(List<String> errors, String field, String workspace, Set<String> served) {
        if (blank(workspace)) {
            return;
        }
        String problem = com.dvarahq.core.workspace.WorkspaceIds.rejectionReason(workspace);
        if (problem != null) {
            errors.add(field + ": " + problem);
            return;
        }
        if (!served.contains(workspace)) {
            errors.add(field + ": no workspace '" + workspace + "' is declared under workspaces or named by "
                    + "an API key, so no request carries it and this entry would apply to nothing. Check the "
                    + "spelling, or declare the workspace.");
        }
    }

    /**
     * A placeholder is {@code {{name}}} — word characters, with any surrounding whitespace ignored.
     * Anything else between double braces is named here rather than passed through.
     */
    private static void reportUnrecognisedPlaceholders(List<String> errors, String field, String template) {
        for (String bad : com.dvarahq.core.prompt.PromptTemplateRenderer.unrecognisedPlaceholders(template)) {
            errors.add(field + ": '" + bad + "' is not a variable — a variable is {{name}}, letters, "
                    + "digits and underscores, and spaces inside the braces are fine. As written it "
                    + "would be sent to the model exactly as it appears, inside the prompt.");
        }
    }

    private static boolean isValidTemplateStatus(String status) {
        return switch (status.toUpperCase()) {
            case "DRAFT", "ACTIVE", "ARCHIVED" -> true;
            default -> false;
        };
    }

    /**
     * DRAFT and ARCHIVED are rejected rather than accepted and ignored. Nothing filters inactive
     * policies out of a file, so an inert status would mean writing a policy and having it silently
     * not apply. To switch a policy off, delete it or comment it out.
     */
    private static boolean isValidPolicyStatus(String status) {
        return switch (status.toUpperCase()) {
            case "ACTIVE" -> true;
            default -> false;
        };
    }

    private static boolean isValidWorkspaceStatus(String status) {
        return switch (status.toUpperCase()) {
            case "ACTIVE", "SUSPENDED" -> true;
            default -> false;
        };
    }

    /**
     * The strategies a route may name in this build.
     *
     * <p>This list must stay in step with the route mapper, {@code YamlRouteRepository.toStrategy}.
     * Validation runs before the mapper and fails the boot, so a strategy the mapper understands but
     * this list leaves out cannot be configured at all, and the error would call it unsupported.
     */
    private static boolean isValidStrategy(String strategy) {
        return switch (strategy.toLowerCase()) {
            case "model-prefix", "round-robin", "weighted", "canary" -> true;
            default -> false;
        };
    }
}
