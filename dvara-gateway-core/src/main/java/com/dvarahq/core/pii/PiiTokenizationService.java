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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

import java.util.List;

/**
 * Reversible tokenization, whole: the optional service behind {@link PiiAction#TOKENIZE}.
 *
 * <p>The implementation owns everything about a token: the format, minting, recognising one in a
 * response, and resolving it. The gateway knows only that a workspace asked for TOKENIZE and whether
 * something can do it. {@code PiiScanService} resolves the workspace's action, refuses when no
 * implementation is present, and applies {@code pii.degraded-action} when one is present and
 * fails.</p>
 *
 * <p>Irreversible redaction never comes through here. {@link PiiAction#REDACT} replaces a value
 * with a placeholder, stores nothing, needs no key and cannot fail.</p>
 *
 * <h2>Not implemented in this build</h2>
 *
 * <p>Nothing in this repository implements this interface; another module or the application may
 * register an implementation, and it is then used. With no implementation, a workspace asking for
 * TOKENIZE is refused with PII_TOKENIZE_UNAVAILABLE rather than served with its data untouched.
 * BLOCK, LOG and REDACT are unaffected.
 */
public interface PiiTokenizationService {

    /**
     * Replaces each detected span in {@code text} with a token that can be resolved later.
     *
     * <p>The implementation owns the token format — a vault tokenizer mints an opaque identifier and
     * records the mapping, a self-contained one derives the token from the value's own ciphertext
     * and records nothing — which is why this takes the text and the spans rather than returning
     * tokens for a caller to splice in.</p>
     *
     * @throws PiiTokenizationUnavailableException when no key, store or reachable control plane can
     *     back it. The caller applies the workspace's {@code pii.degraded-action} — refusing by
     *     default, because sending the raw value upstream inverts what the workspace asked for at
     *     the one moment they cannot observe that it happened.
     */
    String tokenize(String text, List<PiiEntity> entities, String workspaceId);

    /**
     * Restores, in {@code response}, the tokens that {@code request} carried.
     *
     * <p>Scoped to the request's own tokens deliberately: a token the model invented, or one
     * belonging to another conversation, is left as it is rather than resolved. Reporting what could
     * NOT be resolved is part of the contract — a placeholder returned as if it were content is the
     * failure this path exists to avoid — so the implementation owns the
     * {@code X-Gateway-Pii-Unresolved} header and the detokenize audit events, which is where the
     * counts are known.</p>
     *
     * <p>Returns {@code response} unchanged when there is nothing to restore.</p>
     */
    ChatResponse detokenizeResponse(ChatResponse response, ChatRequest request, String workspaceId);
}
