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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A file that uses the legacy {@code key:} or {@code generate:} fields is refused, not ignored.
 *
 * <p>{@code ApiKeyEntry} ignores unknown fields, so an unrecognised {@code key:} entry would bind to
 * nothing: the gateway would start with no keys and, because {@code require-api-key} defaults to
 * false, serve every caller as anonymous with no workspace, so no per-workspace control would apply.
 */
class LegacyApiKeyFieldsAreRefusedTest {

    @TempDir
    Path dir;

    @Test
    void aPlaintextKeyIsRefusedAndTheMessageNamesTheWayOut() {
        assertThatThrownBy(() -> load("""
                api_keys:
                  - key: gw_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0
                    name: legacy
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("legacy")
                .hasMessageContaining("--hash-key")
                .as("the migration must not cost the operator their callers")
                .hasMessageContaining("keep the same key");
    }

    @Test
    void generateTrueIsRefusedAndTheMessageSaysWhatToDoInstead() {
        assertThatThrownBy(() -> load("""
                api_keys:
                  - generate: true
                    name: dev
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dev")
                .as("trying the gateway needs no key at all")
                .hasMessageContaining("send no key at all")
                .hasMessageContaining("--generate-key");
    }

    @Test
    void aFingerprintThatIsNotOneIsRefusedAtStartupRatherThanNeverMatching() {
        assertThatThrownBy(() -> load("""
                api_keys:
                  - key_hash: sha256:nothex
                    name: typo
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typo")
                .hasMessageContaining("64");

        assertThatThrownBy(() -> load("""
                api_keys:
                  - key_hash: 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08
                    name: no-prefix
                """))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sha256:");
    }

    @Test
    void theValidatorReportsEveryOffendingEntryAtOnce() throws IOException {
        GatewayYamlConfig config = parse("""
                api_keys:
                  - key: gw_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0
                    name: one
                  - generate: true
                    name: two
                """);

        assertThat(GatewayYamlLoader.validate(config))
                .as("one boot should tell an operator about every entry, not the first")
                .hasSize(2);
    }

    private GatewayYamlConfig parse(String yaml) throws IOException {
        Path file = Files.writeString(dir.resolve("gateway-" + yaml.hashCode() + ".yaml"), yaml);
        return GatewayYamlLoader.load(Map.of("DVARA_CONFIG_FILE", file.toString())::get).orElseThrow();
    }

    private void load(String yaml) throws IOException {
        new YamlConfigStore(parse(yaml));
    }
}
