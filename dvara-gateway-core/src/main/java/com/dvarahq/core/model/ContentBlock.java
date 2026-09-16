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

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One part of a message's content.
 *
 * <p>Two kinds, because two are what the request path produces: text, and an image. A tool call is
 * not a content block here — it travels on {@link MultimodalMessage#getToolCalls()} as a
 * {@link ToolCall}, which is the shape the OpenAI-compatible API this gateway serves puts it in, and
 * a tool result travels as a {@code tool}-role message carrying
 * {@link MultimodalMessage#getToolCallId()} and its output as text (see
 * {@link MultimodalMessage#toolResult}).
 *
 * <p>Adding a block kind here adds a shape every reader must handle, so be sure something produces
 * it first.
 *
 * <p>The Jackson type information is load-bearing, not decoration: the response cache writes a
 * {@code ChatResponse} as JSON and reads it back, and without a discriminator Jackson cannot pick a
 * kind to instantiate. A cache put would succeed and every get would fail, which reads as a cache
 * that never hits.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ContentBlock.TextBlock.class, name = "text"),
        @JsonSubTypes.Type(value = ContentBlock.ImageBlock.class, name = "image")
})
public sealed interface ContentBlock
        permits ContentBlock.TextBlock, ContentBlock.ImageBlock {

    /**
     * Returns the textual content of this block, or {@code null} if the block carries no text. Text
     * blocks return their text; image blocks return {@code null}, their content being binary.
     *
     * <p>This helper exists so mock-scenario Groovy scripts can write
     * {@code request.messages.last().textContent()} without walking the block list and downcasting to
     * {@link TextBlock} by hand.
     */
    String textContent();

    record TextBlock(String text) implements ContentBlock {
        @Override public String textContent() { return text; }
    }

    record ImageBlock(String mediaType, String data) implements ContentBlock {
        @Override public String textContent() { return null; }
    }
}