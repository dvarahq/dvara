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
package com.dvarahq.autoconfigure.config.yaml.repo;

import com.dvarahq.core.routing.CanaryMetricsCollector;

/**
 * Canary comparison metrics, which a file-configured build does not collect.
 *
 * <p>{@code ProviderDispatcher} requires a {@code CanaryMetricsCollector}, so a build with no
 * durable store still needs one. This one discards what it is given: the canary split still routes
 * traffic where the route says, but nothing in this build stores or reads the comparison. Another
 * module may register a collector that does.
 */
final class UncollectedRoutingMetrics {

    private UncollectedRoutingMetrics() {}

    static final class Canary implements CanaryMetricsCollector {
        @Override public void record(String routeId, String variant, long latencyMs, double costUsd, boolean error) {
            // Discarded: nothing in this build reads it.
        }
    }
}
