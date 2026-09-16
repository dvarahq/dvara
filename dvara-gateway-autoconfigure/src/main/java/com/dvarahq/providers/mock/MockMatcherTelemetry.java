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
package com.dvarahq.providers.mock;

import com.dvarahq.core.model.ChatRequest;

/**
 * Telemetry hook for the Mock provider's matcher system. Implementations
 * record metrics and audit events when matchers fire and when the scenarios
 * directory is reloaded. A no-op default is used when no telemetry is wired,
 * so MockProvider and MockScenarioWatcher can always call the hook without
 * a null check.
 *
 * <p>The real implementation (in the gateway application) wires this to Micrometer
 * counters and the audit writer. Keeping the interface in dvara-gateway-autoconfigure
 * avoids a Micrometer dependency in the mock-scenario code path and lets unit tests use
 * plain in-memory implementations.
 *
 * <p><b>Failure isolation:</b> every telemetry call is treated as auxiliary.
 * Callers ({@link MockProvider}, {@link MockScenarioWatcher}) wrap the hook
 * in a try/catch that logs the failure at WARN level and continues serving
 * the request. Telemetry bugs must never take down the data plane.
 */
public interface MockMatcherTelemetry {

    /**
     * A no-op implementation used when no telemetry bean is registered.
     * Shared singleton — the interface is stateless so a single instance
     * is safe across every MockProvider and watcher in the JVM.
     */
    MockMatcherTelemetry NOOP = new MockMatcherTelemetry() {};

    /**
     * Called when a matcher's predicate returns true and its response is
     * about to be produced. Implementations typically increment a labelled
     * Prometheus counter and emit an audit event.
     *
     * @param scenarioName the matcher's human-readable name
     * @param source       whether the matcher came from a {@code .groovy}
     *                     scenario file ({@link Source#FILE}) or from the
     *                     YAML configuration ({@link Source#YAML})
     * @param request      the incoming chat request — implementations may
     *                     extract {@code workspace_id} from {@code request.metadata}
     *                     to attach to the audit event envelope, but the
     *                     request object itself must not be mutated
     */
    default void matcherFired(String scenarioName, Source source, ChatRequest request) {}

    /**
     * Called when no matcher fired for a request and the configured default
     * response text was used instead. Implementations typically increment a
     * Prometheus counter so operators can compute the matcher hit rate
     * ({@code fires / (fires + fallthrough)}) on a Grafana dashboard.
     *
     * @param request the incoming chat request — implementations may extract
     *                {@code workspace_id} from {@code request.metadata} the same
     *                way as {@link #matcherFired}
     */
    default void matcherFellThrough(ChatRequest request) {}

    /**
     * Called when the scenarios directory has been successfully reloaded
     * by the filesystem watcher. A failed reload (bad syntax, missing
     * binding) does <b>not</b> trigger this hook — only successful swaps
     * that produce a new active matcher list.
     *
     * @param scenarioCount the number of scenarios in the new list
     */
    default void scenariosReloaded(int scenarioCount) {}

    /** The origin of a fired matcher — drives the {@code source} label on the Prometheus counter. */
    enum Source {
        /** Loaded from a {@code .groovy} file in the scenarios directory. */
        FILE,
        /** Loaded from the {@code dvara.llm-gateway.providers.mock.matchers} YAML block. */
        YAML
    }
}