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

import java.util.OptionalDouble;

/**
 * Records upstream latency per provider and model for latency-aware routing.
 *
 * <h2>Not implemented in this build</h2>
 *
 * <p>Nothing in this repository implements this interface; another module or the application may
 * register an implementation, and it is then used. With no implementation, a route asking for the
 * latency-aware strategy is refused by name at startup rather than quietly falling back to
 * round-robin.
 */
public interface LatencyTracker {

    void record(String provider, String model, long latencyMs);

    OptionalDouble getEwmaLatency(String provider, String model);

    default boolean hasData(String provider, String model) {
        return getEwmaLatency(provider, model).isPresent();
    }
}