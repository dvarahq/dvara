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
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TiktokenEstimatorTest {

    private final TiktokenEstimator estimator = new TiktokenEstimator();

    @Test
    void estimateTokens_nullText_returnsZero() {
        assertThat(estimator.estimateTokens((String) null)).isZero();
    }

    @Test
    void estimateTokens_emptyText_returnsZero() {
        assertThat(estimator.estimateTokens("")).isZero();
    }

    @Test
    void estimateTokens_shortText_returnsPositive() {
        int tokens = estimator.estimateTokens("Hello, world!");
        assertThat(tokens).isGreaterThan(0);
    }

    @Test
    void estimateTokens_longerText_moreThanShorter() {
        int shortTokens = estimator.estimateTokens("Hi");
        int longTokens = estimator.estimateTokens("This is a much longer piece of text that should have more tokens");
        assertThat(longTokens).isGreaterThan(shortTokens);
    }

    @Test
    void estimateTokens_request_includesOverhead() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("user")
                                .content(List.of(new ContentBlock.TextBlock("Hello")))
                                .build()))
                .build();

        int requestTokens = estimator.estimateTokens(request);
        int textTokens = estimator.estimateTokens("Hello");

        // Request tokens should include message overhead (4 per message + 3 reply priming)
        assertThat(requestTokens).isGreaterThan(textTokens);
    }

    @Test
    void estimateTokens_nullRequest_returnsZero() {
        assertThat(estimator.estimateTokens((ChatRequest) null)).isZero();
    }

    @Test
    void estimateTokens_multipleMessages_summed() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("system")
                                .content(List.of(new ContentBlock.TextBlock("You are a helpful assistant")))
                                .build(),
                        MultimodalMessage.builder()
                                .role("user")
                                .content(List.of(new ContentBlock.TextBlock("What is the capital of France?")))
                                .build()))
                .build();

        int tokens = estimator.estimateTokens(request);
        assertThat(tokens).isGreaterThan(10);
    }

    // ---------------------------------------------------------------------------------------------
    // Everything that becomes tokens is counted.
    //
    // In a function-calling conversation tool results are usually the bulk, and the tool definitions
    // ride along on every turn; the context-window governor reads this number.
    // ---------------------------------------------------------------------------------------------

    private static final String LONG_PAYLOAD =
            "{\"rows\":[{\"id\":1,\"name\":\"Alice\",\"city\":\"Chennai\",\"total\":42.5},"
            + "{\"id\":2,\"name\":\"Bob\",\"city\":\"Bengaluru\",\"total\":17.25}]}";

    private static ChatRequest withMessages(List<MultimodalMessage> messages) {
        return ChatRequest.builder().model("gpt-4o").messages(messages).build();
    }

    private static MultimodalMessage user(String text) {
        return MultimodalMessage.builder().role("user")
                .content(List.of(new ContentBlock.TextBlock(text))).build();
    }

    @Test
    void toolResultContentIsCounted() {
        int withoutResult = estimator.estimateTokens(withMessages(List.of(user("hi"))));
        int withResult = estimator.estimateTokens(withMessages(List.of(
                user("hi"),
                // The real shape of a tool result: a `tool`-role message carrying the id it answers
                // and its output as text. There is no tool-result block kind to carry it.
                MultimodalMessage.toolResult("call_1", LONG_PAYLOAD))));

        assertThat(withResult).isGreaterThan(withoutResult + 20);
    }

    @Test
    void toolCallNameAndArgumentsAreCounted() {
        int withoutCall = estimator.estimateTokens(withMessages(List.of(user("hi"))));
        int withCall = estimator.estimateTokens(withMessages(List.of(
                user("hi"),
                MultimodalMessage.builder().role("assistant").content(null)
                        .toolCalls(List.of(ToolCall.builder()
                                .id("call_1").name("query_orders").arguments(LONG_PAYLOAD).build()))
                        .build())));

        assertThat(withCall).isGreaterThan(withoutCall + 20);
    }

    @Test
    void toolDefinitionsAreCounted() {
        ChatRequest plain = withMessages(List.of(user("hi")));
        ChatRequest withTools = plain.toBuilder()
                .tools(List.of(ToolDefinition.builder()
                        .name("query_orders")
                        .description("Look up orders for a customer in a given city and date range")
                        .parameters(Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "customer", Map.of("type", "string"),
                                        "city", Map.of("type", "string"),
                                        "from", Map.of("type", "string"),
                                        "to", Map.of("type", "string")),
                                "required", List.of("customer")))
                        .build()))
                .build();

        assertThat(estimator.estimateTokens(withTools)).isGreaterThan(estimator.estimateTokens(plain) + 20);
    }

    @Test
    void anImageIsNotCountedAsItsBase64Length() {
        // A provider prices an image by tile count, not by tokenizing its bytes, so BPE over the
        // payload would be a large number unrelated to the real cost. Named, not papered over.
        String base64 = "A".repeat(4000);
        int withImage = estimator.estimateTokens(withMessages(List.of(
                MultimodalMessage.builder().role("user")
                        .content(List.of(new ContentBlock.TextBlock("what is this?"),
                                new ContentBlock.ImageBlock("image/png", base64)))
                        .build())));

        assertThat(withImage).isLessThan(100);
    }
}
