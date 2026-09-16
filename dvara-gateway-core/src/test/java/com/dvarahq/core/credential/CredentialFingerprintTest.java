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
package com.dvarahq.core.credential;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The properties an abuse investigation depends on.
 *
 * <p>Each test here corresponds to a step in the runbook, because a fingerprint that is subtly wrong
 * fails at exactly one moment — the one where a provider has already blocked our egress ranges and
 * someone is trying to name the workspace responsible.
 */
class CredentialFingerprintTest {

    private static final String KEY = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789";

    @Test
    void theSameKeyAlwaysProducesTheSameFingerprint() {
        // The whole mechanism. A provider names a key; we hash it and look it up. If this were not
        // stable across processes and releases, the lookup would return nothing and the answer would
        // be indistinguishable from "no workspace used it".
        assertThat(CredentialFingerprint.of(KEY)).isEqualTo(CredentialFingerprint.of(KEY));
    }

    @Test
    void differentKeysProduceDifferentFingerprints() {
        assertThat(CredentialFingerprint.of(KEY))
                .isNotEqualTo(CredentialFingerprint.of(KEY + "x"));
    }

    @Test
    void theFingerprintDoesNotContainTheKey() {
        // Stated as a test because the failure mode is a forensic column that quietly becomes a
        // credential store — readable by anyone with database access, and shipped off the box.
        String fp = CredentialFingerprint.of(KEY);
        assertThat(fp).doesNotContain(KEY);
        assertThat(fp).doesNotContain("sk-proj");
        assertThat(fp).hasSize(24).matches("[0-9a-f]+");   // 12 bytes, lowercase hex
    }

    @Test
    void noCredentialMeansNoFingerprint() {
        // Null is a meaningful value on the record, not a missing one: a cache hit or a secret-less
        // provider made no attributable upstream call. Inventing an identity for those would put
        // rows in the forensic record that correspond to nothing.
        assertThat(CredentialFingerprint.of(null)).isNull();
        assertThat(CredentialFingerprint.of("")).isNull();
        assertThat(CredentialFingerprint.of("   ")).isNull();
    }

    @Test
    void theValueIsPinnedAcrossReleases() {
        // A golden vector, and the only test here that would fail on a well-intentioned change.
        // Altering the salt or the truncation silently breaks every historical lookup: rows written
        // before the change stop matching keys hashed after it, nothing errors, and the damage is
        // only visible during an abuse investigation months later. Asserting equality against
        // another call would pass through any such change — the literal is the point.
        //
        // If this needs updating, that is the conversation it exists to force: every
        // credential_fingerprint already written becomes unmatchable.
        assertThat(CredentialFingerprint.of("dvara-test-vector"))
                .isEqualTo("375a04dd022045bab8a14476");
    }
}