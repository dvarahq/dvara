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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;

/**
 * Evaluates Groovy boolean predicates for mock matchers. The predicate source
 * is compiled once at matcher construction time, so repeat evaluations do not
 * re-parse the script.
 *
 * <p>Thread-safe: each call creates a fresh {@link Script} instance from the
 * precompiled class. The compiled class itself is immutable and shared.
 */
public final class MatcherEvaluator {

    /** Immutable source string retained for debugging and error messages. */
    private final String source;

    /** Compiled Groovy script class — instantiated fresh per evaluation to stay thread-safe. */
    private final Class<? extends Script> scriptClass;

    @SuppressWarnings("unchecked")
    public MatcherEvaluator(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Mock matcher predicate source must not be blank");
        }
        this.source = source;
        try {
            GroovyShell shell = new GroovyShell();
            this.scriptClass = (Class<? extends Script>) shell.parse(source).getClass();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Mock matcher predicate failed to compile: " + source + " (" + e.getMessage() + ")", e);
        }
    }

    /**
     * Evaluates the predicate against a chat request. Returns {@code true} only
     * when the script returns exactly {@code Boolean.TRUE}. Any other return
     * value — {@code false}, {@code null}, a non-boolean — is treated as a
     * non-match, not an error.
     *
     * @throws GatewayException wrapping {@code PROVIDER_ERROR} when the script
     *         throws during evaluation (bug in the user's predicate)
     */
    public boolean matches(ChatRequest request) {
        Binding binding = new Binding();
        binding.setVariable("request", request);
        try {
            Script instance = scriptClass.getDeclaredConstructor().newInstance();
            instance.setBinding(binding);
            Object result = instance.run();
            return Boolean.TRUE.equals(result);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("PROVIDER_ERROR",
                    "Mock matcher predicate evaluation failed for '" + source + "': " + e.getMessage(), e);
        }
    }

    public String source() {
        return source;
    }
}