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
package com.dvarahq.server.metering;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.policy.guardrail.TiktokenEstimator;
import com.dvarahq.providers.anthropic.AnthropicProvider;
import com.dvarahq.providers.gemini.GeminiProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How far the gateway's own token count is from the vendor's count and from what the provider bills, per model family,
 * over a fixed set of samples in {@code src/test/resources/token-accuracy}: English prose, code, text in seven
 * scripts, and an agent's tool-heavy turn.
 *
 * <p>The gateway counts every model with one local tokenizer, and models it does not know fall back to OpenAI's
 * cl100k. Whether that is close enough for Anthropic, Gemini and the rest, and whether a per-family tokenizer, a
 * per-family correction or the vendors' own counting endpoints are worth their cost, is decided by this table.
 *
 * <p>Two parts. {@link #theLocalCountsAreStableOverTheSamples} always runs, with no network: it keeps the samples
 * loadable and the local counts deterministic. {@link #measure} runs on request and calls the vendors for every key
 * that is set, writing {@code target/token-accuracy.md}:
 *
 * <pre>
 *   OPENAI_API_KEY=… ANTHROPIC_API_KEY=… GEMINI_API_KEY=… \
 *     ./mvnw -pl dvara-gateway-runtime -am test -Dtest=TokenCountAccuracyTest -Ddvara.token-accuracy=true \
 *       -Dsurefire.failIfNoSpecifiedTests=false
 * </pre>
 *
 * <p>The vendor count is free (Anthropic {@code count_tokens}, Gemini {@code countTokens}; OpenAI has none). The
 * billed count is one real call per sample and family, asking for a single output token, so the input is what the
 * provider bills; it includes the provider's own per-message framing, which no local count of the text sees. Model ids
 * can be changed with {@code -Ddvara.token-accuracy.openai-model=…} and the like.
 */
class TokenCountAccuracyTest {

    record Sample(String name, String file, boolean tools) {
    }

    static final List<Sample> SAMPLES = List.of(
            new Sample("English prose", "prose.txt", false),
            new Sample("Code (Java)", "code.java.txt", false),
            new Sample("Seven scripts", "multilingual.txt", false),
            new Sample("Agent turn with tools", "agentic.txt", true));

    /** A model family, the model measured for it, and the provider that serves it, or none for local-only rows. */
    record Family(String name, String model, String keyEnv, java.util.function.Function<SecretProvider, LlmProvider> provider) {
    }

    static List<Family> families() {
        return List.of(
                new Family("OpenAI", System.getProperty("dvara.token-accuracy.openai-model", "gpt-4.1-mini"),
                        "OPENAI_API_KEY", s -> new OpenAiProvider(s, "https://api.openai.com/v1", RestClient.builder())),
                new Family("Anthropic", System.getProperty("dvara.token-accuracy.anthropic-model", "claude-haiku-4-5"),
                        "ANTHROPIC_API_KEY", s -> new AnthropicProvider(s, RestClient.builder())),
                new Family("Gemini", System.getProperty("dvara.token-accuracy.gemini-model", "gemini-3.8-flash"),
                        "GEMINI_API_KEY", s -> new GeminiProvider(s, "https://generativelanguage.googleapis.com",
                        RestClient.builder())),
                // Counted locally with the cl100k fallback; no vendor column without a provider here.
                new Family("DeepSeek", "deepseek-chat", null, null),
                new Family("Mistral", "mistral-large-latest", null, null));
    }

    static String text(Sample sample) {
        try (InputStream in = TokenCountAccuracyTest.class.getResourceAsStream("/token-accuracy/" + sample.file())) {
            if (in == null) {
                throw new IllegalStateException("missing sample " + sample.file());
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<ToolDefinition> tools() {
        return List.of(
                ToolDefinition.builder().name("search_orders").description("Find a customer's orders by status")
                        .parameters(Map.of("type", "object", "properties", Map.of(
                                "customer_id", Map.of("type", "string"),
                                "status", Map.of("type", "array", "items", Map.of("type", "string")),
                                "limit", Map.of("type", "integer")), "required", List.of("customer_id")))
                        .build(),
                ToolDefinition.builder().name("get_shipment").description("Get an order's shipment events")
                        .parameters(Map.of("type", "object", "properties", Map.of(
                                "order_id", Map.of("type", "string"),
                                "include", Map.of("type", "array", "items", Map.of("type", "string"))),
                                "required", List.of("order_id")))
                        .build());
    }

    static ChatRequest request(Sample sample, String model) {
        ChatRequest.ChatRequestBuilder b = ChatRequest.builder().model(model).maxTokens(1)
                .messages(List.of(MultimodalMessage.user(text(sample))));
        if (sample.tools()) {
            b.tools(tools());
        }
        return b.build();
    }

    /** The raw count: what the corrections are measured from. */
    private final TiktokenEstimator local =
            new TiktokenEstimator(com.dvarahq.policy.guardrail.DefaultTokenizerResolver.uncorrected());

    /** The count the gateway uses, with the measured corrections. */
    private final TiktokenEstimator corrected = new TiktokenEstimator();

    /** No network: every sample loads, and the local count is positive and the same every time. */
    @Test
    void theLocalCountsAreStableOverTheSamples() {
        for (Family family : families()) {
            for (Sample sample : SAMPLES) {
                int first = local.estimateTokens(request(sample, family.model()));
                assertThat(first).as("%s on %s", sample.name(), family.model()).isPositive();
                assertThat(local.estimateTokens(request(sample, family.model()))).isEqualTo(first);
            }
        }
    }

    record Row(String sample, String family, String model, String vocabulary, int local, int corrected,
               Integer vendor, Integer billed, double localMicros) {
    }

    /** The vocabulary the local count used for a model, and whether it is the model's own. */
    static String vocabulary(String model) {
        var r = new com.dvarahq.policy.guardrail.DefaultTokenizerResolver().resolve(model);
        return r.encoding() + (r.exact() ? "" : " (stand-in)");
    }

    @Test
    @EnabledIfSystemProperty(named = "dvara.token-accuracy", matches = "true")
    void measure() throws IOException {
        List<Row> rows = new ArrayList<>();
        List<String> unanswered = new ArrayList<>();
        for (Family family : families()) {
            String key = family.keyEnv() == null ? null : System.getenv(family.keyEnv());
            LlmProvider provider = key == null || key.isBlank() ? null
                    : family.provider().apply(secrets(family, key));
            boolean answered = false;
            for (Sample sample : SAMPLES) {
                ChatRequest request = request(sample, family.model());
                Integer billed = provider == null ? null : billed(provider, request);
                answered |= billed != null;
                rows.add(new Row(sample.name(), family.name(), family.model(), vocabulary(family.model()),
                        local.estimateTokens(request), corrected.estimateTokens(request),
                        provider == null ? null : vendor(provider, request), billed, microsPerCount(request)));
            }
            if (provider != null && !answered) {
                unanswered.add(family.name() + " (" + family.model() + ")");
            }
        }
        String table = table(rows);
        Path out = Path.of("target", "token-accuracy.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, table);
        System.out.println(table);
        assertThat(rows).hasSize(families().size() * SAMPLES.size());
        // A key was given and no call with it was answered: a wrong key or a retired model, not a measurement.
        assertThat(unanswered).as("families whose key was set but no call succeeded").isEmpty();
    }

    private static SecretProvider secrets(Family family, String key) {
        String name = "provider." + family.name().toLowerCase(Locale.ROOT) + ".api-key";
        return k -> name.equals(k) ? Optional.of(key) : Optional.empty();
    }

    /** The vendor's own count, where it has an endpoint; null when it has none or the call fails. */
    private static Integer vendor(LlmProvider provider, ChatRequest request) {
        try {
            OptionalInt count = provider.countInputTokens(request);
            return count.isPresent() ? count.getAsInt() : null;
        } catch (RuntimeException e) {
            System.err.println("vendor count failed for " + request.getModel() + ": " + e.getMessage());
            return null;
        }
    }

    /** The input the provider billed for a one-token answer; null when the call fails. */
    private static Integer billed(LlmProvider provider, ChatRequest request) {
        try {
            ChatResponse response = provider.chat(request);
            return response.getUsage() == null ? null : response.getUsage().getPromptTokens();
        } catch (RuntimeException e) {
            System.err.println("billed call failed for " + request.getModel() + ": " + e.getMessage());
            return null;
        }
    }

    /** What one local count costs, averaged after a warm-up. */
    private double microsPerCount(ChatRequest request) {
        for (int i = 0; i < 200; i++) {
            local.estimateTokens(request);
        }
        int runs = 2_000;
        long start = System.nanoTime();
        for (int i = 0; i < runs; i++) {
            local.estimateTokens(request);
        }
        return (System.nanoTime() - start) / 1_000.0 / runs;
    }

    static String table(List<Row> rows) {
        StringBuilder md = new StringBuilder();
        md.append("| Sample | Family | Model | Local vocabulary | Local | Corrected | Vendor count | Billed input "
                + "| Local vs billed | Corrected vs billed | Local cost (µs) |\n");
        md.append("|---|---|---|---|--:|--:|--:|--:|--:|--:|--:|\n");
        for (Row r : rows) {
            md.append("| ").append(r.sample()).append(" | ").append(r.family()).append(" | ").append(r.model())
                    .append(" | ").append(r.vocabulary())
                    .append(" | ").append(String.format(Locale.ROOT, "%,d", r.local()))
                    .append(" | ").append(String.format(Locale.ROOT, "%,d", r.corrected()))
                    .append(" | ").append(r.vendor() == null ? "—" : String.format(Locale.ROOT, "%,d", r.vendor()))
                    .append(" | ").append(r.billed() == null ? "—" : String.format(Locale.ROOT, "%,d", r.billed()))
                    .append(" | ").append(difference(r.local(), r.billed()))
                    .append(" | ").append(difference(r.corrected(), r.billed()))
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", r.localMicros())).append(" |\n");
        }
        return md.toString();
    }

    private static String difference(int count, Integer billed) {
        return billed == null || billed == 0 ? "—"
                : String.format(Locale.ROOT, "%+.1f%%", 100.0 * (count - billed) / billed);
    }

    /** The table says what it cannot know rather than inventing a number. */
    @Test
    void aRowWithNoKeyShowsDashesNotZeros() {
        String md = table(List.of(
                new Row("English prose", "Anthropic", "claude-haiku-4-5", vocabulary("claude-haiku-4-5"), 250, 263,
                        null, null, 12.5),
                new Row("English prose", "OpenAI", "gpt-4.1-mini", vocabulary("gpt-4.1-mini"), 240, 240, null, 250,
                        9.0)));
        assertThat(md).contains("| cl100k_base (stand-in) | 250 | 263 | — | — | — | — | 12.5 |")
                .contains("| o200k_base | 240 | 240 | — | 250 | -4.0% | -4.0% | 9.0 |");
    }
}
