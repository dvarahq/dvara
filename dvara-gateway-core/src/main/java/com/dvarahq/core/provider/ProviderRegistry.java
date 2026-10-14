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
package com.dvarahq.core.provider;

import java.util.List;
import java.util.function.Supplier;

/**
 * The providers this gateway can send a call to, read each time they are needed.
 *
 * <p>The request path, the status endpoint and the readiness check ask {@link #current()} on every use rather than
 * keeping a list from startup, so a build that adds, replaces or removes providers while running is seen at once.
 * The default holds the providers registered at startup and never changes.
 */
public interface ProviderRegistry {

    /** The providers now registered, in order. Never null; the caller must not modify it. */
    List<LlmProvider> current();

    /** A registry that always answers the same providers. */
    static ProviderRegistry of(List<LlmProvider> providers) {
        List<LlmProvider> fixed = List.copyOf(providers);
        return () -> fixed;
    }

    /** A registry that reads its providers once, on first use, and then always answers them. */
    static ProviderRegistry lazily(Supplier<List<LlmProvider>> providers) {
        return new ProviderRegistry() {
            private volatile List<LlmProvider> fixed;

            @Override
            public List<LlmProvider> current() {
                List<LlmProvider> read = fixed;
                if (read == null) {
                    synchronized (this) {
                        if (fixed == null) {
                            fixed = List.copyOf(providers.get());
                        }
                        read = fixed;
                    }
                }
                return read;
            }
        };
    }
}
