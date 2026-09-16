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
package com.dvarahq.core.secret;

import com.dvarahq.core.exception.GatewayException;

import java.util.Optional;

/**
 * Abstraction for retrieving provider credentials at runtime.
 * <p>
 * Default: {@code PropertySecretProvider} backed by Spring properties. Another module or the
 * application may register a vault-backed implementation (HashiCorp Vault, AWS Secrets Manager,
 * etc.) following the standard {@code @ConditionalOnMissingBean} pattern.
 * <p>
 * Key convention: {@code provider.<name>.<field>}
 * (e.g. {@code provider.openai.api-key}, {@code provider.bedrock.access-key}).
 */
public interface SecretProvider {

    /**
     * Retrieve the secret value for the given logical key.
     *
     * @param key logical secret key (e.g. {@code provider.openai.api-key})
     * @return the secret value, or empty if not configured
     */
    Optional<String> getSecret(String key);

    /**
     * Check whether a secret exists for the given key without fetching the value.
     * Override in implementations that can probe existence cheaply.
     */
    default boolean hasSecret(String key) {
        return getSecret(key).isPresent();
    }


    /**
     * Evict a cached secret, forcing re-fetch on next access.
     * No-op for non-caching implementations.
     */
    default void evictCached(String key) {
        // no-op by default
    }

    /**
     * Forget every cached secret, forcing re-fetch on next access. Called when the store behind a
     * caching implementation changes wholesale. No-op for non-caching implementations.
     */
    default void evictAll() {
        // no-op by default
    }

    /**
     * Retrieve the secret value, throwing if not found.
     *
     * @param key logical secret key
     * @return the secret value
     * @throws GatewayException with code {@code CREDENTIAL_NOT_FOUND} if the key is absent
     */
    default String requireSecret(String key) {
        return getSecret(key).orElseThrow(() ->
                new GatewayException("CREDENTIAL_NOT_FOUND",
                        "Required credential not found: " + key));
    }

    // --- Workspace-aware overloads ---

    /**
     * Retrieve the secret value for the given key, scoped to a workspace.
     *
     * <p>An implementation that holds per-workspace credentials checks those first and falls back to
     * the installation-wide value. One that does not returns the installation-wide value whatever
     * workspace asks — which is what the default below does, and what every caller gets unless
     * something supplies a source of workspace credentials.
     *
     * <p><b>So the second argument may genuinely not matter, depending on the assembly.</b> Where
     * configuration comes from a file, a workspace may carry its own credentials and this resolves
     * them; where it comes from a database, that is the store's concern and this is the platform and
     * environment fallback beneath it. Worth knowing before reading a call site and concluding the
     * workspace is being honoured.
     *
     * <p><b>Absence falls back; it never refuses.</b> A workspace with no credential of its own uses
     * the installation-wide one. Requiring a workspace to bring its own is a separate decision and a
     * separate switch, not something expressed by returning empty here.
     */
    default Optional<String> getSecret(String key, String workspaceId) {
        return getSecret(key);
    }

    default boolean hasSecret(String key, String workspaceId) {
        return getSecret(key, workspaceId).isPresent();
    }


    default void evictCached(String key, String workspaceId) {
        evictCached(key);
    }

    default String requireSecret(String key, String workspaceId) {
        return getSecret(key, workspaceId).orElseThrow(() ->
                new GatewayException("CREDENTIAL_NOT_FOUND",
                        "Required credential not found: " + key
                                + (workspaceId != null ? " for workspace " + workspaceId : " (platform default)")));
    }
}