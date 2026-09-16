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

import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlLoader;
import com.dvarahq.autoconfigure.config.yaml.YamlProviderType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Translates {@code gateway.yaml} provider and rate-limit entries plus env var shortcuts
 * into Spring properties <b>before</b> the application context starts.
 *
 * <p>The properties are inserted immediately below {@code systemEnvironment}: above this
 * application's own {@code application.yml} defaults, which already define every key written here
 * with placeholders such as {@code ${OPENAI_API_KEY:}}, and below everything an operator states
 * directly (command line, system properties, environment variables). Added at the bottom instead,
 * they would be outranked by those defaults and never read.
 *
 * <p>Only keys the operator actually wrote are affected. {@code YamlProviderType.mapTo} uses
 * put-if-not-blank, so a provider entry with no {@code api_key} contributes no {@code api-key}
 * property and the {@code application.yml} placeholder still applies.
 *
 * <p>Env var shortcuts:
 * <ul>
 *   <li>{@code DVARA_PROVIDER_TYPE} + {@code DVARA_PROVIDER_API_KEY} — single provider</li>
 *   <li>{@code DVARA_PROVIDERS_0_TYPE} / {@code _API_KEY} / {@code _NAME} — multi-provider (indexed)</li>
 * </ul>
 * The legacy {@code GATEWAY_*} names are read as fallbacks.
 */
