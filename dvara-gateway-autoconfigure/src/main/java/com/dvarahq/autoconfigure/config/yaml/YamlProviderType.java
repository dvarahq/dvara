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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The provider types {@code gateway.yaml} accepts, and how each becomes the properties
 * {@code ProviderAutoConfiguration} registers it from. One table for both: the loader validates
 * membership against it and derives its "valid:" list from it, and the environment post-processor
 * maps through it, so the two cannot drift apart.
 *
 * <p>Each type is switched on the way its bean condition reads it: most register when their
 * {@code api-key} property is set; Ollama, Bedrock and Mock have no key and register on
 * {@code enabled}. Whether a type reads a {@code base-url} is recorded too, because Anthropic,
 * Bedrock and Mock do not, and a {@code base_url} for one of those would map to a property nothing
 * reads, so it is refused instead. Azure OpenAI also needs a {@code base-url} to register, since no
 * default can name the resource; the post-processor checks that against the effective environment
 * rather than the file, because the URL may come from {@code AZURE_OPENAI_BASE_URL} while the key
 * is in the file. {@code YamlProviderTypeTest} checks this table against the auto-configuration in
 * both directions.
 */
public enum YamlProviderType {
    OPENAI("openai", Switch.API_KEY, true, false),
    ANTHROPIC("anthropic", Switch.API_KEY, false, false),
    GEMINI("gemini", Switch.API_KEY, true, false),
    AZURE_OPENAI("azure-openai", Switch.API_KEY, true, true),
    MISTRAL("mistral", Switch.API_KEY, true, false),
    COHERE("cohere", Switch.API_KEY, true, false),
    GROQ("groq", Switch.API_KEY, true, false),
    QWEN("qwen", Switch.API_KEY, true, false),
    DEEPSEEK("deepseek", Switch.API_KEY, true, false),
    MOONSHOT("moonshot", Switch.API_KEY, true, false),
    CHATGLM("chatglm", Switch.API_KEY, true, false),
    GROK("grok", Switch.API_KEY, true, false),
    OLLAMA("ollama", Switch.ENABLED, true, false),
    BEDROCK("bedrock", Switch.ENABLED, false, false),
    MOCK("mock", Switch.ENABLED, false, false);

    /** What the bean condition reads. */
    public enum Switch { API_KEY, ENABLED }

    static final String PREFIX = "dvara.llm-gateway.providers.";

    private final String type;
    private final Switch switchedOn;
    private final boolean readsBaseUrl;
    private final boolean baseUrlRequiredToRegister;

    YamlProviderType(String type, Switch switchedOn, boolean readsBaseUrl, boolean baseUrlRequiredToRegister) {
        this.type = type;
        this.switchedOn = switchedOn;
        this.readsBaseUrl = readsBaseUrl;
        this.baseUrlRequiredToRegister = baseUrlRequiredToRegister;
    }

    /** The value of {@code type:} in the file, which is also the property segment. */
    public String type() {
        return type;
    }

    public Switch switchedOn() {
        return switchedOn;
    }

    /** Whether the provider's bean reads a {@code base-url} at all. */
    public boolean readsBaseUrl() {
        return readsBaseUrl;
    }

    /** Whether the bean condition needs a {@code base-url} as well as the key — Azure OpenAI. */
    public boolean baseUrlRequiredToRegister() {
        return baseUrlRequiredToRegister;
    }

    /** The property prefix this type's bean condition and properties bind under. */
    public String propertyPrefix() {
        return PREFIX + type;
    }

    /** Case-insensitive lookup by the file's {@code type:} value. */
    public static Optional<YamlProviderType> of(String type) {
        if (type == null) return Optional.empty();
        return Arrays.stream(values()).filter(t -> t.type.equalsIgnoreCase(type)).findFirst();
    }

    /** The accepted names, in declaration order, for validation messages. */
    public static List<String> names() {
        return Arrays.stream(values()).map(t -> t.type).toList();
    }

    public static String namesForMessage() {
        return names().stream().collect(Collectors.joining(", "));
    }

    /**
     * Validation beyond membership, on what the file alone can judge: a field this type's bean
     * would never read. Returned as {@code <field>: <reason>} suffixes for the loader to prefix.
     * What the file cannot judge — whether Azure's URL arrives from the environment instead — is
     * left to the post-processor, which sees the effective configuration.
     */
    public List<String> problems(GatewayYamlConfig.ProviderEntry entry) {
        if (!readsBaseUrl && !isBlank(entry.getBaseUrl())) {
            return List.of("base_url: not read by " + type + ", whose endpoint is fixed");
        }
        return List.of();
    }

    /** Writes the entry into the properties the type's bean condition and configuration read. */
    public void mapTo(GatewayYamlConfig.ProviderEntry entry, Map<String, Object> props) {
        String p = propertyPrefix() + ".";
        switch (switchedOn) {
            case API_KEY -> {
                putIfNotBlank(props, p + "api-key", entry.getApiKey());
                if (readsBaseUrl) putIfNotBlank(props, p + "base-url", entry.getBaseUrl());
            }
            case ENABLED -> {
                props.put(p + "enabled", "true");
                if (readsBaseUrl) {
                    putIfNotBlank(props, p + "base-url", entry.getBaseUrl());
                } else if (this == BEDROCK) {
                    putIfNotBlank(props, p + "access-key", entry.getAccessKey());
                    putIfNotBlank(props, p + "secret-key", entry.getSecretKey());
                    putIfNotBlank(props, p + "region", entry.getRegion());
                }
            }
        }
    }

    private static void putIfNotBlank(Map<String, Object> props, String key, String value) {
        if (!isBlank(value)) props.put(key, value);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
