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

/**
 * Decides whether the request may go to the provider routing picked first, as it will be sent.
 *
 * <p>The request filters check the model the caller named. A route can send another one: a strategy may pin a
 * version or pick a model tier, and the dispatcher then sends that model. A guard of this type is asked about
 * the request with the model actually sent, just before the first provider is called, so a check on the model
 * cannot be passed by naming a different one. Route fallbacks are asked of {@link FallbackTargetGuard}.
 *
 * <p>When a guard refuses and the matched route has a fallback chain, the chain is tried, its targets asked of
 * {@link FallbackTargetGuard} as usual. When no target serves the request, the caller gets the guard's error and
 * {@link #refused} is called once. The refused target is never called. With no guard registered, nothing changes.
 *
 * <p>{@link #refuse} must not have side effects: it decides. Several may be registered; the first that refuses
 * decides.
 */
public interface PrimaryTargetGuard {

    /**
     * The error the caller gets if {@code request}, as it will be sent, may not go to {@code provider}, or null
     * when it may.
     *
     * @param request  the request with the model it will be sent with
     * @param provider the name of the provider routing picked
     */
    GatewayException refuse(ChatRequest request, String provider);

    /**
     * Called once when the caller is answered with this guard's error: no fallback served the request. The place
     * to record the refusal, for example in an audit trail. Does nothing by default.
     */
    default void refused(ChatRequest request, String provider, GatewayException error) {
    }
}
