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
import groovy.lang.Closure;

import java.util.Map;

/**
 * A file-based matcher loaded from a {@code .groovy} scenario file. The
 * predicate and response are both Groovy closures captured at file load time
 * and invoked fresh on each request.
 *
 * <p>The closures receive the {@link ChatRequest} as their single argument.
 * They are expected to be pure functions (no shared mutable state) so repeated
 * concurrent invocations stay thread-safe. The loader does not enforce this;
 * users who capture mutable state in their scenario files are on their own.
 */
public final class ScenarioMatcher implements MockMatcher {

    private final String name;
    private final String sourceFile;
    private final Closure<?> whenClosure;
    private final Closure<?> respondClosure;

    ScenarioMatcher(String name, String sourceFile, Closure<?> whenClosure, Closure<?> respondClosure) {
        this.name = name;
        this.sourceFile = sourceFile;
        this.whenClosure = whenClosure;
        this.respondClosure = respondClosure;
    }

    @Override
    public String name() {
        return name;
    }

    /** Path (absolute or relative to the scenarios directory) of the .groovy file this matcher came from. */
    public String sourceFile() {
        return sourceFile;
    }

    @Override
    public boolean matches(ChatRequest request) {
        try {
            Object result = whenClosure.call(request);
            return Boolean.TRUE.equals(result);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("PROVIDER_ERROR",
                    "Mock scenario '" + name + "' from " + sourceFile + ": predicate evaluation failed: "
                            + e.getMessage(), e);
        }
    }

    @Override
    public String respond(ChatRequest request) {
        return reply(request).text();
    }

    /**
     * The closure may return the text, or a map {@code [content: '...', usage: [prompt_tokens: 1200,
     * completion_tokens: 80]]} to report that usage as given instead of the mock's estimate (#56).
     * Either token count may be left out and is then estimated.
     */
    @Override
    public MockReply reply(ChatRequest request) {
        Object result;
        try {
            result = respondClosure.call(request);
        } catch (GatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new GatewayException("PROVIDER_ERROR",
                    "Mock scenario '" + name + "' from " + sourceFile + ": response closure failed: "
                            + e.getMessage(), e);
        }
        if (!(result instanceof Map<?, ?> map)) {
            return MockReply.text(result != null ? result.toString() : "");
        }
        Object content = map.get("content");
        Map<?, ?> usage = map.get("usage") instanceof Map<?, ?> u ? u : Map.of();
        return new MockReply(content != null ? content.toString() : "",
                tokens(usage, "prompt_tokens"), tokens(usage, "completion_tokens"));
    }

    private Integer tokens(Map<?, ?> usage, String field) {
        Object v = usage.get(field);
        if (v == null) {
            return null;
        }
        if (!(v instanceof Number n) || n.intValue() < 0) {
            throw new GatewayException("PROVIDER_ERROR", "Mock scenario '" + name + "' from " + sourceFile
                    + ": usage." + field + " must be a non-negative number, got " + v);
        }
        return n.intValue();
    }
}