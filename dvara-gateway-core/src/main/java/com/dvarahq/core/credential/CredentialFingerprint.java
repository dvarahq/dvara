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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * A stable, non-reversible handle for an upstream provider credential.
 *
 * <p>A gateway that proxies workspaces' own provider keys has to be able to answer a provider's
 * abuse report by mapping the key the provider names back to a workspace. That mapping cannot be
 * reconstructed after the fact, so a fingerprint is written on the metering record as the call
 * happens.
 *
 * <p><b>The salt is a fixed constant on purpose.</b> The fingerprint has to be reproducible: hash
 * the key the provider gave us and look it up. A per-install random salt would have to be stored
 * and escrowed to allow that. Secrecy against ourselves is not the property wanted;
 * non-reversibility is, and HMAC-SHA256 over a 20+ character provider key gives it.
 *
 * <p><b>96 bits</b> (12 bytes) because the question is "which of this installation's credentials
 * is it", a domain of thousands, not a global namespace.
 *
 * <p>{@code ProviderRateLimitInterceptor} keys its per-credential rate-limit windows on the same
 * value, so there is one definition of "which credential is this".
 */
public final class CredentialFingerprint {

    /** Fixed and deterministic, so every pod and every future release derives the same identity. */
    private static final byte[] SALT =
            "dvara-credential-fingerprint-v1".getBytes(StandardCharsets.UTF_8);

    /** 96-bit prefix — see the class note on why truncation is safe here. */
    private static final int BYTES = 12;

    private CredentialFingerprint() {}

    /**
     * @param secret the resolved upstream credential. Never stored, never logged.
     * @return lowercase hex, or {@code null} when there is no secret to fingerprint — a request that
     *         resolved no credential made no attributable upstream call, and inventing an identity for
     *         it would put a value in the forensic record that corresponds to nothing.
     */
    public static String of(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SALT, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(secret.getBytes(StandardCharsets.UTF_8)),
                    0, BYTES);
        } catch (java.security.GeneralSecurityException e) {
            // HmacSHA256 is mandatory on every JRE, so this is unreachable in practice. Returning
            // null rather than throwing keeps a forensic nicety from failing a customer's request.
            return null;
        }
    }
}