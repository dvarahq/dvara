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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.crypto.SecretKey;

/**
 * What PII tokenization crypto costs per detected entity, measured on the primitive rather than the
 * request path.
 *
 * <p>Three regimes are compared: PBKDF2 derived per call, a key derived once at construction, and a
 * per-workspace DEK supplied by {@code DekSource}. The DEK arm does identical AES work and is a
 * control that supplying the key from elsewhere does not reintroduce a derivation. Both fast arms
 * are JIT-sensitive, so they share one warm-up before either is measured; without it whichever ran
 * first reports a fraction of the other's throughput.
 *
 * <p>Off unless {@code PII_CRYPTO_BENCH=true}: a throughput figure from a machine running other work
 * measures that work. Record the output with the hardware line it prints.
 */
@EnabledIfEnvironmentVariable(named = "PII_CRYPTO_BENCH", matches = "true",
        disabledReason = "Tokenize-mode crypto benchmark — run deliberately, not in CI")
class PiiRedactCryptoBenchmarkTest {

    /** A representative PII value: long enough to be realistic, short enough that AES is not the cost. */
    private static final String PLAINTEXT = "4111111111111111 / john.doe@example.com";
    private static final String PASSWORD = "benchmark-master-password";

    /** PBKDF2 at 210k iterations costs tens of milliseconds per call, so this regime needs far fewer samples than the others. */
    private static final int DERIVING_SAMPLES = 20;
    private static final int FAST_SAMPLES = 200_000;

    /**
     * Warm-up for the two fast arms, run once before either is measured, so neither pays to
     * JIT-compile {@code encrypt} while its clock is running.
     */
    private static final int SHARED_WARMUP = 100_000;

    /**
     * All three regimes in one test, so the run emits a single self-contained block.
     *
     * <p>Deliberately not one {@code @Test} per regime: JUnit's method order is deterministic but
     * unspecified, so three tests would print three unlabelled rows in no guaranteed order, with no
     * header and no hardware line, and a row whose machine is unstated is not a measurement.
     */
    @Test
    void measureTokenizationCrypto() {
        System.out.println();
        System.out.println("=== PII tokenization crypto cost ===");
        System.out.printf("java=%s os=%s cores=%d plaintext=%d chars%n",
                System.getProperty("java.version"),
                System.getProperty("os.name"),
                Runtime.getRuntime().availableProcessors(),
                PLAINTEXT.length());
        System.out.println("regime            | samples | entities/s | ms/entity | 5-entity message");
        // ms/entity is printed to 4 decimals because the fast arms land near a thousandth of a
        // millisecond; at 3 the whole finding rounds to "0.002" and stops being a number.

        perCallPbkdf2();
        warmUpFastPath();
        derivedOnce();
        perWorkspaceDek();

        System.out.println("=== per-call PBKDF2 derives a key per entity; the rows below it use a key "
                + "derived once ===");
        System.out.println();
    }

    /** A 210,000-iteration derivation per entity. */
    private void perCallPbkdf2() {
        // Warm up the JIT and the SecretKeyFactory lookup, or the first sample carries class loading.
        for (int i = 0; i < 3; i++) {
            AesEncryptor.encrypt(PLAINTEXT, PASSWORD);
        }

        long start = System.nanoTime();
        for (int i = 0; i < DERIVING_SAMPLES; i++) {
            AesEncryptor.encrypt(PLAINTEXT, PASSWORD);
        }
        report("per-call PBKDF2", DERIVING_SAMPLES, System.nanoTime() - start);
    }

    /** What an install with no key infrastructure runs. */
    private void derivedOnce() {
        byte[] salt = AesEncryptor.stableSalt("pii-token", PASSWORD);
        SecretKey key = AesEncryptor.deriveKeyOnce(PASSWORD, salt);
        run("derived once", key, salt);
    }

    /** The control: a DEK supplied from elsewhere must cost the same as a key derived once. */
    private void perWorkspaceDek() {
        // A DEK is an AES key that arrived from DekSource rather than from a password. The crypto is
        // identical by construction; this exists so a change that made it stop being identical is
        // visible as a number rather than as an assumption.
        byte[] salt = AesEncryptor.stableSalt("pii-dek", "dek-0001");
        SecretKey dek = AesEncryptor.deriveKeyOnce("simulated-unwrapped-dek-material", salt);
        run("per-workspace DEK", dek, salt);
    }

    /** Drive the shared {@code encrypt(String, SecretKey, byte[])} path to steady state, once. */
    private void warmUpFastPath() {
        byte[] salt = AesEncryptor.stableSalt("pii-warmup", "warmup");
        SecretKey key = AesEncryptor.deriveKeyOnce("warmup-key-material", salt);
        for (int i = 0; i < SHARED_WARMUP; i++) {
            AesEncryptor.encrypt(PLAINTEXT, key, salt);
        }
    }

    private void run(String label, SecretKey key, byte[] salt) {
        for (int i = 0; i < 10_000; i++) {
            AesEncryptor.encrypt(PLAINTEXT, key, salt);
        }

        long start = System.nanoTime();
        for (int i = 0; i < FAST_SAMPLES; i++) {
            AesEncryptor.encrypt(PLAINTEXT, key, salt);
        }
        report(label, FAST_SAMPLES, System.nanoTime() - start);
    }

    private void report(String label, int samples, long elapsedNanos) {
        double msEach = elapsedNanos / 1_000_000.0 / samples;
        System.out.printf("%-17s | %7d | %10.0f | %9.4f | %13.3f ms%n",
                label, samples, 1_000.0 / msEach, msEach, msEach * 5);
    }
}