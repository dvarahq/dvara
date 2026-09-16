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
package com.dvarahq.server;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Supplies an {@link ObjectProvider} for a collaborator a test constructs directly.
 *
 * <p>The request path takes its optional collaborators — the response cache, the two cost seams, the
 * priority controller — as providers resolved once at construction, so that a build without them has
 * no bean rather than a bean that does nothing. A test constructing those classes by hand needs the
 * same shape, and {@code of(null)} is how it expresses the absent case.
 */
public final class TestProviders {

    private TestProviders() {
    }

    public static <T> ObjectProvider<T> of(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getObject() { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }

            // Needed when a consumer asks for every bean rather than the one: Spring's default
            // element access throws unless a custom provider implements stream(). A null value is
            // the absent case and yields nothing, which is what an empty list of listeners means.
            @Override public java.util.stream.Stream<T> stream() {
                return value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value);
            }

            @Override public java.util.stream.Stream<T> orderedStream() { return stream(); }
        };
    }
}
