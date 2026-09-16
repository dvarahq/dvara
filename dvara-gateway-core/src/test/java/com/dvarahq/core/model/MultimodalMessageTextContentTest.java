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
 * Tests for the {@code textContent()} convenience helpers on
 * {@link MultimodalMessage} and {@link ContentBlock}.
 *
 * <p>These exist primarily so mock-scenario Groovy scripts can write
 * {@code request.messages.last().textContent()?.contains('billing')}
 * without walking the {@code List<ContentBlock>} and downcasting.
 */
class MultimodalMessageTextContentTest {

    // --- ContentBlock.textContent() per type ---

    @Test
    void textBlock_returnsItsText() {
        assertThat(new ContentBlock.TextBlock("hello").textContent()).isEqualTo("hello");
    }

    @Test
    void imageBlock_returnsNull() {
        assertThat(new ContentBlock.ImageBlock("image/png", "base64data").textContent()).isNull();
    }

    @Test
    void textBlock_nullText_returnsNull() {
        // Records allow null components today. Make sure the helper doesn't NPE
        // on a TextBlock whose text field is null.
        assertThat(new ContentBlock.TextBlock(null).textContent()).isNull();
    }

    @Test
    void messageWithNullTextBlock_skipsItGracefully() {
        // The MultimodalMessage walk should treat a null-text block the same
        // as an empty one — skip it without NPE'ing on the .isEmpty() guard.
        var msg = MultimodalMessage.builder()
                .role("user")
                .content(java.util.Arrays.asList(
                        new ContentBlock.TextBlock(null),
                        new ContentBlock.TextBlock("real content")))
                .build();
        assertThat(msg.textContent()).isEqualTo("real content");
    }

    // --- MultimodalMessage.textContent() composition ---

    @Test
    void emptyMessage_returnsEmptyString() {
        var msg = MultimodalMessage.builder().role("user").content(List.of()).build();
        assertThat(msg.textContent()).isEmpty();
    }

    @Test
    void nullContent_returnsEmptyString() {
        var msg = MultimodalMessage.builder().role("user").content(null).build();
        assertThat(msg.textContent()).isEmpty();
    }

    @Test
    void singleTextBlock_returnsItsText() {
        assertThat(MultimodalMessage.user("hello world").textContent()).isEqualTo("hello world");
    }

    @Test
    void multipleTextBlocks_joinedWithSingleSpace() {
        var msg = MultimodalMessage.builder()
                .role("user")
                .content(List.of(
                        new ContentBlock.TextBlock("hello"),
                        new ContentBlock.TextBlock("world")))
                .build();
        assertThat(msg.textContent()).isEqualTo("hello world");
    }

    @Test
    void textAndImageBlocks_imageSkipped() {
        var msg = MultimodalMessage.builder()
                .role("user")
                .content(List.of(
                        new ContentBlock.TextBlock("describe this image:"),
                        new ContentBlock.ImageBlock("image/png", "base64data"),
                        new ContentBlock.TextBlock("in detail")))
                .build();
        assertThat(msg.textContent()).isEqualTo("describe this image: in detail");
    }

    @Test
    void onlyImageBlocks_returnsEmptyString() {
        var msg = MultimodalMessage.builder()
                .role("user")
                .content(List.of(
                        new ContentBlock.ImageBlock("image/png", "base64a"),
                        new ContentBlock.ImageBlock("image/jpeg", "base64b")))
                .build();
        assertThat(msg.textContent()).isEmpty();
    }

    @Test
    void textBlockWithEmptyText_skipped() {
        var msg = MultimodalMessage.builder()
                .role("user")
                .content(List.of(
                        new ContentBlock.TextBlock(""),
                        new ContentBlock.TextBlock("real content")))
                .build();
        assertThat(msg.textContent()).isEqualTo("real content");
    }

    // --- realistic scenario predicate use ---

    @Test
    void usableInScenarioPredicateStyleCheck() {
        // Mirrors the way a mock scenario's `when` closure would call the helper.
        var msg = MultimodalMessage.user("can you help with my billing question?");
        boolean isBilling = msg.textContent().toLowerCase().contains("billing");
        assertThat(isBilling).isTrue();
    }
}