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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayYamlLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void load_fileNotFound_returnsEmpty() {
        Map<String, String> env = Map.of("GATEWAY_CONFIG_FILE", "/nonexistent/gateway.yaml");
        Optional<GatewayYamlConfig> result = GatewayYamlLoader.load(env::get);
        assertThat(result).isEmpty();
    }

    @Test
    void load_validFile_parsesProviders() throws IOException {
        Path configFile = tempDir.resolve("gateway.yaml");
        Files.writeString(configFile, """
                providers:
                  - name: openai
                    type: openai
                    api_key: sk-test-123
                  - name: local
                    type: ollama
                    base_url: http://localhost:11434
                """);

        Map<String, String> env = Map.of("GATEWAY_CONFIG_FILE", configFile.toString());
        Optional<GatewayYamlConfig> result = GatewayYamlLoader.load(env::get);

        assertThat(result).isPresent();
        GatewayYamlConfig config = result.get();
        assertThat(config.getProviders()).hasSize(2);
        assertThat(config.getProviders().get(0).getType()).isEqualTo("openai");
        assertThat(config.getProviders().get(0).getApiKey()).isEqualTo("sk-test-123");
        assertThat(config.getProviders().get(1).getType()).isEqualTo("ollama");
        assertThat(config.getProviders().get(1).getBaseUrl()).isEqualTo("http://localhost:11434");
    }

    @Test
    void load_parsesRoutes() throws IOException {
        Path configFile = tempDir.resolve("gateway.yaml");
        Files.writeString(configFile, """
                routes:
                  - id: gpt-route
                    model: "gpt*"
                    provider: openai
                  - id: balanced
                    model: "claude*"
                    strategy: weighted
                    providers:
                      - provider: anthropic
                        weight: 70
                      - provider: bedrock
                        weight: 30
                    fallback: openai
                """);

        Map<String, String> env = Map.of("GATEWAY_CONFIG_FILE", configFile.toString());
        GatewayYamlConfig config = GatewayYamlLoader.load(env::get).orElseThrow();

        assertThat(config.getRoutes()).hasSize(2);
        assertThat(config.getRoutes().get(0).getModel()).isEqualTo("gpt*");
        assertThat(config.getRoutes().get(0).getProvider()).isEqualTo("openai");
        assertThat(config.getRoutes().get(1).getStrategy()).isEqualTo("weighted");
        assertThat(config.getRoutes().get(1).getProviders()).hasSize(2);
        assertThat(config.getRoutes().get(1).getFallback()).isEqualTo("openai");
    }

    @Test
    void load_parsesApiKeys() throws IOException {
        Path configFile = tempDir.resolve("gateway.yaml");
        Files.writeString(configFile, """
                api_keys:
                  - key_hash: sha256:0000000000000000000000000000000000000000000000000000000000000001
                    workspace: production
                    scopes: [completions:write]
                  - key_hash: sha256:0000000000000000000000000000000000000000000000000000000000000002
                    workspace: development
                    name: dev-key
                """);

        Map<String, String> env = Map.of("GATEWAY_CONFIG_FILE", configFile.toString());
        GatewayYamlConfig config = GatewayYamlLoader.load(env::get).orElseThrow();

        assertThat(config.getApiKeys()).hasSize(2);
        assertThat(config.getApiKeys().get(0).getKeyHash())
                .isEqualTo("sha256:0000000000000000000000000000000000000000000000000000000000000001");
        assertThat(config.getApiKeys().get(0).getWorkspace()).isEqualTo("production");
        assertThat(config.getApiKeys().get(1).getName()).isEqualTo("dev-key");
        assertThat(config.getApiKeys().get(1).getName()).isEqualTo("dev-key");
    }

    @Test
    void load_parsesRateLimits() throws IOException {
        Path configFile = tempDir.resolve("gateway.yaml");
        Files.writeString(configFile, """
                rate_limits:
                  requests_per_minute: 600
                  tokens_per_minute: 100000
                """);

        Map<String, String> env = Map.of("GATEWAY_CONFIG_FILE", configFile.toString());
        GatewayYamlConfig config = GatewayYamlLoader.load(env::get).orElseThrow();

        assertThat(config.getRateLimits()).isNotNull();
        assertThat(config.getRateLimits().getRequestsPerMinute()).isEqualTo(600);
        assertThat(config.getRateLimits().getTokensPerMinute()).isEqualTo(100000);
    }

    // --- Env var resolution tests ---

    @Test
    void resolveEnvVars_simpleRef() {
        Function<String, String> env = name -> "openai".equals(name) ? "sk-abc" : null;
        String result = GatewayYamlLoader.resolveEnvVars("api_key: ${openai}", env);
        assertThat(result).isEqualTo("api_key: sk-abc");
    }

    @Test
    void resolveEnvVars_withDefault_envSet() {
        Function<String, String> env = name -> "MY_URL".equals(name) ? "http://custom:8080" : null;
        String result = GatewayYamlLoader.resolveEnvVars("url: ${MY_URL:-http://localhost:11434}", env);
        assertThat(result).isEqualTo("url: http://custom:8080");
    }

    @Test
    void resolveEnvVars_withDefault_envNotSet() {
        Function<String, String> env = name -> null;
        String result = GatewayYamlLoader.resolveEnvVars("url: ${MY_URL:-http://localhost:11434}", env);
        assertThat(result).isEqualTo("url: http://localhost:11434");
    }

    @Test
    void resolveEnvVars_withDefault_envSetButEmpty_usesTheDefault() {
        // As in a shell: a compose file writing VAR: ${VAR:-} sets the variable empty, and the file's
        // default must still apply rather than leaving the field blank.
        Function<String, String> env = name -> "MY_URL".equals(name) ? "" : null;
        assertThat(GatewayYamlLoader.resolveEnvVars("url: ${MY_URL:-http://localhost:11434}", env))
                .isEqualTo("url: http://localhost:11434");
        assertThat(GatewayYamlLoader.resolveEnvVars("url: ${MY_URL}", env))
                .as("with no default an empty variable stays empty").isEqualTo("url: ");
    }

    @Test
    void resolveEnvVars_missingEnvNoDefault_replacesWithEmpty() {
        Function<String, String> env = name -> null;
        String result = GatewayYamlLoader.resolveEnvVars("key: ${MISSING}", env);
        assertThat(result).isEqualTo("key: ");
    }

    @Test
    void resolveEnvVars_multipleRefs() {
        Map<String, String> envMap = Map.of("A", "val-a", "B", "val-b");
        String result = GatewayYamlLoader.resolveEnvVars("${A} and ${B}", envMap::get);
        assertThat(result).isEqualTo("val-a and val-b");
    }

    @Test
    void load_envVarResolution_inYaml() throws IOException {
        Path configFile = tempDir.resolve("gateway.yaml");
        Files.writeString(configFile, """
                providers:
                  - name: openai
                    type: openai
                    api_key: ${MY_API_KEY}
                  - name: ollama
                    type: ollama
                    base_url: ${OLLAMA_URL:-http://localhost:11434}
                """);

        Map<String, String> env = new HashMap<>();
        env.put("GATEWAY_CONFIG_FILE", configFile.toString());
        env.put("MY_API_KEY", "sk-resolved-key");
        GatewayYamlConfig config = GatewayYamlLoader.load(env::get).orElseThrow();

        assertThat(config.getProviders().get(0).getApiKey()).isEqualTo("sk-resolved-key");
        assertThat(config.getProviders().get(1).getBaseUrl()).isEqualTo("http://localhost:11434");
    }

    // --- Validation tests ---

    @Test
    void validate_missingProviderType_returnsError() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.ProviderEntry entry = new GatewayYamlConfig.ProviderEntry();
        config.setProviders(List.of(entry));

        List<String> errors = GatewayYamlLoader.validate(config);
        assertThat(errors).containsExactly("providers[0].type: required field is missing");
    }

    @Test
    void validate_unknownApiKeyScope_returnsError() {
        // a scope nothing recognises grants nothing at request time, so a typo would narrow
        // the key silently; it is refused here, where the operator can read the message.
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.ApiKeyEntry key = new GatewayYamlConfig.ApiKeyEntry();
        key.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000003");
        key.setWorkspace("production");
        key.setScopes(List.of("completions:write", "completion:write"));
        config.setApiKeys(List.of(key));

        List<String> errors = GatewayYamlLoader.validate(config);
        assertThat(errors).anyMatch(e -> e.startsWith("api_keys[0].scopes: unknown scope 'completion:write'")
                && e.contains("completions:write") && e.contains("embeddings:write")
                && e.contains("batches:write") && e.contains("models:read"));
        assertThat(errors).noneMatch(e -> e.contains("'completions:write'"));
    }

    @Test
    void validate_workspaceIdTheRestOfTheSystemCannotUse_returnsError() {
        // A workspace id is half of the credential cache key, the guardrail plugin key and the
        // signed audit record, and this file is one of the places an operator types one.
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.WorkspaceEntry sentinel = new GatewayYamlConfig.WorkspaceEntry();
        sentinel.setId("__platform__");
        GatewayYamlConfig.WorkspaceEntry separator = new GatewayYamlConfig.WorkspaceEntry();
        separator.setId("acme|evil");
        config.setWorkspaces(List.of(sentinel, separator));

        List<String> errors = GatewayYamlLoader.validate(config);
        assertThat(errors).anyMatch(e -> e.startsWith("workspaces[0].id:") && e.contains("no workspace"));
        assertThat(errors).anyMatch(e -> e.startsWith("workspaces[1].id:") && e.contains("acme|evil"));
    }

    @Test
    void validate_apiKeyNamingAnUnusableWorkspace_returnsError() {
        // A key may name a workspace with no block of its own; the store then synthesizes one whose
        // id is that string, so the same check applies here.
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.ApiKeyEntry key = new GatewayYamlConfig.ApiKeyEntry();
        key.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000003");
        key.setWorkspace("__platform__");
        config.setApiKeys(List.of(key));

        assertThat(GatewayYamlLoader.validate(config))
                .anyMatch(e -> e.startsWith("api_keys[0].workspace:") && e.contains("no workspace"));
    }

    @Test
    void validate_anOrdinaryWorkspaceId_isAccepted() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.WorkspaceEntry w = new GatewayYamlConfig.WorkspaceEntry();
        w.setId("e5-load");
        config.setWorkspaces(List.of(w));

        assertThat(GatewayYamlLoader.validate(config)).isEmpty();
    }

    @Test
    void validate_twoApiKeysWithTheSameName_returnsError() {
        // A named key's name is its id: two keys called prod would share one rate-limit bucket and be
        // recorded under one api_key value, with the file looking correct.
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.ApiKeyEntry first = new GatewayYamlConfig.ApiKeyEntry();
        first.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000001");
        first.setWorkspace("team-a");
        first.setName("prod");
        GatewayYamlConfig.ApiKeyEntry second = new GatewayYamlConfig.ApiKeyEntry();
        second.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000002");
        second.setWorkspace("team-b");
        second.setName("prod");
        config.setApiKeys(List.of(first, second));

        assertThat(GatewayYamlLoader.validate(config))
                .anyMatch(e -> e.startsWith("api_keys[1].name:") && e.contains("'prod'")
                        && e.contains("api_keys[0]"))
                .noneMatch(e -> e.startsWith("api_keys[0].name:"));
    }

    @Test
    void validate_distinctAndUnnamedApiKeys_areAccepted() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.ApiKeyEntry a = new GatewayYamlConfig.ApiKeyEntry();
        a.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000001");
        a.setName("prod");
        GatewayYamlConfig.ApiKeyEntry b = new GatewayYamlConfig.ApiKeyEntry();
        b.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000002");
        b.setName("staging");
        GatewayYamlConfig.ApiKeyEntry unnamed1 = new GatewayYamlConfig.ApiKeyEntry();
        unnamed1.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000003");
        GatewayYamlConfig.ApiKeyEntry unnamed2 = new GatewayYamlConfig.ApiKeyEntry();
        unnamed2.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000004");
        config.setApiKeys(List.of(a, b, unnamed1, unnamed2));

        assertThat(GatewayYamlLoader.validate(config)).isEmpty();
    }

    @Test
    void validate_policyOrTemplateScopedToAWorkspaceNothingServes_returnsError() {
        // A typo (acme-prd for acme-prod) scopes the entry to a workspace no request carries, so it
        // applies to nothing while the boot log still counts it as loaded.
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.WorkspaceEntry w = new GatewayYamlConfig.WorkspaceEntry();
        w.setId("acme-prod");
        config.setWorkspaces(List.of(w));
        GatewayYamlConfig.PolicyEntry policy = new GatewayYamlConfig.PolicyEntry();
        policy.setId("p");
        policy.setDsl("rules: []");
        policy.setWorkspace("acme-prd");
        config.setPolicies(List.of(policy));
        GatewayYamlConfig.PromptTemplateEntry typo = new GatewayYamlConfig.PromptTemplateEntry();
        typo.setId("t");
        typo.setTemplate("Hello");
        typo.setWorkspace("acme-prd");
        GatewayYamlConfig.PromptTemplateEntry unusable = new GatewayYamlConfig.PromptTemplateEntry();
        unusable.setId("u");
        unusable.setTemplate("Hello");
        unusable.setWorkspace("__platform__");
        config.setPromptTemplates(List.of(typo, unusable));

        assertThat(GatewayYamlLoader.validate(config))
                .anyMatch(e -> e.startsWith("policies[0].workspace:") && e.contains("'acme-prd'")
                        && e.contains("apply to nothing"))
                .anyMatch(e -> e.startsWith("prompt_templates[0].workspace:") && e.contains("'acme-prd'"))
                .anyMatch(e -> e.startsWith("prompt_templates[1].workspace:") && e.contains("no workspace"));
    }

    @Test
    void validate_policyAndTemplatesScopedToServedWorkspaces_areAccepted() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.WorkspaceEntry declared = new GatewayYamlConfig.WorkspaceEntry();
        declared.setId("acme");
        config.setWorkspaces(List.of(declared));
        GatewayYamlConfig.ApiKeyEntry namesAWorkspace = new GatewayYamlConfig.ApiKeyEntry();
        namesAWorkspace.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000001");
        namesAWorkspace.setWorkspace("beta");
        GatewayYamlConfig.ApiKeyEntry namesNone = new GatewayYamlConfig.ApiKeyEntry();
        namesNone.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000002");
        config.setApiKeys(List.of(namesAWorkspace, namesNone));
        GatewayYamlConfig.PolicyEntry policy = new GatewayYamlConfig.PolicyEntry();
        policy.setId("p");
        policy.setDsl("rules: []");
        policy.setWorkspace("acme");
        GatewayYamlConfig.PolicyEntry global = new GatewayYamlConfig.PolicyEntry();
        global.setId("g");
        global.setDsl("rules: []");
        config.setPolicies(List.of(policy, global));
        GatewayYamlConfig.PromptTemplateEntry keyed = new GatewayYamlConfig.PromptTemplateEntry();
        keyed.setId("t1");
        keyed.setTemplate("Hello");
        keyed.setWorkspace("beta");
        GatewayYamlConfig.PromptTemplateEntry defaulted = new GatewayYamlConfig.PromptTemplateEntry();
        defaulted.setId("t2");
        defaulted.setTemplate("Hello");
        defaulted.setWorkspace("default");
        config.setPromptTemplates(List.of(keyed, defaulted));

        assertThat(GatewayYamlLoader.validate(config)).isEmpty();
    }

    @Test
    void validate_promptTemplateWithBraceShapedTextThatIsNotAPlaceholder_returnsError() {
        // Left alone it is not extracted as a variable, not substituted, and delivered to the model
        // as literal braces — so the author's first evidence is a customer reading it in a reply.
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.PromptTemplateEntry t = new GatewayYamlConfig.PromptTemplateEntry();
        t.setId("greet");
        t.setTemplate("Hello {{user-name}}");
        config.setPromptTemplates(List.of(t));

        assertThat(GatewayYamlLoader.validate(config))
                .anyMatch(e -> e.startsWith("prompt_templates[0].template:")
                        && e.contains("{{user-name}}") && e.contains("not a variable"));
    }

    @Test
    void validate_promptTemplateWithSpacesInsideTheBraces_isAccepted() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.PromptTemplateEntry t = new GatewayYamlConfig.PromptTemplateEntry();
        t.setId("greet");
        t.setTemplate("Hello {{ name }}");
        config.setPromptTemplates(List.of(t));

        assertThat(GatewayYamlLoader.validate(config)).isEmpty();
    }

    @Test
    void validate_invalidProviderType_returnsError() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.ProviderEntry entry = new GatewayYamlConfig.ProviderEntry();
        entry.setType("invalid");
        config.setProviders(List.of(entry));

        List<String> errors = GatewayYamlLoader.validate(config);
        assertThat(errors).anyMatch(e -> e.contains("unsupported provider type 'invalid'")
                && e.contains("(valid: " + YamlProviderType.namesForMessage() + ")"));
    }

    /** Every provider type in the table is accepted, in either case, with a base_url where the provider reads one. */
    @Test
    void validate_everyShippedProviderType_isAccepted() {
        for (YamlProviderType type : YamlProviderType.values()) {
            GatewayYamlConfig config = new GatewayYamlConfig();
            GatewayYamlConfig.ProviderEntry entry = new GatewayYamlConfig.ProviderEntry();
            entry.setType(type.type().toUpperCase());
            entry.setApiKey("k");
            if (type.readsBaseUrl()) entry.setBaseUrl("https://example.invalid/v1");
            config.setProviders(List.of(entry));
            assertThat(GatewayYamlLoader.validate(config)).as(type.type()).isEmpty();
        }
    }

    /** A base_url on a provider whose endpoint is fixed would map into a property nothing reads; refused instead. */
    @Test
    void validate_baseUrlOnAFixedEndpointProvider_isRefused() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.ProviderEntry entry = new GatewayYamlConfig.ProviderEntry();
        entry.setType("anthropic");
        entry.setApiKey("k");
        entry.setBaseUrl("https://proxy.example");
        config.setProviders(List.of(entry));
        assertThat(GatewayYamlLoader.validate(config))
                .containsExactly("providers[0].base_url: not read by anthropic, whose endpoint is fixed");
        entry.setBaseUrl(null);
        assertThat(GatewayYamlLoader.validate(config)).isEmpty();
    }

    @Test
    void validate_missingRouteModel_returnsError() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.RouteEntry route = new GatewayYamlConfig.RouteEntry();
        route.setProvider("openai");
        config.setRoutes(List.of(route));

        List<String> errors = GatewayYamlLoader.validate(config);
        assertThat(errors).anyMatch(e -> e.contains("routes[0].model: required field is missing"));
    }

    @Test
    void validate_routeWithNoProviderOrProviders_returnsError() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.RouteEntry route = new GatewayYamlConfig.RouteEntry();
        route.setModel("gpt*");
        config.setRoutes(List.of(route));

        List<String> errors = GatewayYamlLoader.validate(config);
        assertThat(errors).anyMatch(e -> e.contains("must specify either 'provider' or 'providers'"));
    }

    @Test
    void validate_invalidStrategy_returnsError() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.RouteEntry route = new GatewayYamlConfig.RouteEntry();
        route.setModel("gpt*");
        route.setProvider("openai");
        route.setStrategy("random");
        config.setRoutes(List.of(route));

        List<String> errors = GatewayYamlLoader.validate(config);
        assertThat(errors).anyMatch(e -> e.contains("unsupported strategy 'random'"));
    }

    @Test
    void validate_apiKeyWithNoKeyOrGenerate_returnsError() {
        GatewayYamlConfig config = new GatewayYamlConfig();
        GatewayYamlConfig.ApiKeyEntry entry = new GatewayYamlConfig.ApiKeyEntry();
        config.setApiKeys(List.of(entry));

        List<String> errors = GatewayYamlLoader.validate(config);
        assertThat(errors).anyMatch(e -> e.contains("must specify 'key_hash'"));
    }

    @Test
    void validate_validConfig_returnsNoErrors() {
        GatewayYamlConfig config = new GatewayYamlConfig();

        GatewayYamlConfig.ProviderEntry provider = new GatewayYamlConfig.ProviderEntry();
        provider.setType("openai");
        provider.setApiKey("sk-test");
        config.setProviders(List.of(provider));

        GatewayYamlConfig.RouteEntry route = new GatewayYamlConfig.RouteEntry();
        route.setModel("gpt*");
        route.setProvider("openai");
        route.setStrategy("model-prefix");
        config.setRoutes(List.of(route));

        GatewayYamlConfig.ApiKeyEntry key = new GatewayYamlConfig.ApiKeyEntry();
        key.setKeyHash("sha256:0000000000000000000000000000000000000000000000000000000000000003");
        config.setApiKeys(List.of(key));

        List<String> errors = GatewayYamlLoader.validate(config);
        assertThat(errors).isEmpty();
    }

    // --- Config path resolution ---

    @Test
    void resolveConfigPath_usesEnvVar() {
        Map<String, String> env = Map.of("GATEWAY_CONFIG_FILE", "/custom/path.yaml");
        Path path = GatewayYamlLoader.resolveConfigPath(env::get);
        assertThat(path).isEqualTo(Path.of("/custom/path.yaml"));
    }

    @Test
    void resolveConfigPath_defaultsToGatewayYaml() {
        Function<String, String> env = name -> null;
        Path path = GatewayYamlLoader.resolveConfigPath(env);
        assertThat(path).isEqualTo(Path.of("gateway.yaml"));
    }

    @Test
    void load_malformedYaml_throwsIllegalState() throws IOException {
        Path configFile = tempDir.resolve("gateway.yaml");
        Files.writeString(configFile, "providers: [invalid yaml: {{{");

        Map<String, String> env = Map.of("GATEWAY_CONFIG_FILE", configFile.toString());
        assertThatThrownBy(() -> GatewayYamlLoader.load(env::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to parse gateway.yaml");
    }

    @Test
    void load_ignoresUnknownFields() throws IOException {
        Path configFile = tempDir.resolve("gateway.yaml");
        Files.writeString(configFile, """
                providers:
                  - type: openai
                    api_key: sk-test
                    unknown_field: should_be_ignored
                unknown_top_level: also_ignored
                """);

        Map<String, String> env = Map.of("GATEWAY_CONFIG_FILE", configFile.toString());
        GatewayYamlConfig config = GatewayYamlLoader.load(env::get).orElseThrow();
        assertThat(config.getProviders()).hasSize(1);
        assertThat(config.getProviders().get(0).getApiKey()).isEqualTo("sk-test");
    }

    // ---- workspace credentials --------------------------------------------

    private static GatewayYamlConfig parse(String yaml) throws IOException {
        return new com.fasterxml.jackson.databind.ObjectMapper(
                new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                .readValue(yaml, GatewayYamlConfig.class);
    }

    @Test
    void workspaceCredentials_aKeyThatNamesNoProviderField_isRefused() throws IOException {
        // The typo that fails invisibly: the key is simply never looked up, so the workspace keeps
        // using the installation-wide credential it meant to replace, and nothing says so.
        var errors = GatewayYamlLoader.validate(parse("""
                workspaces:
                  - id: acme
                    credentials:
                      openai.api-key: sk-whatever
                """));

        assertThat(errors).anyMatch(e -> e.contains("names a provider field")
                && e.contains("would never be looked up"));
    }

    @Test
    void workspaceCredentials_anUnresolvedPlaceholder_isRefused() throws IOException {
        // An unset ${VAR} arrives blank and reads like a value. Left alone it would shadow the
        // installation-wide credential with nothing, and the workspace would send no key at all.
        var errors = GatewayYamlLoader.validate(parse("""
                workspaces:
                  - id: acme
                    credentials:
                      provider.openai.api-key: ""
                """));

        assertThat(errors).anyMatch(e -> e.contains("empty"));
    }

    @Test
    void workspaceCredentials_aWellFormedBlockIsAccepted() throws IOException {
        GatewayYamlConfig config = parse("""
                workspaces:
                  - id: acme
                    credentials:
                      provider.openai.api-key: acme-openai
                      provider.bedrock.access-key: acme-access
                      provider.bedrock.secret-key: acme-secret
                """);

        assertThat(GatewayYamlLoader.validate(config)).isEmpty();
        assertThat(config.getWorkspaces().get(0).getCredentials())
                .as("many keys per provider is ordinary -- Bedrock needs two")
                .containsEntry("provider.openai.api-key", "acme-openai")
                .containsEntry("provider.bedrock.access-key", "acme-access")
                .containsEntry("provider.bedrock.secret-key", "acme-secret");
    }
}