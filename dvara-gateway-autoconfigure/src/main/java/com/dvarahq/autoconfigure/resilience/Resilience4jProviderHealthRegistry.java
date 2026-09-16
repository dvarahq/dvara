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
package com.dvarahq.autoconfigure.resilience;

import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.resilience.ProviderHealthStatus;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Resilience4jProviderHealthRegistry implements ProviderHealthRegistry {

    private final ConcurrentMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();

    public void register(String providerName, CircuitBreaker circuitBreaker) {
        circuitBreakers.put(providerName, circuitBreaker);
    }

    @Override
    public ProviderHealthStatus getHealth(String providerName) {
        CircuitBreaker cb = circuitBreakers.get(providerName);
        if (cb == null) {
            return ProviderHealthStatus.HEALTHY;
        }
        // Grouped by what the breaker does with a call, which is resilience4j's own wording:
        // DISABLED is "not operating and allowing all requests through", and acquirePermission
        // returns true for CLOSED or DISABLED. A disabled breaker must not report its provider
        // unhealthy while every call to it is being permitted.
        return switch (cb.getState()) {
            case CLOSED, DISABLED, METRICS_ONLY -> ProviderHealthStatus.HEALTHY;
            case HALF_OPEN -> ProviderHealthStatus.DEGRADED;
            case OPEN, FORCED_OPEN -> ProviderHealthStatus.UNHEALTHY;
        };
    }
}