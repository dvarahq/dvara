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
package com.dvarahq.server.filter;

import com.dvarahq.core.provider.ModelContextLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.OptionalInt;

/**
 * A model's own context window from the registered {@link ModelContextLimits} sources: the context-window
 * check and the model list read it the same way.
 */
public final class ModelWindows {

    private static final Logger log = LoggerFactory.getLogger(ModelWindows.class);

    private ModelWindows() {
    }

    /**
     * The window of the first source, in order, that knows the model; empty when none does. A source that
     * throws or answers a non-positive window is passed over.
     */
    public static OptionalInt of(List<ModelContextLimits> sources, String provider, String model) {
        for (ModelContextLimits source : sources) {
            OptionalInt window;
            try {
                window = source.contextTokens(provider, model);
            } catch (RuntimeException e) {
                // A lookup that fails must not fail the caller; the provider's window still holds.
                log.debug("Model limit source {} threw for {} on {}: {}",
                        source.getClass().getSimpleName(), model, provider, e.getMessage());
                continue;
            }
            if (window != null && window.isPresent() && window.getAsInt() > 0) {
                return window;
            }
        }
        return OptionalInt.empty();
    }
}
