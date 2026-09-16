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
package com.dvarahq.core.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A copy of a chunk keeps everything the original carried.
 *
 * <p>This class is copied-with-one-field-changed in four places: the OpenAI-compatible decoder holds
 * the finish-reason chunk and merges the following usage chunk into it, and the streaming guard builds
 * three terminals. {@code toBuilder()} carries every field, so none of those sites can fall behind
 * when a field is added; a hand-written field list is only correct until the next field.
 */
class SseChunkCopyTest {

    @Test
    void aCopyWithOneFieldChangedKeepsEveryOtherField() {
        SseChunk original = SseChunk.builder()
                .id("chatcmpl-1")
                .model("gpt-4o")
                .delta("hello")
                .finishReason("tool_calls")
                .toolCalls(List.of(ToolCallDelta.open(0, "call_1", "get_weather", "{\"city\":")))
                .done(false)
                .build();

        SseChunk terminal = original.toBuilder()
                .usage(ChatResponse.Usage.builder()
                        .promptTokens(11).completionTokens(5).totalTokens(16).build())
                .done(true)
                .build();

        assertThat(terminal.getId()).isEqualTo("chatcmpl-1");
        assertThat(terminal.getModel()).isEqualTo("gpt-4o");
        assertThat(terminal.getDelta()).isEqualTo("hello");
        assertThat(terminal.getFinishReason()).isEqualTo("tool_calls");
        assertThat(terminal.getToolCalls()).isEqualTo(original.getToolCalls());
        assertThat(terminal.isDone()).isTrue();
        assertThat(terminal.getUsage().getTotalTokens()).isEqualTo(16);
    }

    @Test
    void anAbsentUsageStaysNullRatherThanBecomingThreeZeros() {
        // The metering path drops a response whose total is not positive, so a zeroed usage block is a
        // served call with no usage row. Null is the state the estimator fallback exists for.
        SseChunk chunk = SseChunk.builder().id("c").model("m").delta("hi").done(false).build();
        assertThat(chunk.getUsage()).isNull();
    }
}
