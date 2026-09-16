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

import com.dvarahq.core.model.ChatRequest;

import java.util.List;

public abstract class AbstractLlmProvider implements LlmProvider {

    private final String name;

    protected AbstractLlmProvider(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean supports(ChatRequest request) {
        String model = request.getModel();
        if (model == null) return false;
        for (String prefix : modelPrefixes()) {
            if (model.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * The model-name prefixes this provider claims. The default routing strategy
     * picks the first provider whose any-prefix matches the request's model.
     *
     * <p>Most providers declare a single prefix (e.g. {@code "claude"} for
     * Anthropic). OpenAI declares several because its chat catalogue spans
     * three namespaces ({@code "gpt"}, {@code "o1"}/{@code "o3"}/{@code "o4"},
     * {@code "chatgpt"}). When a provider's catalogue grows a new namespace,
     * extend the list rather than overriding {@link #supports(ChatRequest)}.
     *
     * <p>Use specific prefixes (e.g. {@code "o1"} not bare {@code "o"}) so a
     * provider doesn't accidentally swallow another provider's models when the
     * default fallback runs.
     */
    protected abstract List<String> modelPrefixes();
}