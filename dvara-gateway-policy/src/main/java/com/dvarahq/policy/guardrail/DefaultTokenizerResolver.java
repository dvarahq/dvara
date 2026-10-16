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
package com.dvarahq.policy.guardrail;

import java.util.Locale;
import java.util.Map;

/**
 * Resolves a model to its family by its name, and the family to a tokenizer.
 *
 * <ul>
 *   <li><b>OpenAI</b> is counted with its own vocabulary: {@code o200k_base} for GPT-4o, GPT-4.1, GPT-4.5, GPT-5, the
 *       o-series and gpt-oss; {@code cl100k_base} for GPT-4, GPT-3.5 and the older embeddings. Dated and suffixed names
 *       ({@code gpt-4o-2024-08-06}, {@code gpt-4.1-mini}) count as their base model.</li>
 *   <li><b>Every other family</b> (Anthropic, Gemini, Mistral, DeepSeek, Meta, Qwen, Cohere, xAI) is counted with
 *       {@code cl100k_base} as a stand-in, marked not exact: their own tokenizers are not available in-process.</li>
 *   <li><b>A model no family claims</b> is {@code unknown}: cl100k, not exact. That is the stated default, not a guess
 *       hidden in a catch block.</li>
 * </ul>
 *
 * <p>Each family's count can be multiplied by a correction, {@code dvara.llm-gateway.token-count.corrections.<family>},
 * 1.0 where none is set. The values come from measuring the local count against what each provider bills
 * ({@code TokenCountAccuracyTest}); a correction is never guessed.
 *
 * <p>A provider prefix or path is ignored: {@code openai/gpt-4.1}, {@code anthropic.claude-sonnet-4-5} (Bedrock) and
 * {@code us.anthropic.claude-…} resolve as the model they name.
 */
public class DefaultTokenizerResolver implements TokenizerResolver {

    public static final String O200K = "o200k_base";
    public static final String CL100K = "cl100k_base";

    private final Map<String, Double> corrections;

    public DefaultTokenizerResolver() {
        this(Map.of());
    }

    /** @param corrections a factor per family; a missing, non-positive or non-finite one counts as 1.0 */
    public DefaultTokenizerResolver(Map<String, Double> corrections) {
        this.corrections = corrections == null ? Map.of() : Map.copyOf(corrections);
    }

    @Override
    public Resolution resolve(String model) {
        String name = name(model);
        String family = family(name);
        boolean openai = "openai".equals(family);
        String encoding = openai && !olderOpenAi(name) ? O200K : CL100K;
        return new Resolution(family, encoding, openai, correction(family));
    }

    private double correction(String family) {
        Double factor = corrections.get(family);
        return factor == null || !Double.isFinite(factor) || factor <= 0 ? 1.0 : factor;
    }

    /** The model's own name: lower case, without a provider path or a Bedrock region and vendor prefix. */
    static String name(String model) {
        if (model == null) {
            return "";
        }
        String name = model.strip().toLowerCase(Locale.ROOT);
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // Bedrock: "anthropic.claude-…", "us.anthropic.claude-…", "meta.llama3-…", "mistral.mistral-large-…".
        for (String vendor : new String[] {"anthropic.", "meta.", "mistral.", "cohere.", "deepseek.", "qwen."}) {
            int at = name.indexOf(vendor);
            if (at >= 0 && (at == 0 || name.charAt(at - 1) == '.')) {
                return name.substring(at + vendor.length());
            }
        }
        return name;
    }

    static String family(String name) {
        if (name.startsWith("gpt-") || name.startsWith("chatgpt") || name.startsWith("text-embedding")
                || name.matches("o\\d.*") || name.startsWith("davinci") || name.startsWith("babbage")) {
            return "openai";
        }
        if (name.startsWith("claude")) {
            return "anthropic";
        }
        if (name.startsWith("gemini") || name.startsWith("gemma")) {
            return "gemini";
        }
        if (name.startsWith("mistral") || name.startsWith("mixtral") || name.startsWith("codestral")
                || name.startsWith("ministral") || name.startsWith("magistral") || name.startsWith("pixtral")
                || name.startsWith("devstral")) {
            return "mistral";
        }
        if (name.startsWith("deepseek")) {
            return "deepseek";
        }
        if (name.startsWith("llama")) {
            return "meta";
        }
        if (name.startsWith("qwen") || name.startsWith("qwq")) {
            return "qwen";
        }
        if (name.startsWith("command") || name.startsWith("embed-") || name.startsWith("rerank")) {
            return "cohere";
        }
        if (name.startsWith("grok")) {
            return "xai";
        }
        return "unknown";
    }

    /** The OpenAI models before GPT-4o, which count with cl100k. */
    private static boolean olderOpenAi(String name) {
        if (name.startsWith("gpt-4o") || name.startsWith("gpt-4.") || name.startsWith("gpt-5")
                || name.startsWith("gpt-oss") || name.startsWith("chatgpt-4o") || name.matches("o\\d.*")) {
            return false;
        }
        return name.startsWith("gpt-4") || name.startsWith("gpt-3") || name.startsWith("text-embedding")
                || name.startsWith("davinci") || name.startsWith("babbage") || name.startsWith("chatgpt");
    }
}
