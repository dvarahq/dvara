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
package com.dvarahq.core.apikey;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyGeneratorTest {

    @Test
    void generatePlaintext_startsWithPrefix() {
        String key = ApiKeyGenerator.generatePlaintext();
        assertThat(key).startsWith("gw_");
    }

    @Test
    void generatePlaintext_hasExpectedLength() {
        String key = ApiKeyGenerator.generatePlaintext();
        // "gw_" (3) + 40 hex chars = 43
        assertThat(key).hasSize(43);
    }

    @Test
    void generatePlaintext_isUnique() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            keys.add(ApiKeyGenerator.generatePlaintext());
        }
        assertThat(keys).hasSize(100);
    }

    @Test
    void hash_producesConsistentSha256() {
        String key = "gw_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9";
        String hash1 = ApiKeyGenerator.hash(key);
        String hash2 = ApiKeyGenerator.hash(key);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 = 64 hex chars
    }

    @Test
    void hash_differentKeysProduceDifferentHashes() {
        String hash1 = ApiKeyGenerator.hash(ApiKeyGenerator.generatePlaintext());
        String hash2 = ApiKeyGenerator.hash(ApiKeyGenerator.generatePlaintext());

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void extractPrefix_returnsFirst11Chars() {
        String key = "gw_a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9";
        String prefix = ApiKeyGenerator.extractPrefix(key);

        // "gw_" (3) + 8 hex chars = 11
        assertThat(prefix).isEqualTo("gw_a1b2c3d4");
        assertThat(prefix).hasSize(11);
    }
}