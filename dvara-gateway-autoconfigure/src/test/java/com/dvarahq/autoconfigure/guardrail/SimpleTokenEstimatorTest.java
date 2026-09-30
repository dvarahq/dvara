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
package com.dvarahq.autoconfigure.guardrail;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleTokenEstimatorTest {

    private final SimpleTokenEstimator estimator = new SimpleTokenEstimator();

    @Test
    void anImageOnlyRequestEstimatesNonZero() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder().role("user")
                        .content(List.of(new ContentBlock.ImageBlock("image/url", "https://example.com/a.png", "low")))
                        .build()))
                .build();

        // A low-detail image is 85 tokens whatever its size.
        assertThat(estimator.estimateTokens(request)).isEqualTo(85);
    }

    @Test
    void aRequestWithToolsAndNoTextEstimatesNonZero() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o")
                .messages(List.of())
                .tools(List.of(ToolDefinition.builder().name("lookup_order")
                        .description("Looks up an order by its id")
                        .parameters(Map.of("type", "object",
                                "properties", Map.of("id", Map.of("type", "string"))))
                        .build()))
                .build();

        assertThat(estimator.estimateTokens(request)).isPositive();
    }

    @Test
    void aToolResultIsCounted() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o")
                .messages(List.of(MultimodalMessage.toolResult("call_1", "x".repeat(400))))
                .build();

        assertThat(estimator.estimateTokens(request)).isEqualTo(100);
    }
}
