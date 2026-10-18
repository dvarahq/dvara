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
package com.dvarahq.core.resilience;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.EmbeddingRequest;

/**
 * Decides whether a fallback may receive a request. The primary passed the gateway's checks with the model the
 * caller asked for; a fallback may be another provider and another model, so every guard is asked again about the
 * request as that fallback would receive it. A guard that refuses keeps the request off that target; the next
 * target is tried. The first target is asked of {@link PrimaryTargetGuard}.
 *
 * <p>Every fallback path asks: a matched route's fallback chain, the providers tried for a request that matched no
 * route, and, through {@link #refuseEmbedding}, the providers tried for an embeddings request.
 *
 * <p>A guard can also name the caller's error ({@link #error}). When the first provider failed, no fallback served
 * the request, and a guard that names an error refused a fallback, the caller gets the first such error instead of
 * the first provider's, and {@link #refused} is called once. A guard that names no error only skips targets, and
 * the caller gets the first provider's error, as before.
 *
 * <p>{@link #refuse} must not have side effects the request already caused once, such as a second audit event or a
 * second budget charge: it decides, it does not act. {@link #refused} is the place to record. Several guards may be
 * registered; each must allow.
 */
@FunctionalInterface
public interface FallbackTargetGuard {

    /**
     * Why {@code request}, as the fallback would receive it (its model already mapped), may not go to
     * {@code provider}, or null when it may.
     */
    String refuse(ChatRequest request, String provider);

    /**
     * The error the caller gets when this guard's refusal decides the answer, or null to leave the first provider's
     * error. Asked only after {@link #refuse} returned {@code reason}. Must not have side effects.
     *
     * @param request  the request as the refused fallback would have received it
     * @param provider the refused provider
     * @param reason   what {@link #refuse} returned
     */
    default GatewayException error(ChatRequest request, String provider, String reason) {
        return null;
    }

    /**
     * Called once when the caller is answered with this guard's {@link #error}: no fallback served the request. The
     * place to record the refusal, for example in an audit trail. An exception it throws is logged and ignored.
     */
    default void refused(ChatRequest request, String provider, GatewayException error) {
    }

    /**
     * Why an embeddings request may not fall back to {@code provider}, or null when it may. Allows by default. A
     * refusal here only skips the provider; the caller gets the first provider's error when nothing serves it.
     */
    default String refuseEmbedding(EmbeddingRequest request, String provider) {
        return null;
    }
}
