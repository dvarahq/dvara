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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where {@code gateway.yaml}'s properties sit relative to everything else.
 *
 * <p>{@code application.yml} is the application's own defaults and already defines every key this
 * class writes, so the file must rank above it, and below anything the operator states directly.
 * The tests assert which value {@code getProperty} returns rather than the property source's
 * index, and never set the property they assert.
 */
class GatewayConfigEnvironmentPostProcessorTest {

    @TempDir
    Path dir;

    /** The shape of {@code application.yml}: every key defined, resolved to a default. */
    private static final Map<String, Object> APPLICATION_DEFAULTS = Map.of(
            "dvara.llm-gateway.providers.mock.enabled", "false",
            "dvara.llm-gateway.providers.openai.api-key", "");

    /** Every provider type the validator accepts is also mapped: both read the same table. */
    @Test
    void aProviderBeyondTheOriginalSix_isMappedNotRefused() throws IOException {
        StandardEnvironment environment = environmentWith("""
                providers:
                  - type: mistral
                    api_key: sk-mistral-from-the-file
                  - type: azure-openai
                    api_key: az-key
                    base_url: https://r.openai.azure.com/openai
                """);

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.providers.mistral.api-key")).isEqualTo("sk-mistral-from-the-file");
        assertThat(environment.getProperty("dvara.llm-gateway.providers.azure-openai.api-key")).isEqualTo("az-key");
        assertThat(environment.getProperty("dvara.llm-gateway.providers.azure-openai.base-url")).isEqualTo("https://r.openai.azure.com/openai");
    }

    /**
     * The key in the file and the URL from the environment is a legitimate split, so the file is not
     * refused for lacking Azure's base_url; the post-processor judges the effective configuration
     * instead, and warns only when no source supplies the URL.
     */
    @Test
    void azureKeyInTheFile_urlFromTheEnvironment_isNotRefused_andNotWarned() throws IOException {
        StandardEnvironment environment = environmentWith("""
                providers:
                  - type: azure-openai
                    api_key: az-key
                """);
        environment.getPropertySources().addFirst(new MapPropertySource("env",
                Map.of("dvara.llm-gateway.providers.azure-openai.base-url", "https://r.openai.azure.com/openai")));

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.providers.azure-openai.api-key")).isEqualTo("az-key");
        assertThat(GatewayConfigEnvironmentPostProcessor.effectiveWarnings(environment)).isEmpty();
    }

    @Test
    void azureKeyWithNoUrlFromAnySource_isWarnedNotRefused() throws IOException {
        StandardEnvironment environment = environmentWith("""
                providers:
                  - type: azure-openai
                    api_key: az-key
                """);

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.providers.azure-openai.api-key")).isEqualTo("az-key");
        assertThat(GatewayConfigEnvironmentPostProcessor.effectiveWarnings(environment)).singleElement()
                .asString().contains("azure-openai").contains("no base-url from any source").contains("will not register");
    }

