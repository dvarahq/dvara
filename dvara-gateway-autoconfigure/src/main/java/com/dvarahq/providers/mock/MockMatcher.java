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
 * A mock provider matcher: a named conditional rule that can decide whether
 * a given incoming request should receive a custom response, and produce that
 * response when it does. Two concrete forms exist:
 *
 * <ul>
 *   <li>{@link CompiledMatcher} — loaded from the YAML {@code dvara.llm-gateway.providers.mock.matchers}
 *       list. Carries a precompiled Groovy predicate and a response string that may be
 *       static text or a {@code groovy:}-prefixed script.</li>
 *   <li>{@link ScenarioMatcher} — loaded from a {@code .groovy} file in the configured
 *       {@code scenarios-dir}. Carries closures for both the predicate and the response
 *       producer.</li>
 * </ul>
 *
 * {@link MockProvider} evaluates every matcher in declaration order on each request and
 * the first one whose {@link #matches(ChatRequest)} returns {@code true} produces the
 * response via {@link #respond(ChatRequest)}.
 */
public interface MockMatcher {

    /** Human-readable label used in logs and match telemetry. */
    String name();

    /** Returns {@code true} when the request should be handled by this matcher. */
    boolean matches(ChatRequest request);

    /** Produces the fake response body for a matched request. */
    String respond(ChatRequest request);
}