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
 * Evaluates mock response configuration — either a static string or a Groovy
 * script prefixed with {@code groovy:}. Groovy is a hard runtime dependency
 * of dvara-gateway-autoconfigure, so the engine is always available.
 *
 * <p>{@link #compile(String)} parses a script once, the way {@link MatcherEvaluator}
 * parses a predicate, and each {@link #evaluate(ChatRequest)} runs a fresh instance
 * of the compiled class, so concurrent requests do not share a binding. A script
 * that does not compile fails on the request that uses it, rather than at startup.
 */
final class ResponseEvaluator {

    private static final String GROOVY_PREFIX = "groovy:";

    /** The configuration as given, retained for debugging. May be null. */
    private final String source;

    /** The text to return, or null when the configuration is a script. */
    private final String text;

    /** The compiled script class, or null for static text or a script that did not compile. */
    private final Class<? extends Script> scriptClass;

    /** Why the script did not compile, reported on each request that uses it. */
    private final Exception compileFailure;

    private ResponseEvaluator(String source, String text, Class<? extends Script> scriptClass,
                              Exception compileFailure) {
        this.source = source;
        this.text = text;
        this.scriptClass = scriptClass;
        this.compileFailure = compileFailure;
    }

    @SuppressWarnings("unchecked")
    static ResponseEvaluator compile(String responseConfig) {
        if (responseConfig == null) {
            return new ResponseEvaluator(null, "This is a mock response", null, null);
        }
        if (!responseConfig.startsWith(GROOVY_PREFIX)) {
            return new ResponseEvaluator(responseConfig, responseConfig, null, null);
        }
        String script = responseConfig.substring(GROOVY_PREFIX.length()).trim();
        try {
            Class<? extends Script> compiled = (Class<? extends Script>) new GroovyShell().parse(script).getClass();
            return new ResponseEvaluator(responseConfig, null, compiled, null);
        } catch (Exception e) {
            return new ResponseEvaluator(responseConfig, null, null, e);
        }
    }

    /** Compiles and evaluates in one step. For a response used more than once, keep {@link #compile(String)}. */
    static String evaluate(String responseConfig, ChatRequest request) {
        return compile(responseConfig).evaluate(request);
    }

    String evaluate(ChatRequest request) {
        if (text != null) {
            return text;
        }
        try {
            if (compileFailure != null) {
                throw compileFailure;
            }
            Binding binding = new Binding();
            binding.setVariable("request", request);
            Script instance = scriptClass.getDeclaredConstructor().newInstance();
            instance.setBinding(binding);
            Object result = instance.run();
            return result != null ? result.toString() : "";
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("PROVIDER_ERROR",
                    "Groovy script evaluation failed: " + e.getMessage(), e);
        }
    }

    String source() {
        return source;
    }
}