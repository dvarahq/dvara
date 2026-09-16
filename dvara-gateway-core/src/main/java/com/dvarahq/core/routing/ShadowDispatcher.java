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
package com.dvarahq.core.routing;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

/**
 * Dispatches shadow traffic to a secondary provider for comparison.
 *
 * <p>An implementation should dispatch asynchronously, never blocking the primary response. There
 * is no default and no no-op bean.</p>
 *
 * <h2>Not implemented in this build</h2>
 *
 * <p>Nothing in this repository implements this interface; another module or the application may
 * register an implementation, and it is then used. With no implementation, a route carrying a
 * shadow configuration is refused at startup rather than accepted and silently not mirrored.
 */
public interface ShadowDispatcher {

    /**
     * Dispatches the request to the shadow provider defined in the route's shadow config.
     *
     * @param request         the original chat request
     * @param primaryResponse the response from the primary provider
     * @param primaryLatencyMs latency of the primary provider call
     * @param route           the route config containing shadow configuration
     */
    void dispatchShadow(ChatRequest request, ChatResponse primaryResponse,
                        long primaryLatencyMs, RouteConfig route);
}