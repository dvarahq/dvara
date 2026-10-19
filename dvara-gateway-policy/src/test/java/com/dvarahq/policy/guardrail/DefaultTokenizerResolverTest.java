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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Each model is counted by its family: OpenAI, Mistral's Tekken models and DeepSeek V3 with their own vocabulary, the rest with a stated stand-in. */
class DefaultTokenizerResolverTest {

    private final DefaultTokenizerResolver resolver = new DefaultTokenizerResolver();

    @ParameterizedTest(name = "{0} -> {1}, {2}, exact {3}")
    @CsvSource({
            "gpt-4.1, openai, o200k_base, true",
            "gpt-4.1-mini, openai, o200k_base, true",
            "gpt-4o-2024-08-06, openai, o200k_base, true",
            "gpt-5, openai, o200k_base, true",
            "o3-mini, openai, o200k_base, true",
            "o4-mini, openai, o200k_base, true",
            "gpt-oss-120b, openai, o200k_base, true",
            "chatgpt-4o-latest, openai, o200k_base, true",
            "gpt-4, openai, cl100k_base, true",
            "gpt-4-turbo, openai, cl100k_base, true",
            "gpt-3.5-turbo, openai, cl100k_base, true",
            "text-embedding-3-small, openai, cl100k_base, true",
            "openai/gpt-4.1, openai, o200k_base, true",
            "claude-sonnet-4-5, anthropic, cl100k_base, false",
            "anthropic.claude-3-5-sonnet-20240620-v1:0, anthropic, cl100k_base, false",
            "us.anthropic.claude-haiku-4-5, anthropic, cl100k_base, false",
            "gemini-2.5-flash, gemini, cl100k_base, false",
            "mistral-large-latest, mistral, tekken, true",
            "mistral-small-2506, mistral, tekken, true",
            "open-mistral-nemo, mistral, tekken, true",
            "ministral-8b-2410, mistral, tekken, true",
            "magistral-medium-latest, mistral, tekken, true",
            "devstral-small-2507, mistral, tekken, true",
            "codestral-2508, mistral, tekken, true",
            "mistral/mistral-medium-2508, mistral, tekken, true",
            "mistral-large-2411, mistral, cl100k_base, false",
            "mistral.mistral-large-2407-v1:0, mistral, cl100k_base, false",
            "open-mixtral-8x22b, mistral, cl100k_base, false",
            "open-mistral-7b, mistral, cl100k_base, false",
            "codestral-2405, mistral, cl100k_base, false",
            "deepseek-chat, deepseek, deepseek_v3, true",
            "deepseek-reasoner, deepseek, deepseek_v3, true",
            "deepseek.r1-v1:0, deepseek, deepseek_v3, true",
            "deepseek-v3.1, deepseek, deepseek_v3, true",
            "deepseek-coder-6.7b, deepseek, cl100k_base, false",
            "meta.llama3-70b-instruct-v1:0, meta, cl100k_base, false",
            "qwen-max, qwen, cl100k_base, false",
            "command-r-plus, cohere, cl100k_base, false",
            "grok-4, xai, cl100k_base, false",
            "my-own-model, unknown, cl100k_base, false"})
    void resolvesEachFamily(String model, String family, String encoding, boolean exact) {
        TokenizerResolver.Resolution r = resolver.resolve(model);
        assertThat(r.family()).isEqualTo(family);
        assertThat(r.encoding()).isEqualTo(encoding);
        assertThat(r.exact()).isEqualTo(exact);
        assertThat(r.correction()).isEqualTo("anthropic".equals(family) ? 1.05 : 1.0);
        assertThat(r.toolOverhead()).isEqualTo("anthropic".equals(family) ? 540 : 0);
    }

    @Test
    void aNullModelIsUnknown() {
        assertThat(resolver.resolve(null).family()).isEqualTo("unknown");
    }

