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

import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The derive-once path, and the compatibility properties it rests on.
 *
 * <p>The point of the raw-key API is that a caller on a request path can pay PBKDF2 once at boot
 * instead of once per value. That is only safe if the values it writes remain ordinary values:
 * readable by the password path, indistinguishable on the wire, and rollback-safe. These tests
 * assert that, not the speed.
 */
class AesEncryptorRawKeyTest {

    private static final String PASSWORD = "master-password-for-tests";
    private static final String DOMAIN = "pii-token";

    @Test
    void valueWrittenWithADerivedKeyIsReadableByThePasswordPath() {
        byte[] salt = AesEncryptor.stableSalt(DOMAIN, PASSWORD);
        SecretKey key = AesEncryptor.deriveKeyOnce(PASSWORD, salt);

        String encrypted = AesEncryptor.encrypt("4111-1111-1111-1111", key, salt);

        // The load-bearing compatibility property. If this ever fails, switching a store to the
        // fast path becomes a data migration and a rollback becomes data loss — which is exactly
        // what keeping the wire format identical is meant to avoid.
        assertEquals("4111-1111-1111-1111", AesEncryptor.decrypt(encrypted, PASSWORD));
    }

    @Test
    void valueWrittenByThePasswordPathIsNotReadableByTheDerivedKey_andSaysSoWithNull() {
        // Password-path values carry a random per-value salt, so the cached key does not apply.
        String legacy = AesEncryptor.encrypt("legacy-secret", PASSWORD);

        byte[] salt = AesEncryptor.stableSalt(DOMAIN, PASSWORD);
        SecretKey key = AesEncryptor.deriveKeyOnce(PASSWORD, salt);

        // null, not an exception: this is the signal to fall back, and a store adopting the fast
        // path must still read everything it wrote before. An exception here would be
        // indistinguishable from tampering and would force the caller to treat both the same.
        assertNull(AesEncryptor.decrypt(legacy, key, salt));
        assertEquals("legacy-secret", AesEncryptor.decrypt(legacy, PASSWORD));
    }

    @Test
    void roundTripsThroughTheDerivedKey() {
        byte[] salt = AesEncryptor.stableSalt(DOMAIN, PASSWORD);
        SecretKey key = AesEncryptor.deriveKeyOnce(PASSWORD, salt);

        String encrypted = AesEncryptor.encrypt("sensitive", key, salt);
        assertEquals("sensitive", AesEncryptor.decrypt(encrypted, key, salt));
    }

    @Test
    void sameValueEncryptsDifferentlyEachTime() {
        byte[] salt = AesEncryptor.stableSalt(DOMAIN, PASSWORD);
        SecretKey key = AesEncryptor.deriveKeyOnce(PASSWORD, salt);

        // A fixed salt must not make the scheme deterministic. The IV is still random per value,
        // so equal plaintexts do not produce equal ciphertexts — otherwise a fixed salt would leak
        // which tokens hold the same PII, which is most of what the token store is protecting.
        String a = AesEncryptor.encrypt("same-value", key, salt);
        String b = AesEncryptor.encrypt("same-value", key, salt);
        assertNotEquals(a, b);
        assertEquals("same-value", AesEncryptor.decrypt(a, key, salt));
        assertEquals("same-value", AesEncryptor.decrypt(b, key, salt));
    }

    @Test
    void saltIsStablePerDeploymentAndDistinctAcrossPasswordsAndDomains() {
        byte[] a = AesEncryptor.stableSalt(DOMAIN, PASSWORD);
        byte[] b = AesEncryptor.stableSalt(DOMAIN, PASSWORD);
        assertEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(b),
                "must be stable, or the key cannot be cached across restarts or pods");

        // Different password -> different salt: no precomputation table built against one install
        // helps against another. This is the reason the salt is not a hardcoded constant.
        assertNotEquals(java.util.Arrays.toString(a),
                java.util.Arrays.toString(AesEncryptor.stableSalt(DOMAIN, "other-password")));

