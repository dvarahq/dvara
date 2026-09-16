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

import static org.assertj.core.api.Assertions.assertThat;

import com.dvarahq.autoconfigure.ProviderAutoConfiguration;
import com.dvarahq.core.provider.LlmProvider;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * The table in {@link YamlProviderType} is compared against the auto-configuration it stands for,
 * in both directions and for the switch each condition reads.
 */
class YamlProviderTypeTest {

    private static final Pattern CONDITION =
            Pattern.compile("dvara\\.llm-gateway\\.providers\\.([a-z-]+)\\.(api-key|enabled)");

    /** type -> the switch its bean condition reads, taken from ProviderAutoConfiguration itself. */
    private static Map<String, YamlProviderType.Switch> registeredProviders() {
        Map<String, YamlProviderType.Switch> found = new TreeMap<>();
        for (Method m : ProviderAutoConfiguration.class.getDeclaredMethods()) {
            if (!m.isAnnotationPresent(Bean.class) || !LlmProvider.class.isAssignableFrom(m.getReturnType())) continue;
            String condition = "";
            ConditionalOnExpression expr = m.getAnnotation(ConditionalOnExpression.class);
            if (expr != null) condition = expr.value();
            ConditionalOnProperty prop = m.getAnnotation(ConditionalOnProperty.class);
            if (prop != null) condition = String.join(" ", prop.name()) + " " + String.join(" ", prop.value());
            Matcher matcher = CONDITION.matcher(condition);
            java.util.Set<String> types = new java.util.TreeSet<>();
            YamlProviderType.Switch sw = null;
            while (matcher.find()) {
                types.add(matcher.group(1));
                sw = matcher.group(2).equals("api-key") ? YamlProviderType.Switch.API_KEY : YamlProviderType.Switch.ENABLED;
            }
            assertThat(types).as("%s gates on exactly one providers.<type>.api-key or .enabled property", m.getName()).hasSize(1);
            String type = types.iterator().next();
            assertThat(found).as("%s is gated by one bean method only", type).doesNotContainKey(type);
            found.put(type, sw);
        }
        return found;
    }

    /** type -> whether its bean method passes a base-url, read from the auto-configuration's source. */
    private static Map<String, Boolean> beanMethodsReadingBaseUrl() throws java.io.IOException {
        java.nio.file.Path src = java.nio.file.Path.of("src/main/java/com/dvarahq/autoconfigure/ProviderAutoConfiguration.java");
        assertThat(src).exists();
        Map<String, Boolean> reads = new TreeMap<>();
        for (String block : java.nio.file.Files.readString(src).split("\n    @Bean\n")) {
            Matcher name = Pattern.compile("public \\w+Provider \\w+\\(").matcher(block);
            Matcher cond = CONDITION.matcher(block);
            if (!name.find() || !cond.find()) continue;
            reads.put(cond.group(1), block.contains("getBaseUrl()"));
        }
        return reads;
    }

    @Test
    void everyRegisteredProviderIsAccepted_andNothingElse() {
        Map<String, YamlProviderType.Switch> registered = registeredProviders();
        Map<String, YamlProviderType.Switch> table = new TreeMap<>();
        for (YamlProviderType t : YamlProviderType.values()) table.put(t.type(), t.switchedOn());
        assertThat(table).as("the file accepts exactly the providers the auto-configuration registers, switched the way each condition reads")
                .isEqualTo(registered);
        assertThat(registered).hasSize(15);
    }

    /** Three providers have a fixed endpoint; the table must say so, or a file base_url maps into nothing. */
    @Test
    void readsBaseUrl_matchesWhatEachBeanMethodPasses() throws java.io.IOException {
        Map<String, Boolean> fromSource = beanMethodsReadingBaseUrl();
        Map<String, Boolean> table = new TreeMap<>();
        for (YamlProviderType t : YamlProviderType.values()) table.put(t.type(), t.readsBaseUrl());
        assertThat(table).isEqualTo(fromSource);
        assertThat(fromSource).containsEntry("anthropic", false).containsEntry("bedrock", false).containsEntry("mock", false);
        assertThat(fromSource.values().stream().filter(b -> !b).count()).isEqualTo(3);
    }

