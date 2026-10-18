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
 * Told when a fallback served a chat request after the first provider failed, for an extension that allows a
 * fallback but must record it, for example a request served outside its usual region. A {@link FallbackTargetGuard}
 * may not record, because it is asked about targets that are never used.
 *
 * <p>Called once per request that a fallback served, after the fallback's call returned (for a stream, once the
 * stream opened). It must not fail the request: an exception it throws is logged and ignored. Several may be
 * registered; none registered changes nothing.
 */
@FunctionalInterface
public interface FallbackListener {

    /**
     * @param request        the request as the fallback received it (its model already mapped)
     * @param failedProvider the provider that failed first
     * @param servedBy       the fallback provider that served it
     */
    void served(ChatRequest request, String failedProvider, String servedBy);
}
