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

import com.dvarahq.core.model.ChatRequest;

/**
 * Decides whether a route's fallback may receive a request. The primary passed the
 * gateway's checks with the model the caller asked for; a fallback may be another provider and another
 * model, so every guard is asked again about the request as that fallback would receive it. A guard that
 * refuses keeps the request off that target; the next target in the chain is tried.
 * The first target is asked of {@link PrimaryTargetGuard}.
 *
 * <p>Implementations must not have side effects the request already caused once, such as a second audit
 * event or a second budget charge: they decide, they do not act. Several may be registered; each must allow.
 */
@FunctionalInterface
public interface FallbackTargetGuard {

    /**
     * Why {@code request}, as the fallback would receive it (its model already mapped), may not go to
     * {@code provider}, or null when it may.
     */
    String refuse(ChatRequest request, String provider);
}
