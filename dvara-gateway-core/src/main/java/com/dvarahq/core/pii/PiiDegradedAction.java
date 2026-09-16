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
package com.dvarahq.core.pii;

/**
 * What a workspace's {@code TOKENIZE} becomes when a token cannot be minted.
 *
 * <p>Only {@code TOKENIZE} can reach this: it needs a key, a store and a reachable control plane,
 * and {@code REDACT} needs none of them, so redaction cannot be unavailable. The emitted
 * {@code PII_REDACT_DEGRADED} event type and the {@code PII_REDACT_UNAVAILABLE} error code keep
 * their older names because consumers key on the strings.
 *
 * <p>This is the one place the gateway deliberately does not fail open. Sending raw personal data to
 * a third-party provider cannot be recalled, and the workspace cannot observe it happening, so the
 * default refuses the request. A workspace that would rather serve traffic than protect the field
 * can say so in advance.
 */
public enum PiiDegradedAction {

    /**
     * Refuse the request. The default.
     *
     * <p>The client sees a failure and can retry; nothing is disclosed.
     */
    BLOCK,

    /**
     * Serve the request with the PII intact, and audit the exposure.
     *
     * <p>Opt-in only, via {@code Workspace.metadata["pii.degraded-action"] = "LOG"}, for a workspace
     * where availability outranks the field's confidentiality. The exposure is audited either way.
     */
    LOG;

    /** {@code BLOCK} for anything unrecognised — an unreadable setting must not open the door. */
    public static PiiDegradedAction fromMetadata(Object value) {
        if (value == null) {
            return BLOCK;
        }
        String text = String.valueOf(value).trim();
        return "LOG".equalsIgnoreCase(text) ? LOG : BLOCK;
    }
}