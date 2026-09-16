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
 * Action to take when PII is detected.
 */
public enum PiiAction {

    /** Refuse the request. */
    BLOCK,

    /**
     * Replace each detected value with an irreversible placeholder — {@code [REDACTED_EMAIL]} and
     * so on. The original is not stored anywhere and cannot be recovered by anyone, including us.
     * Redaction needs no encryption password, no key management and no reachable control plane, so
     * it cannot degrade. Reversible substitution is {@link #TOKENIZE}.</p>
     */
    REDACT,

    /**
     * Replace each detected value with an opaque token whose original is recoverable through an
     * encrypted store — pseudonymisation rather than removal.
     *
     * <p>Reversible by design, which is what makes it useful: a response can be detokenized before
     * it reaches the caller, so a model never sees the value while the caller still does. It needs
     * key material and durable storage, and a build without them refuses the action outright rather
     * than quietly doing something weaker.</p>
     */
    TOKENIZE,

    /** Record the detection and pass the request through unchanged. */
    LOG
}