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
package com.dvarahq.autoconfigure.secret;

import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.core.secret.AesEncryptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertySecretProviderTest {

    private GatewayProperties properties;

    @BeforeEach
    void setUp() {
        properties = new GatewayProperties();
    }

    @Test
    void getSecret_openaiApiKey_returnsPlainValue() {
        properties.getProviders().getOpenai().setApiKey("sk-openai-test");

        PropertySecretProvider provider = new PropertySecretProvider(properties, new com.dvarahq.autoconfigure.GatewayEncryptionProperties());

        assertThat(provider.getSecret("provider.openai.api-key")).hasValue("sk-openai-test");
    }

    @Test
    void getSecret_anthropicApiKey_returnsPlainValue() {
        properties.getProviders().getAnthropic().setApiKey("sk-ant-test");

        PropertySecretProvider provider = new PropertySecretProvider(properties, new com.dvarahq.autoconfigure.GatewayEncryptionProperties());

        assertThat(provider.getSecret("provider.anthropic.api-key")).hasValue("sk-ant-test");
    }

    @Test
    void getSecret_geminiApiKey_returnsPlainValue() {
        properties.getProviders().getGemini().setApiKey("AIza-gemini-test");

        PropertySecretProvider provider = new PropertySecretProvider(properties, new com.dvarahq.autoconfigure.GatewayEncryptionProperties());

        assertThat(provider.getSecret("provider.gemini.api-key")).hasValue("AIza-gemini-test");
    }

    @Test
    void getSecret_bedrockKeys_returnsPlainValues() {
        properties.getProviders().getBedrock().setAccessKey("AKIA-test");
        properties.getProviders().getBedrock().setSecretKey("secret-test");

        PropertySecretProvider provider = new PropertySecretProvider(properties, new com.dvarahq.autoconfigure.GatewayEncryptionProperties());

        assertThat(provider.getSecret("provider.bedrock.access-key")).hasValue("AKIA-test");
        assertThat(provider.getSecret("provider.bedrock.secret-key")).hasValue("secret-test");
    }

    @Test
    void everyKeyedProviderResolvesItsOwnApiKey() {
        // Every provider whose bean registers on its api-key property must resolve here, or the
        // first request fails asking for a secret nothing can answer.
        GatewayProperties.Providers p = properties.getProviders();
        java.util.Map<String, GatewayProperties.ProviderConfig> keyed = new java.util.LinkedHashMap<>();
        keyed.put("openai", p.getOpenai());
        keyed.put("anthropic", p.getAnthropic());
        keyed.put("gemini", p.getGemini());
        keyed.put("azure-openai", p.getAzureOpenai());
        keyed.put("mistral", p.getMistral());
        keyed.put("cohere", p.getCohere());
        keyed.put("groq", p.getGroq());
        keyed.put("qwen", p.getQwen());
        keyed.put("deepseek", p.getDeepseek());
        keyed.put("moonshot", p.getMoonshot());
        keyed.put("chatglm", p.getChatglm());
        keyed.put("grok", p.getGrok());
        keyed.forEach((name, config) -> config.setApiKey("key-for-" + name));
        PropertySecretProvider provider = new PropertySecretProvider(properties, new com.dvarahq.autoconfigure.GatewayEncryptionProperties());

        keyed.keySet().forEach(name ->
                assertThat(provider.getSecret("provider." + name + ".api-key"))
                        .as(name).hasValue("key-for-" + name));
    }

    @Test
    void getSecret_missingKey_returnsEmpty() {
        PropertySecretProvider provider = new PropertySecretProvider(properties, new com.dvarahq.autoconfigure.GatewayEncryptionProperties());

        assertThat(provider.getSecret("provider.openai.api-key")).isEmpty();
    }

    @Test
    void getSecret_unknownKey_returnsEmpty() {
        PropertySecretProvider provider = new PropertySecretProvider(properties, new com.dvarahq.autoconfigure.GatewayEncryptionProperties());

        assertThat(provider.getSecret("provider.unknown.api-key")).isEmpty();
    }

    @Test
    void getSecret_encPrefix_decryptsValue() {
        String masterPassword = "my-master-password";
        String encrypted = AesEncryptor.encrypt("sk-real-secret", masterPassword);

        properties.getProviders().getOpenai().setApiKey("ENC:" + encrypted);
        com.dvarahq.autoconfigure.GatewayEncryptionProperties encryption =
                new com.dvarahq.autoconfigure.GatewayEncryptionProperties();
        encryption.setMasterPassword(masterPassword);

        PropertySecretProvider provider = new PropertySecretProvider(properties, encryption);

        assertThat(provider.getSecret("provider.openai.api-key")).hasValue("sk-real-secret");
    }

    @Test
    void getSecret_encPrefix_noMasterPassword_throws() {
        String encrypted = AesEncryptor.encrypt("secret", "some-password");
        properties.getProviders().getOpenai().setApiKey("ENC:" + encrypted);
        // master password not set

        PropertySecretProvider provider = new PropertySecretProvider(properties, new com.dvarahq.autoconfigure.GatewayEncryptionProperties());

        assertThatThrownBy(() -> provider.getSecret("provider.openai.api-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master-password is not set");
    }

    @Test
    void getSecret_blankValue_returnsEmpty() {
        properties.getProviders().getOpenai().setApiKey("   ");

        PropertySecretProvider provider = new PropertySecretProvider(properties, new com.dvarahq.autoconfigure.GatewayEncryptionProperties());

        assertThat(provider.getSecret("provider.openai.api-key")).isEmpty();
    }
}