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
package com.dvarahq.core.provider;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.routing.RequestContext;

import java.util.List;

@FunctionalInterface
public interface RoutingStrategy {

    LlmProvider route(ChatRequest request, List<LlmProvider> providers);

    /**
     * Route with explicit context for virtual-thread-safe state passing.
     * Strategies that need to set resolved model (e.g. intelligent routing)
     * should override this method and write to {@code ctx}.
     */
    default LlmProvider route(ChatRequest request, List<LlmProvider> providers, RequestContext ctx) {
        return route(request, providers);
    }
}