public class GatewayConfigEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final String PROPERTY_SOURCE_NAME = "gatewayYamlConfig";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> props = new HashMap<>();

        // Phase 1a: Load gateway.yaml providers + rate limits
        Function<String, String> envResolver = name -> environment.getProperty(name);
        Optional<GatewayYamlConfig> configOpt = GatewayYamlLoader.load(envResolver);

        if (configOpt.isPresent()) {
            GatewayYamlConfig config = configOpt.get();
            List<String> errors = GatewayYamlLoader.validate(config);
            if (!errors.isEmpty()) {
                System.err.println("ERROR: gateway.yaml validation failed:");
                errors.forEach(e -> System.err.println("  - " + e));
                throw new IllegalStateException("gateway.yaml validation failed: " + errors);
            }

            if (config.getProviders() != null) {
                for (GatewayYamlConfig.ProviderEntry provider : config.getProviders()) {
                    mapProviderToProperties(provider, props);
                }
            }

            if (config.getRateLimits() != null) {
                mapRateLimitsToProperties(config.getRateLimits(), props);
            }
        }

        // Phase 1b: Env var shortcuts (single provider). Canonical DVARA_*
        // first; the legacy GATEWAY_* name is still read as a fallback.
        String singleType = readEnv(environment, "DVARA_PROVIDER_TYPE", "GATEWAY_PROVIDER_TYPE");
        if (singleType != null && !singleType.isBlank()) {
            GatewayYamlConfig.ProviderEntry entry = new GatewayYamlConfig.ProviderEntry();
            entry.setType(singleType);
            entry.setApiKey(readEnv(environment, "DVARA_PROVIDER_API_KEY", "GATEWAY_PROVIDER_API_KEY"));
            entry.setBaseUrl(readEnv(environment, "DVARA_PROVIDER_BASE_URL", "GATEWAY_PROVIDER_BASE_URL"));
            entry.setAccessKey(readEnv(environment, "DVARA_PROVIDER_ACCESS_KEY", "GATEWAY_PROVIDER_ACCESS_KEY"));
            entry.setSecretKey(readEnv(environment, "DVARA_PROVIDER_SECRET_KEY", "GATEWAY_PROVIDER_SECRET_KEY"));
            entry.setRegion(readEnv(environment, "DVARA_PROVIDER_REGION", "GATEWAY_PROVIDER_REGION"));
            mapProviderToProperties(entry, props);
        }

        // Phase 1c: Env var shortcuts (multi-provider indexed). Same
        // canonical-first / legacy-fallback shape per index.
        for (int i = 0; i < 10; i++) {
            String indexedType = readEnv(environment,
                    "DVARA_PROVIDERS_" + i + "_TYPE",
                    "GATEWAY_PROVIDERS_" + i + "_TYPE");
            if (indexedType == null || indexedType.isBlank()) {
                continue;
            }
            GatewayYamlConfig.ProviderEntry entry = new GatewayYamlConfig.ProviderEntry();
            entry.setType(indexedType);
            entry.setApiKey(readEnv(environment,
                    "DVARA_PROVIDERS_" + i + "_API_KEY",
                    "GATEWAY_PROVIDERS_" + i + "_API_KEY"));
            entry.setBaseUrl(readEnv(environment,
                    "DVARA_PROVIDERS_" + i + "_BASE_URL",
                    "GATEWAY_PROVIDERS_" + i + "_BASE_URL"));
            entry.setName(readEnv(environment,
                    "DVARA_PROVIDERS_" + i + "_NAME",
                    "GATEWAY_PROVIDERS_" + i + "_NAME"));
            entry.setAccessKey(readEnv(environment,
                    "DVARA_PROVIDERS_" + i + "_ACCESS_KEY",
                    "GATEWAY_PROVIDERS_" + i + "_ACCESS_KEY"));
            entry.setSecretKey(readEnv(environment,
                    "DVARA_PROVIDERS_" + i + "_SECRET_KEY",
                    "GATEWAY_PROVIDERS_" + i + "_SECRET_KEY"));
            entry.setRegion(readEnv(environment,
                    "DVARA_PROVIDERS_" + i + "_REGION",
                    "GATEWAY_PROVIDERS_" + i + "_REGION"));
            mapProviderToProperties(entry, props);
        }

        if (!props.isEmpty()) {
            addAboveApplicationDefaults(environment, new MapPropertySource(PROPERTY_SOURCE_NAME, props));
        }
        // Whatever the file contributed — including nothing, with the key arriving from the environment
        // alone — judge the effective configuration. Logging is not up yet in an EnvironmentPostProcessor;
        // the validation errors above use stderr too.
        effectiveWarnings(environment).forEach(w -> System.err.println("WARN: provider configuration: " + w));
    }

    /**
     * What the file alone cannot judge, checked once its values are in the environment: a provider
     * whose bean needs a base-url as well as a key (Azure OpenAI) has a key but, from every source
     * together, no URL — so it will silently not register. A warning rather than a refusal, because
     * the file is not necessarily wrong: the URL may be meant to come from
     * {@code AZURE_OPENAI_BASE_URL} and simply be unset in this environment.
     */
    static List<String> effectiveWarnings(ConfigurableEnvironment environment) {
        List<String> warnings = new java.util.ArrayList<>();
        for (YamlProviderType type : YamlProviderType.values()) {
            if (!type.baseUrlRequiredToRegister()) continue;
            String key = environment.getProperty(type.propertyPrefix() + ".api-key", "");
            String baseUrl = environment.getProperty(type.propertyPrefix() + ".base-url", "");
            if (!key.isBlank() && baseUrl.isBlank()) {
                warnings.add("provider " + type.type() + " has an api-key but no base-url from any source, so it will"
                        + " not register; set base_url in the file, or " + type.propertyPrefix()
                        + ".base-url through the property or environment variable it binds from");
            }
        }
        return warnings;
    }

    private void mapProviderToProperties(GatewayYamlConfig.ProviderEntry provider, Map<String, Object> props) {
        // Shares one table with the loader's validation, so a type the file accepts is a type this maps.
        YamlProviderType.of(provider.getType()).ifPresent(type -> type.mapTo(provider, props));
    }

    private void mapRateLimitsToProperties(GatewayYamlConfig.RateLimitsEntry rateLimits, Map<String, Object> props) {
        if (rateLimits.getRequestsPerMinute() != null) {
            // The gateway's rate limiter enforces a per-API-key count over a
            // rolling 60-second window, so the YAML value maps 1:1 into the
            // truthful "per-key.requests-per-minute" property — no unit conversion.
            props.put("dvara.llm-gateway.rate-limit.enabled", "true");
            props.put("dvara.llm-gateway.rate-limit.per-key.requests-per-minute",
                    String.valueOf(rateLimits.getRequestsPerMinute()));
        }
        if (rateLimits.getTokensPerMinute() != null) {
            props.put("dvara.llm-gateway.rate-limit.enabled", "true");
            props.put("dvara.llm-gateway.rate-limit.per-key.tokens-per-minute",
                    String.valueOf(rateLimits.getTokensPerMinute()));
        }
        // Burst capacity -- how big a spike is allowed after idling, as opposed to the
        // sustained rate above. Deliberately does NOT switch rate limiting on by itself: a burst
        // with no rate beside it configures nothing, and turning enforcement on from it would be a
        // surprising way to start refusing traffic.
        if (rateLimits.getRequestsBurst() != null) {
            props.put("dvara.llm-gateway.rate-limit.per-key.requests-burst",
                    String.valueOf(rateLimits.getRequestsBurst()));
        }
        if (rateLimits.getTokensBurst() != null) {
            props.put("dvara.llm-gateway.rate-limit.per-key.tokens-burst",
                    String.valueOf(rateLimits.getTokensBurst()));
        }
    }

    /**
     * Inserts just below {@code systemEnvironment}: above this application's own defaults, below
     * anything the operator states directly. {@code addAfter} throws if the named source is absent,
     * which only happens in a hand-built environment; there nothing needs outranking, so
     * {@code addFirst} is correct. It must never fall back to {@code addLast}.
     */
    private static void addAboveApplicationDefaults(ConfigurableEnvironment environment,
                                                    MapPropertySource source) {
        MutablePropertySources sources = environment.getPropertySources();
        String systemEnvironment = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        if (sources.contains(systemEnvironment)) {
            sources.addAfter(systemEnvironment, source);
        } else {
            sources.addFirst(source);
        }
    }

    /**
     * Reads a canonical {@code DVARA_*} env var, falling back to the legacy {@code GATEWAY_*}
     * alias. Only returns the resolved value; it does not warn about the legacy name.
     */
    private static String readEnv(org.springframework.core.env.Environment env,
                                  String canonical, String legacy) {
        String value = env.getProperty(canonical);
        if (value == null || value.isBlank()) {
            value = env.getProperty(legacy);
        }
        return value;
    }
}