    @Test
    void aFamilysCorrectionIsAppliedAndABadOneIgnored() {
        var corrected = new DefaultTokenizerResolver(Map.of("anthropic", 1.12, "gemini", -1.0, "xai", Double.NaN));
        assertThat(corrected.resolve("claude-sonnet-4-5").correction()).isEqualTo(1.12);
        assertThat(corrected.resolve("gemini-2.5-flash").correction()).as("not positive").isEqualTo(1.0);
        assertThat(corrected.resolve("grok-4").correction()).as("not finite").isEqualTo(1.0);
        assertThat(corrected.resolve("gpt-4.1").correction()).as("no entry").isEqualTo(1.0);
    }

    private static ChatRequest ask(String model, String text) {
        return ChatRequest.builder().model(model).messages(List.of(MultimodalMessage.user(text))).build();
    }

    /** GPT-4.1 was counted with cl100k; it is o200k's, which splits non-English text into far fewer pieces. */
    @Test
    void gpt41IsNowCountedWithItsOwnVocabulary() {
        String hindi = "कृपया पिछले महीने के खर्च का सारांश भेजें, और बताएं कि किस कार्यक्षेत्र ने सबसे अधिक खर्च किया।";
        TiktokenEstimator estimator = new TiktokenEstimator();
        int asGpt41 = estimator.estimateTokens(ask("gpt-4.1", hindi));
        int asGpt4 = estimator.estimateTokens(ask("gpt-4", hindi));
        assertThat(asGpt41).as("o200k").isLessThan(asGpt4);
        assertThat(estimator.estimateTokens(ask("gpt-4o", hindi))).as("jtokkit's own gpt-4o, also o200k").isEqualTo(asGpt41);
    }

    @Test
    void aCorrectionScalesTheCountAndTheBoundStaysAboveIt() {
        TiktokenEstimator plain = new TiktokenEstimator(DefaultTokenizerResolver.uncorrected());
        TiktokenEstimator corrected = new TiktokenEstimator(new DefaultTokenizerResolver(Map.of("anthropic", 1.5)));
        ChatRequest request = ask("claude-sonnet-4-5", "The quarterly review covered three areas in some detail.");

        int base = plain.estimateTokens(request);
        int scaled = corrected.estimateTokens(request);
        assertThat(scaled).isEqualTo((int) Math.ceil(base * 1.5));
        assertThat(corrected.upperBound(request)).isGreaterThanOrEqualTo(scaled);
    }

    @Test
    void aResolverThatFailsCountsAsUnknown() {
        TiktokenEstimator estimator = new TiktokenEstimator(model -> {
            throw new IllegalStateException("broken");
        });
        assertThat(estimator.estimateTokens(ask("claude-sonnet-4-5", "hello there")))
                .isEqualTo(new TiktokenEstimator().estimateTokens(ask("my-own-model", "hello there")));
    }

    @Test
    void theMeasuredValuesAreTheDefaultsAndASettingReplacesOne() {
        var resolver = new DefaultTokenizerResolver(Map.of("anthropic", 1.2), Map.of("gemini", 100));
        assertThat(resolver.resolve("claude-haiku-4-5").correction()).as("setting wins").isEqualTo(1.2);
        assertThat(resolver.resolve("claude-haiku-4-5").toolOverhead()).as("measured default kept").isEqualTo(540);
        assertThat(resolver.resolve("gemini-3.8-flash").toolOverhead()).isEqualTo(100);
        assertThat(new DefaultTokenizerResolver(Map.of(), Map.of("anthropic", -5)).resolve("claude-haiku-4-5")
                .toolOverhead()).as("negative").isZero();
    }

    @Test
    void uncorrectedIsTheRawCount() {
        var raw = DefaultTokenizerResolver.uncorrected().resolve("claude-haiku-4-5");
        assertThat(raw.correction()).isEqualTo(1.0);
        assertThat(raw.toolOverhead()).isZero();
    }
}
