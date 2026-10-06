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

import java.util.OptionalInt;

/**
 * The context window of a single model, where it is known.
 *
 * <p>A provider declares one window for everything it serves ({@link ProviderCapabilities#maxContextTokens()}),
 * and models of the same provider differ widely: one accepts 128,000 tokens and another a million. The
 * context-window check asks every bean of this type, in order, for the model's own window, and uses the
 * provider's declared window only when none of them knows the model.
 *
 * <p>The gateway registers one bean that answers from configuration
 * ({@code dvara.llm-gateway.model-limits}), ordered first so that what an operator writes wins. An
 * application may add others, such as a lookup in a maintained model catalogue; give one an
 * order ({@code Ordered} or {@code @Order}) after the configured one to keep that precedence.
 */
@FunctionalInterface
public interface ModelContextLimits {

    /**
     * @param provider the name of the provider that would serve the call ({@link LlmProvider#name()})
     * @param model    the model as the caller sends it, prefix included where a provider is reached by one
     * @return the model's context window in tokens, or empty when this source does not know the model
     */
    OptionalInt contextTokens(String provider, String model);
}
