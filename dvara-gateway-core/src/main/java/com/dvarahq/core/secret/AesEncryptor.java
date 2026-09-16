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

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;

/**
 * AES-256-GCM encryption utility with PBKDF2 key derivation.
 * <p>
 * Wire format: {@code Base64(salt(16) || iv(12) || ciphertext+tag)}
 * <p>
 * Uses only JDK {@code javax.crypto} — no external dependencies.
 */
public final class AesEncryptor {

    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int KEY_LENGTH = 256;
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";

    private static final SecureRandom RANDOM = new SecureRandom();

    private AesEncryptor() {}

    /**
     * Encrypt plaintext using a master password.
     *
     * @param plaintext the value to encrypt
     * @param password  the master password for key derivation
     * @return Base64-encoded ciphertext (salt + iv + encrypted data + GCM tag)
     */
    public static String encrypt(String plaintext, String password) {
        try {
            byte[] salt = new byte[SALT_LENGTH];
            RANDOM.nextBytes(salt);

            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            SecretKey key = deriveKey(password, salt);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] result = new byte[SALT_LENGTH + IV_LENGTH + ciphertext.length];
            System.arraycopy(salt, 0, result, 0, SALT_LENGTH);
            System.arraycopy(iv, 0, result, SALT_LENGTH, IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, SALT_LENGTH + IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypt a value previously encrypted with {@link #encrypt(String, String)}.
     *
     * @param encoded  Base64-encoded ciphertext
     * @param password the master password used during encryption
     * @return the original plaintext
     * @throws IllegalArgumentException if decryption fails (wrong password or tampered data)
     */
    public static String decrypt(String encoded, String password) {
        try {
            byte[] data = Base64.getDecoder().decode(encoded);
            if (data.length < SALT_LENGTH + IV_LENGTH + 1) {
                throw new IllegalArgumentException("Invalid encrypted data: too short");
            }

            byte[] salt = new byte[SALT_LENGTH];
            byte[] iv = new byte[IV_LENGTH];
            byte[] ciphertext = new byte[data.length - SALT_LENGTH - IV_LENGTH];

            System.arraycopy(data, 0, salt, 0, SALT_LENGTH);
            System.arraycopy(data, SALT_LENGTH, iv, 0, IV_LENGTH);
            System.arraycopy(data, SALT_LENGTH + IV_LENGTH, ciphertext, 0, ciphertext.length);

            SecretKey key = deriveKey(password, salt);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);

            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Decryption failed: wrong password or corrupted data", e);
        }
    }

    /**
     * Derive a key once, for callers on a hot path.
     *
     * <p>{@link #encrypt(String, String)} runs a deliberately expensive PBKDF2 derivation on every
     * call, which is right for cold paths (provider credentials, admin forms) and wrong per-call on
     * a request path, where it lands in time-to-first-token.
     *
     * <p><b>The salt must be stable for the caller's domain</b>, or nothing is saved: derive it with
     * {@link #stableSalt(String, String)} and hold the returned key for the process lifetime.
     *
     * @param password the master password
     * @param salt     a stable salt for this caller's domain
     * @return a key usable with {@link #encrypt(String, SecretKey, byte[])}
     */
    public static SecretKey deriveKeyOnce(String password, byte[] salt) {
        try {
            return deriveKey(password, salt);
        } catch (Exception e) {
            throw new IllegalStateException("Key derivation failed", e);
        }
    }

    /**
     * A salt that is stable for a (domain, password) pair and unique per deployment.
     *
     * <p>A salt need not be secret or random; its job is to stop precomputation being shared across
     * targets. Mixing the master password in is what provides that here: the salt differs per
     * deployment because the password does. A hardcoded constant would give every install the same
     * derivation target; a per-row random salt cannot be cached. The domain label separates callers,
     * so the PII token key and any other caller's key are unrelated even under one master password.
     *
     * <p><b>It costs a full KDF, and that is the point.</b> The salt is written into every envelope
     * in the clear and the domain is a compile-time constant, so a salt that is a cheap hash of the
     * password would hand anyone holding a stored value a verifier to test candidate passwords
     * against for the price of one hash. Deriving it with PBKDF2 at the same cost as the key makes
     * testing a candidate against the salt cost what testing it against the key costs. The KDF's own
     * salt is the domain digest, public and fixed; the password remains the only per-deployment
     * input.
     *
     * <p>An envelope written under a different salt does not match, which
     * {@link #decrypt(String, SecretKey, byte[])} reports as {@code null}, the signal to fall back to
     * {@link #decrypt(String, String)}, which derives from the envelope's own embedded salt.
     */
    public static byte[] stableSalt(String domain, String password) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] kdfSalt = digest.digest(domain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            KeySpec spec = new PBEKeySpec(password.toCharArray(), kdfSalt,
                    PBKDF2_ITERATIONS, SALT_LENGTH * 8);
            return SecretKeyFactory.getInstance(PBKDF2_ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("Salt derivation failed", e);
        }
    }

    /**
     * Encrypt with an already-derived key, writing {@code salt} into the envelope unchanged.
     *
     * <p><b>The wire format is byte-identical to {@link #encrypt(String, String)}</b> — the salt is
     * still stored alongside the IV even though this caller did not generate it per-value. That is
     * deliberate and is what makes the fast path a drop-in: a value written here is readable by
     * {@link #decrypt(String, String)} with only the master password, so nothing needs migrating, no
     * column changes shape, and a rollback to the slow path reads new rows correctly.
     *
     * <p><b>One key may encrypt at most 2<sup>32</sup> values.</b> The IV is a fresh 96-bit random
     * value per call, and for that construction NIST SP 800-38D caps invocations at that count, past
     * which the chance of an IV repeating stops being negligible; a repeated IV under one GCM key
     * leaks the keystream and the authentication subkey. The password path has no such bound because
     * it draws a fresh random salt per value. No current caller is near the limit; a caller that
     * encrypts per request rather than per entity should check.
     *
     * @param plaintext the value to encrypt
     * @param key       from {@link #deriveKeyOnce(String, byte[])}
     * @param salt      the salt the key was derived from — must match, or the value is unreadable
     */
    public static String encrypt(String plaintext, SecretKey key, byte[] salt) {
        if (salt == null || salt.length != SALT_LENGTH) {
            throw new IllegalArgumentException("salt must be " + SALT_LENGTH + " bytes");
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(
                    plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] result = new byte[SALT_LENGTH + IV_LENGTH + ciphertext.length];
            System.arraycopy(salt, 0, result, 0, SALT_LENGTH);
            System.arraycopy(iv, 0, result, SALT_LENGTH, IV_LENGTH);
            System.arraycopy(ciphertext, 0, result, SALT_LENGTH + IV_LENGTH, ciphertext.length);

            return Base64.getEncoder().encodeToString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypt with an already-derived key, if the envelope's salt matches the one it came from.
     *
     * <p>Returns {@code null} when the embedded salt differs — meaning the value predates the caller's
     * stable salt, or was written by the password path with a random one. That is a <b>signal to fall
     * back</b> to {@link #decrypt(String, String)}, not an error: a store that switches to a derived
     * key still has to read everything it wrote before.
     *
     * <p>Returning null rather than attempting the decryption is the point — with a mismatched key
     * GCM authentication would fail and be indistinguishable from tampering, so the caller could not
     * tell "old row" from "corrupted row" and would have to treat both the same.
     *
     * @return the plaintext, or {@code null} if this key does not apply to this value
     */
    public static String decrypt(String encoded, SecretKey key, byte[] salt) {
        byte[] data = Base64.getDecoder().decode(encoded);
        if (data.length < SALT_LENGTH + IV_LENGTH + 1) {
            throw new IllegalArgumentException("Invalid encrypted data: too short");
        }
        if (!java.security.MessageDigest.isEqual(
                java.util.Arrays.copyOfRange(data, 0, SALT_LENGTH), salt)) {
            return null;
        }
        try {
            byte[] iv = java.util.Arrays.copyOfRange(data, SALT_LENGTH, SALT_LENGTH + IV_LENGTH);
            byte[] ciphertext = java.util.Arrays.copyOfRange(data, SALT_LENGTH + IV_LENGTH, data.length);

            Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("Decryption failed: corrupted data", e);
        }
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }
}