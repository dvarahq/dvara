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
 *   <li><b>Mistral</b> is counted with its own Tekken vocabulary, for every model that uses it: Nemo, Ministral,
 *       Pixtral 12B, Small from 2409, Medium 3, Magistral, Devstral, Codestral from 25.01, Voxtral and the
 *       {@code -latest} names. The older models that use a SentencePiece vocabulary (Large 2402 to 2411, Pixtral
 *       Large, Mixtral, the 7B, Codestral 22B and Mamba, Small and Medium 2312 and Small 2402) are counted with the
 *       stand-in, not exact.</li>
 *   <li><b>DeepSeek</b> is counted with its own V3 vocabulary for V3 and R1 and the API's {@code deepseek-chat} and
 *       {@code deepseek-reasoner}. Earlier models (Coder, V2, LLM) have other vocabularies and use the stand-in.</li>
 *   <li><b>Every other family</b> (Anthropic, Gemini, Meta, Qwen, Cohere, xAI) is counted with {@code cl100k_base} as a
 *       stand-in, marked not exact: their own tokenizers are not available in-process.</li>
 *   <li><b>A model no family claims</b> is {@code unknown}: cl100k, not exact. That is the stated default, not a guess
 *       hidden in a catch block.</li>
 * </ul>
 *
 * <p>Each family's count can be multiplied by a correction, {@code dvara.llm-gateway.token-count.corrections.<family>},
 * and a request with tools can have a fixed number of tokens added, {@code dvara.llm-gateway.token-count.tool-overhead.<family>}.
 * The values come from measuring the local count against what each provider bills ({@code TokenCountAccuracyTest});
 * they are never guessed. A family with no measurement gets 1.0 and 0. The measured values ship as defaults
 * ({@link #MEASURED_CORRECTIONS}, {@link #MEASURED_TOOL_OVERHEADS}), and a setting for a family replaces its default.
 *
 * <p>A provider prefix or path is ignored: {@code openai/gpt-4.1}, {@code anthropic.claude-sonnet-4-5} (Bedrock) and
 * {@code us.anthropic.claude-…} resolve as the model they name.
 */
public class DefaultTokenizerResolver implements TokenizerResolver {

    public static final String O200K = "o200k_base";
    public static final String CL100K = "cl100k_base";
    /** Mistral's Tekken vocabulary. */
    public static final String TEKKEN = "tekken";
    /** DeepSeek's V3 vocabulary. */
    public static final String DEEPSEEK_V3 = "deepseek_v3";

    /**
     * Measured with claude-haiku-4-5 on 2026-10-16: over prose, Java code and text in seven scripts, Anthropic billed
     * 980 tokens where the cl100k stand-in counted 938. The error on one text ranged from 21% under (code) to 11% over
     * (non-Latin scripts), so this corrects the total, not every text.
     */
    public static final Map<String, Double> MEASURED_CORRECTIONS = Map.of("anthropic", 1.05);

    /**
     * Measured with claude-haiku-4-5 on 2026-10-16: an agent turn with two tools was billed 920 tokens where the
     * corrected count was 380. Anthropic adds its own instructions for tool use to every request that has tools.
     */
    public static final Map<String, Integer> MEASURED_TOOL_OVERHEADS = Map.of("anthropic", 540);

    private final Map<String, Double> corrections;
    private final Map<String, Integer> toolOverheads;

    /** The measured defaults. */
    public DefaultTokenizerResolver() {
        this(Map.of(), Map.of());
    }

    /** @param corrections a factor per family, over the measured ones; a non-positive or non-finite one counts as 1.0 */
    public DefaultTokenizerResolver(Map<String, Double> corrections) {
        this(corrections, Map.of());
    }

    /**
     * @param corrections   a factor per family, over the measured ones; a non-positive or non-finite one counts as 1.0
     * @param toolOverheads tokens per family added to a request with tools, over the measured ones; a negative one
     *                      counts as 0
     */
    public DefaultTokenizerResolver(Map<String, Double> corrections, Map<String, Integer> toolOverheads) {
        this(corrections, toolOverheads, MEASURED_CORRECTIONS, MEASURED_TOOL_OVERHEADS);
    }

    private DefaultTokenizerResolver(Map<String, Double> corrections, Map<String, Integer> toolOverheads,
                                     Map<String, Double> measuredCorrections,
                                     Map<String, Integer> measuredToolOverheads) {
        this.corrections = merged(measuredCorrections, corrections);
        this.toolOverheads = merged(measuredToolOverheads, toolOverheads);
    }

    /** Each family's raw count: no correction and no tool overhead. What a measurement compares. */
    public static DefaultTokenizerResolver uncorrected() {
        return new DefaultTokenizerResolver(Map.of(), Map.of(), Map.of(), Map.of());
    }

    @Override
    public Resolution resolve(String model) {
        String name = name(model);
        String family = family(name);
        String encoding;
        boolean exact;
        switch (family) {
            case "openai" -> {
                encoding = olderOpenAi(name) ? CL100K : O200K;
                exact = true;
            }
            case "mistral" -> {
                exact = !sentencePieceMistral(name);
                encoding = exact ? TEKKEN : CL100K;
            }
            case "deepseek" -> {
                exact = deepseekV3(name);
                encoding = exact ? DEEPSEEK_V3 : CL100K;
            }
            default -> {
                encoding = CL100K;
                exact = false;
            }
        }
        return new Resolution(family, encoding, exact, correction(family), toolOverhead(family));
    }

    /**
     * The Mistral models whose vocabulary is SentencePiece, not Tekken: everything released before Tekken (mid 2024)
     * and the Large line up to 2411. Their own vocabularies are not shipped, so they keep the stand-in.
     */
    static boolean sentencePieceMistral(String name) {
        return name.startsWith("mixtral") || name.startsWith("open-mixtral") || name.startsWith("mistral-7b")
                || name.startsWith("open-mistral-7b") || name.startsWith("mistral-tiny")
                || name.startsWith("mistral-small-2312") || name.startsWith("mistral-small-2402")
                || name.startsWith("mistral-medium-2312") || name.startsWith("mistral-large-2402")
                || name.startsWith("mistral-large-2407") || name.startsWith("mistral-large-2411")
                || name.startsWith("pixtral-large") || name.startsWith("codestral-2405")
                || name.startsWith("codestral-22b") || name.startsWith("codestral-mamba");
    }

    /** DeepSeek's V3 vocabulary: V3, R1 and the API's two names for them. */
    static boolean deepseekV3(String name) {
        return name.startsWith("deepseek-chat") || name.startsWith("deepseek-reasoner")
                || name.startsWith("deepseek-v3") || name.startsWith("deepseek-r1");
    }

    private double correction(String family) {
        Double factor = corrections.get(family);
        return factor == null || !Double.isFinite(factor) || factor <= 0 ? 1.0 : factor;
    }

    private int toolOverhead(String family) {
        Integer tokens = toolOverheads.get(family);
        return tokens == null || tokens < 0 ? 0 : tokens;
    }

    private static <V> Map<String, V> merged(Map<String, V> measured, Map<String, V> configured) {
        Map<String, V> all = new java.util.HashMap<>(measured);
        if (configured != null) {
            configured.forEach((family, value) -> {
                if (family != null && value != null) {
                    all.put(family, value);
                }
            });
        }
        return Map.copyOf(all);
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
                String rest = name.substring(at + vendor.length());
                // Bedrock names DeepSeek's models by version alone ("deepseek.r1-v1:0"), so the family stays in front.
                return "deepseek.".equals(vendor) && !rest.startsWith("deepseek") ? "deepseek-" + rest : rest;
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
                || name.startsWith("devstral") || name.startsWith("voxtral") || name.startsWith("open-mistral")
                || name.startsWith("open-mixtral")) {
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