    /**
     * The warning must not depend on the file having contributed anything: a key from the environment
     * alone, with no file at all, would otherwise leave Azure silently unregistered. The stderr line
     * itself is checked, since the post-processor has no logger at this point.
     */
    @Test
    void azureKeyFromTheEnvironmentAlone_noFile_noUrl_isWarnedOnStderr() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("env",
                Map.of("dvara.llm-gateway.providers.azure-openai.api-key", "az-key-from-env")));
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true));
        try {
            new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);
        } finally {
            System.setErr(original);
        }
        assertThat(captured.toString()).contains("WARN: provider configuration:")
                .contains("azure-openai").contains("no base-url from any source").contains("will not register");
        assertThat(environment.getPropertySources().contains("gatewayYamlConfig")).as("no file, nothing contributed").isFalse();
    }

    @Test
    void aProviderInTheFileBeatsTheApplicationDefault() throws IOException {
        StandardEnvironment environment = environmentWith("""
                providers:
                  - type: mock
                """);

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.providers.mock.enabled"))
                .as("a provider declared in gateway.yaml must switch it on, over application.yml's own default")
                .isEqualTo("true");
    }

    @Test
    void anApiKeyInTheFileBeatsTheEmptyApplicationDefault() throws IOException {
        StandardEnvironment environment = environmentWith("""
                providers:
                  - type: openai
                    api_key: sk-from-the-file
                """);

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.providers.openai.api-key"))
                .isEqualTo("sk-from-the-file");
    }

    /**
     * A value the operator states directly, on the command line, as a system property or as an
     * environment variable, still wins. The file sits below those and above the application's
     * defaults.
     */
    @Test
    void anOperatorSuppliedValueStillBeatsTheFile() throws IOException {
        StandardEnvironment environment = environmentWith("""
                providers:
                  - type: openai
                    api_key: sk-from-the-file
                """);
        environment.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
                Map.of("dvara.llm-gateway.providers.openai.api-key", "sk-from-the-command-line")));

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.providers.openai.api-key"))
                .isEqualTo("sk-from-the-command-line");
    }

    @Test
    void aBurstInTheFileReachesTheLimiter() throws IOException {
        StandardEnvironment environment = environmentWith("""
                rate_limits:
                  requests_per_minute: 100
                  requests_burst: 10
                  tokens_per_minute: 100000
                  tokens_burst: 5000
                """);

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.rate-limit.per-key.requests-burst"))
                .as("burst capacity is how big a spike is allowed, separate from the sustained rate")
                .isEqualTo("10");
        assertThat(environment.getProperty("dvara.llm-gateway.rate-limit.per-key.tokens-burst"))
                .isEqualTo("5000");
    }

    @Test
    void aBurstAloneDoesNotSwitchRateLimitingOn() throws IOException {
        StandardEnvironment environment = environmentWith("""
                rate_limits:
                  requests_burst: 10
                """);

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.rate-limit.enabled"))
                .as("a burst with no rate beside it configures nothing, and turning enforcement on "
                        + "from it would be a surprising way to start refusing traffic")
                .isNotEqualTo("true");
    }

    /**
     * A provider entry without an {@code api_key} contributes no property, so
     * {@code application.yml}'s {@code ${OPENAI_API_KEY:}} still governs. The file overrides values it
     * states and invents none.
     */
    @Test
    void aKeyTheFileDoesNotMentionIsUntouched() throws IOException {
        StandardEnvironment environment = environmentWith("""
                providers:
                  - type: openai
                """);

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.providers.openai.api-key"))
                .as("the file said nothing about the key, so the application default must survive")
                .isEmpty();
    }

    /** The env var shortcut ranks above the application default too, with no file involved at all. */
    @Test
    void theEnvVarShortcutAlsoBeatsTheApplicationDefault() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(
                new MapPropertySource("applicationConfig: [classpath:/application.yml]", APPLICATION_DEFAULTS));
        environment.getPropertySources().addFirst(new MapPropertySource("shortcut",
                Map.of("DVARA_PROVIDER_TYPE", "mock",
                        // Point the loader at a path that does not exist, so only the shortcut runs.
                        "DVARA_CONFIG_FILE", dir.resolve("absent.yaml").toString())));

        new GatewayConfigEnvironmentPostProcessor().postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("dvara.llm-gateway.providers.mock.enabled"))
                .as("DVARA_PROVIDER_TYPE=mock with no file at all must enable the mock provider")
                .isEqualTo("true");
    }

    private StandardEnvironment environmentWith(String yaml) throws IOException {
        Path file = dir.resolve("gateway.yaml");
        Files.writeString(file, yaml);
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(
                new MapPropertySource("applicationConfig: [classpath:/application.yml]", APPLICATION_DEFAULTS));
        environment.getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("DVARA_CONFIG_FILE", file.toString())));
        return environment;
    }
}