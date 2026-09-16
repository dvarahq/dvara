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
 * A token could not be minted, so {@code TOKENIZE} cannot be honoured as written.
 *
 * <p><b>Typed rather than a generic failure, because the caller has to do something specific.</b>
 * Every other exception on this path means "the request failed"; this one means "the request can
 * still proceed, but only under a posture the workspace has to have chosen in advance". Catching a bare
 * {@code RuntimeException} to decide that would also catch bugs, and would silently degrade PII
 * handling on a NullPointerException — the failure mode this whole area exists to prevent.
 *
 * <p>Two conditions raise it, and they are the same shape from the caller's side:
 *
 * <ul>
 *   <li><b>No DEK.</b> The control plane is unreachable and this pod holds no cached key for the
 *       workspace — so there is nothing to encrypt the original under.</li>
 *   <li><b>The local bound is reached.</b> The pod is past its high-water mark and refuses to mint
 *       rather than mint something it may later shed. A token minted and later shed is worse
 *       than one never minted: the customer already holds the redacted text, and {@code detokenize}
 *       cannot tell a shed token from one that never existed.</li>
 * </ul>
 */
public class PiiTokenizationUnavailableException extends RuntimeException {

    public PiiTokenizationUnavailableException(String message) {
        super(message);
    }

    public PiiTokenizationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}