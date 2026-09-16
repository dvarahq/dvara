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
package com.dvarahq.core.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class DigestUtilsTest {

    @Test
    void sha256String_deterministic() {
        String hash1 = DigestUtils.sha256("hello world");
        String hash2 = DigestUtils.sha256("hello world");
        assertThat(hash1).isEqualTo(hash2).hasSize(64);
    }

    @Test
    void sha256String_differentInputsDifferentHash() {
        assertThat(DigestUtils.sha256("abc")).isNotEqualTo(DigestUtils.sha256("def"));
    }

    @Test
    void sha256Bytes_matchesStringOverload() {
        String text = "test input";
        assertThat(DigestUtils.sha256(text))
                .isEqualTo(DigestUtils.sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void sha256_knownVector() {
        // SHA-256 of empty string is well-known
        assertThat(DigestUtils.sha256(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}