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

import com.dvarahq.core.util.DigestUtils;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates cryptographically secure API keys and computes SHA-256 hashes.
 * <p>
 * Key format: {@code gw_} + 40 hex characters (160 bits of entropy).
 * Only the SHA-256 hash is persisted — the plaintext is returned once at creation.
 */
public final class ApiKeyGenerator {

    private static final String KEY_PREFIX = "gw_";
    private static final int KEY_BYTES = 20; // 160 bits → 40 hex chars
    private static final int DISPLAY_PREFIX_LENGTH = 8; // e.g. "gw_a3f2b1c8..."
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private ApiKeyGenerator() {}

    /**
     * Generates a new plaintext API key.
     *
     * @return plaintext key like {@code gw_a3f2b1c8d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9}
     */
    public static String generatePlaintext() {
        byte[] bytes = new byte[KEY_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return KEY_PREFIX + HexFormat.of().formatHex(bytes);
    }

    /**
     * Computes the SHA-256 hash of a plaintext key.
     *
     * @param plaintext the full plaintext key
     * @return lowercase hex-encoded SHA-256 hash
     */
    public static String hash(String plaintext) {
        return DigestUtils.sha256(plaintext);
    }

    /**
     * Extracts the display prefix from a plaintext key (e.g. {@code "gw_a3f2b1c8"}).
     *
     * @param plaintext the full plaintext key
     * @return the prefix for display/identification purposes
     */
    public static String extractPrefix(String plaintext) {
        return plaintext.substring(0, Math.min(plaintext.length(),
                KEY_PREFIX.length() + DISPLAY_PREFIX_LENGTH));
    }
}