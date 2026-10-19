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

import com.dvarahq.core.guardrail.TokenBreakdown;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where a request's tokens go: the parts add up to the count exactly, each part holds its own messages, and the
 * vocabulary is named, for every family, corrected or not.
 */
class TiktokenEstimatorBreakdownTest {

    private final TiktokenEstimator estimator = new TiktokenEstimator();

    static final String SYSTEM = "You are a careful assistant. Answer in one short paragraph and cite your sources.";
    static final String USER = "What is the weather in Paris today, and should I take an umbrella to the museum?";

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o", "claude-haiku-4-5", "mistral-large-2512", "deepseek-chat", "gemini-2.5-pro",
            "some-unknown-model"})
    void thePartsAddUpToTheCountExactly(String model) {
        ChatRequest request = everything(model);

        TokenBreakdown b = estimator.breakdown(request).orElseThrow();

        assertThat(b.total()).isEqualTo(estimator.estimateTokens(request));
        assertThat(b.system()).isPositive();
        assertThat(b.user()).isPositive();
        assertThat(b.assistant()).isPositive();
        assertThat(b.toolResults()).isPositive();
        assertThat(b.tools()).isPositive();
        assertThat(b.images()).isPositive();
        assertThat(b.overhead()).isNotNegative();
        assertThat(b.perMessage()).hasSize(request.getMessages().size());
    }

    @Test
    void eachMessageCountsTowardsItsOwnPart() {
        ChatRequest asSystem = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(message("system", USER))).build();
        ChatRequest asUser = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(message("user", USER))).build();
        ChatRequest asDeveloper = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(message("developer", USER))).build();
        ChatRequest asTool = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.toolResult("c1", USER))).build();
        ChatRequest asAssistant = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.assistant(USER))).build();

        TokenBreakdown system = estimator.breakdown(asSystem).orElseThrow();
        TokenBreakdown user = estimator.breakdown(asUser).orElseThrow();
        int text = user.user();

        assertThat(text).isPositive();
        assertThat(system.system()).isEqualTo(text);
        assertThat(system.user()).isZero();
        assertThat(user.system()).isZero();
        assertThat(estimator.breakdown(asDeveloper).orElseThrow().system()).isEqualTo(text);
        assertThat(estimator.breakdown(asTool).orElseThrow().toolResults()).isEqualTo(text);
        assertThat(estimator.breakdown(asAssistant).orElseThrow().assistant()).isEqualTo(text);
        assertThat(user.perMessage()).containsExactly(text);
    }

    @Test
    void aProvidersFixedToolTextIsOverheadNotTools() {
        ChatRequest plain = ChatRequest.builder().model("claude-haiku-4-5")
                .messages(List.of(message("user", USER))).build();
        ChatRequest withTools = plain.toBuilder().tools(List.of(tool())).build();

        TokenBreakdown without = estimator.breakdown(plain).orElseThrow();
        TokenBreakdown with = estimator.breakdown(withTools).orElseThrow();

        assertThat(with.overhead() - without.overhead())
                .isGreaterThanOrEqualTo(DefaultTokenizerResolver.MEASURED_TOOL_OVERHEADS.get("anthropic"));
        assertThat(with.tools()).isPositive().isLessThan(200);
    }

    @Test
    void theVocabularyIsNamed_andSaysWhetherItIsTheModelsOwn() {
        assertThat(vocabulary("gpt-4o")).isEqualTo("o200k_base exact");
        assertThat(vocabulary("gpt-4")).isEqualTo("cl100k_base exact");
        assertThat(vocabulary("mistral-large-2512")).isEqualTo("tekken exact");
        assertThat(vocabulary("deepseek-chat")).isEqualTo("deepseek_v3 exact");
        assertThat(vocabulary("claude-haiku-4-5")).isEqualTo("cl100k_base estimated");
        assertThat(vocabulary("some-unknown-model")).isEqualTo("cl100k_base estimated");
    }

    @Test
    void noRequestHasNoBreakdown() {
        assertThat(estimator.breakdown(null)).isEmpty();
        assertThat(estimator.breakdown(ChatRequest.builder().model("gpt-4o").build())).isEmpty();
    }

    private String vocabulary(String model) {
        TokenBreakdown b = estimator.breakdown(ChatRequest.builder().model(model)
                .messages(List.of(message("user", USER))).build()).orElseThrow();
        return b.encoding() + (b.exact() ? " exact" : " estimated");
    }

    private static ChatRequest everything(String model) {
        return ChatRequest.builder().model(model)
                .messages(List.of(
                        message("system", SYSTEM),
                        message("user", USER),
                        MultimodalMessage.builder().role("assistant").content(null)
                                .toolCalls(List.of(ToolCall.builder().id("c1").name("weather")
                                        .arguments("{\"city\": \"Paris\"}").build()))
                                .build(),
                        MultimodalMessage.toolResult("c1", "Light rain until four, then clearing; 14 degrees."),
                        MultimodalMessage.builder().role("user").content(List.of(
                                new ContentBlock.TextBlock("And this photo of the sky?"),
                                new ContentBlock.ImageBlock("image/png", png(512, 512)))).build()))
                .tools(List.of(tool()))
                .build();
    }

    private static ToolDefinition tool() {
        return ToolDefinition.builder().name("weather").description("Current weather for a city")
                .parameters(Map.of("type", "object", "properties", Map.of("city", Map.of("type", "string")),
                        "required", List.of("city")))
                .build();
    }

    private static MultimodalMessage message(String role, String text) {
        return MultimodalMessage.builder().role(role).content(List.of(new ContentBlock.TextBlock(text))).build();
    }

    private static String png(int width, int height) {
        try {
            var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", out);
            return java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
