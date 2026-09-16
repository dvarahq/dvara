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
package com.dvarahq.core.guardrail;

/**
 * Callback for recording guardrail metrics without a Micrometer dependency in the module that
 * detects. The runtime provides the Micrometer-backed implementation.
 *
 * <p>There is no no-op <em>bean</em>. Consumers collect these into a list, so an empty list is the
 * honest no-op and a module registering one joins the others rather than replacing them.</p>
 *
 * <p>{@link #NOOP} is never registered in the container; it is the default a detector holds when
 * constructed without a listener.</p>
 */
public interface GuardrailMetricsListener {

    GuardrailMetricsListener NOOP = new GuardrailMetricsListener() {};

    /** A request or response the guardrail refused, once per category it was refused for. */
    default void onBlocked(String workspaceId, String category) {}

    /** A request or response the guardrail flagged and let through, once per category. */
    default void onFlagged(String workspaceId, String category) {}

    default void onMlDetection(String provider, String category, String action) {}

    /**
     * One classification performed by an {@link MlClassifierHook}, and whether it produced a
     * detection.
     *
     * <p>The denominator: {@code onMlDetection} counts hits, and a hit count alone cannot say what
     * fraction of traffic the classifier flags, which is what choosing its threshold needs.</p>
     */
    default void onClassifierScan(String provider, boolean flagged) {}

    default void onPluginDetection(String plugin, String category, String action) {}
}