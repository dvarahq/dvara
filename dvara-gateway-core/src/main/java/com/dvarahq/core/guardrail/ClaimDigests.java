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
package com.dvarahq.core.guardrail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Stand-ins for ungrounded claims in an audit record. A claim is a sentence of the model's answer, taken
 * before PII redaction runs, and the audit chain is append-only; the record keeps a short SHA-256 of each
 * claim so the same claim can be matched across events without the text being stored.
 */
public final class ClaimDigests {

    private ClaimDigests() {
    }

    /** The first 16 hex characters of each claim's SHA-256, in order. */
    public static List<String> of(List<String> claims) {
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
        return claims.stream()
                .map(c -> HexFormat.of().formatHex(sha.digest((c == null ? "" : c).getBytes(StandardCharsets.UTF_8)))
                        .substring(0, 16))
                .toList();
    }
}
