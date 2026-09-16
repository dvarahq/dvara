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
package com.dvarahq.autoconfigure.config.yaml.repo;

import com.dvarahq.autoconfigure.config.yaml.GatewayYamlConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The file's `credentials:` map reaches the store, keyed by workspace. */
class WorkspaceCredentialsFromYamlTest {

    private static YamlConfigStore storeFrom(String yaml) throws Exception {
        return new YamlConfigStore(new ObjectMapper(new YAMLFactory())
                .readValue(yaml, GatewayYamlConfig.class));
    }

    @Test
    void eachWorkspaceKeepsItsOwnCredentials() throws Exception {
        YamlConfigStore store = storeFrom("""
                workspaces:
                  - id: acme
                    credentials:
                      provider.openai.api-key: acme-openai
                      provider.anthropic.api-key: acme-anthropic
                  - id: globex
                    credentials:
                      provider.openai.api-key: globex-openai
                """);

        assertThat(store.credentialFor("acme", "provider.openai.api-key")).contains("acme-openai");
        assertThat(store.credentialFor("acme", "provider.anthropic.api-key")).contains("acme-anthropic");
        assertThat(store.credentialFor("globex", "provider.openai.api-key")).contains("globex-openai");

        // globex brought no Anthropic key: empty means fall back, not refuse.
        assertThat(store.credentialFor("globex", "provider.anthropic.api-key")).isEmpty();
    }

    @Test
    void aWorkspaceWithNoCredentialsBlockHasNone() throws Exception {
        YamlConfigStore store = storeFrom("""
                workspaces:
                  - id: acme
                """);

        assertThat(store.credentialFor("acme", "provider.openai.api-key")).isEmpty();
    }

    @Test
    void anUnresolvedPlaceholderIsDroppedRatherThanStoredBlank() throws Exception {
        // An unset ${VAR} arrives as an empty string. Storing it would shadow the installation-wide
        // credential with nothing, and the workspace would send no key upstream at all -- which is
        // worse than the fallback it was trying to replace.
        YamlConfigStore store = storeFrom("""
                workspaces:
                  - id: acme
                    credentials:
                      provider.openai.api-key: ""
                """);

        assertThat(store.credentialFor("acme", "provider.openai.api-key")).isEmpty();
    }

    @Test
    void anUnknownWorkspaceOrNullHasNone() throws Exception {
        YamlConfigStore store = storeFrom("""
                workspaces:
                  - id: acme
                    credentials:
                      provider.openai.api-key: acme-openai
                """);

        assertThat(store.credentialFor("nobody", "provider.openai.api-key")).isEmpty();
        assertThat(store.credentialFor(null, "provider.openai.api-key")).isEmpty();
        assertThat(store.credentialFor("acme", null)).isEmpty();
    }
}
