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
package com.dvarahq.core.routing;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.RoutingStrategy;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ModelPrefixRoutingStrategy implements RoutingStrategy {

    private static final Map<String, String> PREFIX_TO_ENV;

    static {
        // The order of this map decides nothing: Map.copyOf has an unspecified iteration order, and
        // buildMessage takes the longest matching prefix explicitly, so adding a key that is a
        // prefix of another (say "o" or "gem") stays correct.
        var map = new LinkedHashMap<String, String>();
        map.put("ollama/", "OLLAMA_ENABLED=true");
        map.put("azure/", "AZURE_OPENAI_API_KEY and AZURE_OPENAI_BASE_URL");
        map.put("groq/", "GROQ_API_KEY");
        map.put("bedrock/", "BEDROCK_ENABLED=true");
        map.put("gpt", "OPENAI_API_KEY");
        // Reasoning models (o1, o3, o4) and the chatgpt-* alias family route
        // through the same OpenAI provider but don't share the gpt prefix —
        // list each so the NO_PROVIDER hint suggests OPENAI_API_KEY rather
        // than the generic catch-all when one of them is requested without
        // OpenAI registered.
        map.put("o1", "OPENAI_API_KEY");
        map.put("o3", "OPENAI_API_KEY");
        map.put("o4", "OPENAI_API_KEY");
        map.put("chatgpt", "OPENAI_API_KEY");
        map.put("text-embedding", "OPENAI_API_KEY");
        map.put("claude", "ANTHROPIC_API_KEY");
        map.put("gemini", "GEMINI_API_KEY");
        map.put("mistral", "MISTRAL_API_KEY");
        map.put("command", "COHERE_API_KEY");
        // First-class OpenAI-compatible providers.
        map.put("qwen", "QWEN_API_KEY");
        map.put("deepseek", "DEEPSEEK_API_KEY");
        map.put("moonshot", "MOONSHOT_API_KEY");
        map.put("glm", "ZHIPU_API_KEY");
        map.put("grok", "XAI_API_KEY");
        PREFIX_TO_ENV = Map.copyOf(map);
    }

    @Override
    public LlmProvider route(ChatRequest request, List<LlmProvider> providers) {
        return providers.stream()
                .filter(p -> p.supports(request))
                .findFirst()
                .orElseThrow(() -> new GatewayException("NO_PROVIDER",
                        buildMessage(request.getModel())));
    }

    private String buildMessage(String model) {
        if (model != null) {
            Optional<String> env = PREFIX_TO_ENV.entrySet().stream()
                    .filter(e -> model.startsWith(e.getKey()))
                    .max(Comparator.comparingInt(e -> e.getKey().length()))
                    .map(Map.Entry::getValue);
            if (env.isPresent()) {
                return "No provider configured for model: " + model
                        + ". Set " + env.get() + " to register the provider.";
            }
        }
        return "No provider configured for model: " + model
                + ". Check GET /v1/models for available models.";
    }
}