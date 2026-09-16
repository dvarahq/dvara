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

import com.dvarahq.autoconfigure.GatewayEncryptionProperties;
import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.core.secret.AesEncryptor;
import com.dvarahq.core.secret.SecretProvider;

import java.util.Optional;

/**
 * Default {@link SecretProvider} backed by Spring configuration properties.
 * <p>
 * Maps logical secret keys to {@link GatewayProperties} fields:
 * <ul>
 *   <li>{@code provider.openai.api-key} → {@code dvara.llm-gateway.providers.openai.api-key}</li>
 *   <li>{@code provider.anthropic.api-key} → {@code dvara.llm-gateway.providers.anthropic.api-key}</li>
 *   <li>{@code provider.gemini.api-key} → {@code dvara.llm-gateway.providers.gemini.api-key}</li>
 *   <li>{@code provider.bedrock.access-key} → {@code dvara.llm-gateway.providers.bedrock.access-key}</li>
 *   <li>{@code provider.bedrock.secret-key} → {@code dvara.llm-gateway.providers.bedrock.secret-key}</li>
 * </ul>
 * <p>
 * Values prefixed with {@code ENC:} are transparently decrypted using
 * {@link AesEncryptor} with the master password from
 * {@code dvara.encryption.master-password} / {@code DVARA_ENCRYPTION_MASTER_PASSWORD}.
 */
public class PropertySecretProvider implements SecretProvider {

    private static final String ENC_PREFIX = "ENC:";

    private final GatewayProperties properties;
    private final GatewayEncryptionProperties encryptionProperties;

    /**
     * Where a workspace's own credential comes from, or null when nothing in this build holds one.
     * Null is the ordinary case: only a file-configured build supplies a source.
     */
    private final com.dvarahq.core.secret.WorkspaceCredentialSource workspaceCredentials;

    public PropertySecretProvider(GatewayProperties properties,
                                  GatewayEncryptionProperties encryptionProperties) {
        this(properties, encryptionProperties, null);
    }

    public PropertySecretProvider(GatewayProperties properties,
                                  GatewayEncryptionProperties encryptionProperties,
                                  com.dvarahq.core.secret.WorkspaceCredentialSource workspaceCredentials) {
        this.properties = properties;
        this.encryptionProperties = encryptionProperties;
        this.workspaceCredentials = workspaceCredentials;
    }

    /**
     * The workspace's own credential if it brought one, otherwise the installation-wide value.
     *
     * <p><b>Absence falls back; it never refuses.</b> A workspace that configured nothing uses what
     * the installation configured for everybody, which is what makes adding one workspace's key a
     * change to that workspace alone. Requiring a workspace to bring its own is a different
     * question and a different switch.
     *
     * <p>The workspace value goes through the same {@code ENC:} decryption as any other, so the two
     * sources cannot differ in how a value is read — only in where it was found.
     */
    @Override
    public java.util.Optional<String> getSecret(String key, String workspaceId) {
        if (workspaceCredentials != null) {
            java.util.Optional<String> own = workspaceCredentials.credentialFor(workspaceId, key);
            if (own.isPresent()) {
                return decrypted(own.get());
            }
        }
        return getSecret(key);
    }

    @Override
    public Optional<String> getSecret(String key) {
        return decrypted(resolve(key));
    }

    /** Shared by both lookups, so a workspace value and an installation value are read identically. */
    private Optional<String> decrypted(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        if (raw.startsWith(ENC_PREFIX)) {
            return Optional.of(decrypt(raw.substring(ENC_PREFIX.length())));
        }
        return Optional.of(raw);
    }

    @Override
    public boolean hasSecret(String key) {
        String raw = resolve(key);
        return raw != null && !raw.isBlank();
    }

    private String resolve(String key) {
        return switch (key) {
            case "provider.openai.api-key" -> properties.getProviders().getOpenai().getApiKey();
            case "provider.anthropic.api-key" -> properties.getProviders().getAnthropic().getApiKey();
            case "provider.gemini.api-key" -> properties.getProviders().getGemini().getApiKey();
            case "provider.azure-openai.api-key" -> properties.getProviders().getAzureOpenai().getApiKey();
            case "provider.mistral.api-key" -> properties.getProviders().getMistral().getApiKey();
            case "provider.cohere.api-key" -> properties.getProviders().getCohere().getApiKey();
            case "provider.groq.api-key" -> properties.getProviders().getGroq().getApiKey();
            case "provider.qwen.api-key" -> properties.getProviders().getQwen().getApiKey();
            case "provider.deepseek.api-key" -> properties.getProviders().getDeepseek().getApiKey();
            case "provider.moonshot.api-key" -> properties.getProviders().getMoonshot().getApiKey();
            case "provider.chatglm.api-key" -> properties.getProviders().getChatglm().getApiKey();
            case "provider.grok.api-key" -> properties.getProviders().getGrok().getApiKey();
            case "provider.bedrock.access-key" -> properties.getProviders().getBedrock().getAccessKey();
            case "provider.bedrock.secret-key" -> properties.getProviders().getBedrock().getSecretKey();
            default -> null;
        };
    }

    private String decrypt(String ciphertext) {
        String masterPassword = encryptionProperties.getMasterPassword();
        if (masterPassword == null || masterPassword.isBlank()) {
            throw new IllegalStateException(
                    "Encrypted credential found (ENC: prefix) but dvara.encryption.master-password is not set. " +
                    "Set DVARA_ENCRYPTION_MASTER_PASSWORD environment variable.");
        }
        return AesEncryptor.decrypt(ciphertext, masterPassword);
    }
}