    /** Azure's condition needs base-url as well as the key; the table says so, and only for Azure. */
    @Test
    void azureIsTheOnlyProviderWhoseConditionNeedsABaseUrl() {
        for (Method m : ProviderAutoConfiguration.class.getDeclaredMethods()) {
            ConditionalOnExpression expr = m.getAnnotation(ConditionalOnExpression.class);
            if (expr == null) continue;
            boolean needsUrl = expr.value().contains(".base-url");
            Matcher matcher = CONDITION.matcher(expr.value());
            assertThat(matcher.find()).isTrue();
            assertThat(YamlProviderType.of(matcher.group(1)).orElseThrow().baseUrlRequiredToRegister())
                    .as(matcher.group(1)).isEqualTo(needsUrl);
        }
        assertThat(java.util.Arrays.stream(YamlProviderType.values()).filter(YamlProviderType::baseUrlRequiredToRegister))
                .containsExactly(YamlProviderType.AZURE_OPENAI);
    }

    /** The file is judged only on what it alone can decide: a base_url a fixed-endpoint provider would never read. */
    @Test
    void aBaseUrlOnAFixedEndpointProvider_isAProblem_andAzureWithoutOneIsNot() {
        GatewayYamlConfig.ProviderEntry anthropic = new GatewayYamlConfig.ProviderEntry();
        anthropic.setType("anthropic");
        anthropic.setApiKey("k");
        anthropic.setBaseUrl("https://proxy.example");
        assertThat(YamlProviderType.ANTHROPIC.problems(anthropic))
                .containsExactly("base_url: not read by anthropic, whose endpoint is fixed");
        Map<String, Object> props = new HashMap<>();
        YamlProviderType.ANTHROPIC.mapTo(anthropic, props);
        assertThat(props).containsOnlyKeys("dvara.llm-gateway.providers.anthropic.api-key");
        GatewayYamlConfig.ProviderEntry azure = new GatewayYamlConfig.ProviderEntry();
        azure.setType("azure-openai");
        azure.setApiKey("k");
        assertThat(YamlProviderType.AZURE_OPENAI.problems(azure)).as("the URL may come from the environment").isEmpty();
    }

    @Test
    void anApiKeyProviderMapsToWhatItsConditionReads() {
        GatewayYamlConfig.ProviderEntry e = new GatewayYamlConfig.ProviderEntry();
        e.setType("Mistral");
        e.setApiKey("sk-m");
        Map<String, Object> props = new HashMap<>();
        YamlProviderType.of("Mistral").orElseThrow().mapTo(e, props);
        assertThat(props).containsOnly(Map.entry("dvara.llm-gateway.providers.mistral.api-key", "sk-m"));
        e.setBaseUrl("https://proxy.example/v1");
        props.clear();
        YamlProviderType.MISTRAL.mapTo(e, props);
        assertThat(props).containsEntry("dvara.llm-gateway.providers.mistral.base-url", "https://proxy.example/v1");
    }

    @Test
    void enabledProvidersMapTheirOwnFields() {
        GatewayYamlConfig.ProviderEntry b = new GatewayYamlConfig.ProviderEntry();
        b.setType("bedrock");
        b.setAccessKey("ak");
        b.setSecretKey("sk");
        b.setRegion("eu-west-1");
        Map<String, Object> props = new HashMap<>();
        YamlProviderType.BEDROCK.mapTo(b, props);
        assertThat(props).containsOnly(
                Map.entry("dvara.llm-gateway.providers.bedrock.enabled", "true"),
                Map.entry("dvara.llm-gateway.providers.bedrock.access-key", "ak"),
                Map.entry("dvara.llm-gateway.providers.bedrock.secret-key", "sk"),
                Map.entry("dvara.llm-gateway.providers.bedrock.region", "eu-west-1"));
        GatewayYamlConfig.ProviderEntry o = new GatewayYamlConfig.ProviderEntry();
        o.setType("ollama");
        o.setBaseUrl("http://ollama:11434");
        props.clear();
        YamlProviderType.OLLAMA.mapTo(o, props);
        assertThat(props).containsOnly(
                Map.entry("dvara.llm-gateway.providers.ollama.enabled", "true"),
                Map.entry("dvara.llm-gateway.providers.ollama.base-url", "http://ollama:11434"));
        props.clear();
        YamlProviderType.MOCK.mapTo(new GatewayYamlConfig.ProviderEntry(), props);
        assertThat(props).containsOnly(Map.entry("dvara.llm-gateway.providers.mock.enabled", "true"));
    }

    @Test
    void unknownTypeIsNotFound_andTheMessageListsTheTable() {
        assertThat(YamlProviderType.of("mistrl")).isEmpty();
        assertThat(YamlProviderType.of(null)).isEmpty();
        assertThat(YamlProviderType.namesForMessage()).startsWith("openai, anthropic").contains("mistral").endsWith("mock");
    }
}
