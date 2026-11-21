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
package com.dvarahq.providers;

import java.util.List;

/**
 * The provider types this library has an adapter for, as a configuration names them ({@code type: openai}).
 *
 * <p>The built-in adapters only, without the mock provider, which is for tests and local runs. A page or a check that
 * lists the types an operator may choose reads this list, and needs no Spring context and no auto-configuration to do
 * so.
 */
public final class ProviderTypes {

    /** Every built-in type, in the order a list of them is shown. */
    public static final List<String> ALL = List.of("openai", "anthropic", "gemini", "azure-openai", "mistral",
            "cohere", "groq", "qwen", "deepseek", "moonshot", "chatglm", "grok", "ollama", "bedrock", "vertex-ai");

    private ProviderTypes() {
    }
}
