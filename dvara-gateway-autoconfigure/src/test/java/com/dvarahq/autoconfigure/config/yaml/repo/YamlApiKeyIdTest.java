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
import com.dvarahq.autoconfigure.config.yaml.GatewayYamlLoader;
import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An unnamed file-config key's id is derived from its hash and is never shaped like the display
 * prefix ({@code gw_} plus eight hex). The spend counter treats an id of that shape as a legacy
 * attribution to drop, so a real key must not look like one.
 */
class YamlApiKeyIdTest {

    @TempDir
    Path dir;

    @Test
    void anUnnamedKeysIdIsStableAndNotPrefixShaped() throws IOException {
        String plaintext = ApiKeyGenerator.generatePlaintext();
        String yaml = """
                workspaces:
                  - id: production
                    name: Production
                api_keys:
                  - key_hash: sha256:%s
                    workspace: production
                """.formatted(ApiKeyGenerator.hash(plaintext));

        ApiKey first = load(yaml, "a").findByKeyHash(ApiKeyGenerator.hash(plaintext)).orElseThrow();
        ApiKey second = load(yaml, "b").findByKeyHash(ApiKeyGenerator.hash(plaintext)).orElseThrow();

        assertThat(first.getId()).doesNotMatch("gw_[0-9a-f]{8}").startsWith("key-").hasSize(20);
        assertThat(first.getId()).as("the same file yields the same id on every load").isEqualTo(second.getId());
        assertThat(first.getKeyPrefix())
                .as("there is no plaintext to take a display prefix from, and inventing one from the "
                        + "hash would look like a key and match nothing")
                .isEmpty();
        assertThat(first.getScopes()).as("no scopes in the file means unrestricted, not completions:write").isEmpty();
    }

    @Test
    void anExplicitNameCannotUseTheReservedLegacyPrefixShape() throws IOException {
        String yaml = """
                workspaces:
                  - id: production
                    name: Production
                api_keys:
                  - name: gw_0123abcd
                    key_hash: sha256:%s
                    workspace: production
                """.formatted(ApiKeyGenerator.hash(ApiKeyGenerator.generatePlaintext()));

        Path file = dir.resolve("gateway-reserved-name.yaml");
        Files.writeString(file, yaml);
        GatewayYamlConfig config = GatewayYamlLoader.load(
                Map.of("DVARA_CONFIG_FILE", file.toString())::get).orElseThrow();

        assertThat(GatewayYamlLoader.validate(config))
                .containsExactly("api_keys[0].name: must not have the reserved legacy API-key prefix shape "
                        + "'gw_<8 lowercase hex characters>'");
    }

    private YamlApiKeyRepository load(String yaml, String suffix) throws IOException {
        Path file = dir.resolve("gateway-" + suffix + ".yaml");
        Files.writeString(file, yaml);
        GatewayYamlConfig config = GatewayYamlLoader.load(Map.of("DVARA_CONFIG_FILE", file.toString())::get).orElseThrow();
        assertThat(GatewayYamlLoader.validate(config)).as("the fixture must itself be valid").isEmpty();
        return new YamlApiKeyRepository(new YamlConfigStore(config));
    }
}
