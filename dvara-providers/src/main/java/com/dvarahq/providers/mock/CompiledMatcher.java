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
 * A precompiled YAML-config matcher: the {@code when} Groovy predicate and a
 * {@code groovy:}-prefixed {@code response} are each parsed once into a reusable
 * script class. A static text response is returned as it is.
 *
 * <p>Each field is immutable and the matcher is safe to share across threads.
 */
public final class CompiledMatcher implements MockMatcher {

    private final String name;
    private final MatcherEvaluator predicate;
    private final ResponseEvaluator response;

    /**
     * @param name      human-readable label used in logs and match telemetry
     * @param predicate precompiled Groovy predicate evaluator
     * @param response  static text or {@code groovy:}-prefixed script
     */
    public CompiledMatcher(String name, MatcherEvaluator predicate, String response) {
        this.name = name;
        this.predicate = predicate;
        this.response = ResponseEvaluator.compile(response);
    }

    @Override
    public String name() {
        return name;
    }

    public MatcherEvaluator predicate() {
        return predicate;
    }

    /** The response configuration as given: static text or a {@code groovy:}-prefixed script. */
    public String response() {
        return response.source();
    }

    @Override
    public boolean matches(ChatRequest request) {
        return predicate.matches(request);
    }

    @Override
    public String respond(ChatRequest request) {
        return response.evaluate(request);
    }
}