        // Different domain -> different salt, so two callers under one master password get
        // unrelated keys.
        assertNotEquals(java.util.Arrays.toString(a),
                java.util.Arrays.toString(AesEncryptor.stableSalt("other-domain", PASSWORD)));
    }

    @Test
    void tamperedCiphertextIsRejectedRatherThanReturnedAsNull() {
        byte[] salt = AesEncryptor.stableSalt(DOMAIN, PASSWORD);
        SecretKey key = AesEncryptor.deriveKeyOnce(PASSWORD, salt);
        String encrypted = AesEncryptor.encrypt("sensitive", key, salt);

        // Flip a byte in the ciphertext, leaving the salt prefix intact so the key still applies.
        byte[] raw = java.util.Base64.getDecoder().decode(encrypted);
        raw[raw.length - 1] ^= 0x01;
        String tampered = java.util.Base64.getEncoder().encodeToString(raw);

        // Tampering must be an error, distinct from the null that means "not my value". Collapsing
        // the two would let a corrupted row be silently treated as a legacy one and re-read with
        // the password path, turning a detected integrity failure into a confusing decrypt error.
        assertThrows(IllegalArgumentException.class,
                () -> AesEncryptor.decrypt(tampered, key, salt));
    }

    @Test
    void derivingOnceIsDramaticallyCheaperThanDerivingPerCall() {
        // Not a benchmark, a floor: the fast path must be at least an order of magnitude cheaper
        // than deriving per call, which holds on any hardware and does not make CI timing-sensitive.
        byte[] salt = AesEncryptor.stableSalt(DOMAIN, PASSWORD);
        SecretKey key = AesEncryptor.deriveKeyOnce(PASSWORD, salt);

        long fastStart = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            AesEncryptor.encrypt("value-" + i, key, salt);
        }
        long fastNanos = System.nanoTime() - fastStart;

        long slowStart = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            AesEncryptor.encrypt("value-" + i, PASSWORD);
        }
        long slowNanos = System.nanoTime() - slowStart;

        assertTrue(slowNanos > fastNanos * 10,
                "derive-once must be at least 10x cheaper than derive-per-call; fast="
                        + fastNanos / 1_000_000 + "ms slow=" + slowNanos / 1_000_000 + "ms for 20 values");
    }

    // ---------------------------------------------- what the stable salt may reveal

    @Test
    void theStableSaltIsNotASingleHashOfThePassword() {
        // The salt is written into every envelope in the clear and the domain is a compile-time
        // constant, so a salt derived with one SHA-256 would hand anyone holding a stored value a
        // verifier they could test a candidate password against for the price of one hash, while the
        // key beside it costs 210,000 PBKDF2 iterations to attack. The cheaper of the two decides how
        // expensive guessing is.
        byte[] salt = AesEncryptor.stableSalt(DOMAIN, PASSWORD);

        byte[] oneHash = oldConstruction(DOMAIN, PASSWORD);

        assertFalse(java.util.Arrays.equals(salt, oneHash),
                "the salt must not be recomputable with a single hash of the password");
    }

    @Test
    void theStableSaltIsDeterministicForAPairAndDiffersAcrossBoth() {
        // Determinism is what makes it cacheable at all; differing by password is what stops
        // precomputation being shared between installs; differing by domain is what keeps one
        // caller's key unrelated to another's under the same master password.
        assertArrayEquals(AesEncryptor.stableSalt(DOMAIN, PASSWORD),
                AesEncryptor.stableSalt(DOMAIN, PASSWORD));

        assertFalse(java.util.Arrays.equals(AesEncryptor.stableSalt(DOMAIN, PASSWORD),
                AesEncryptor.stableSalt(DOMAIN, PASSWORD + "!")));
        assertFalse(java.util.Arrays.equals(AesEncryptor.stableSalt(DOMAIN, PASSWORD),
                AesEncryptor.stableSalt(DOMAIN + "-other", PASSWORD)));
    }

    @Test
    void theStableSaltIsStillTheEnvelopeSaltLength() {
        assertEquals(16, AesEncryptor.stableSalt(DOMAIN, PASSWORD).length);
    }

    /** The single-hash construction the salt must not be recomputable with: SHA-256(domain || 0x00 || password), truncated. */
    private static byte[] oldConstruction(String domain, String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(domain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Arrays.copyOf(digest.digest(), 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
