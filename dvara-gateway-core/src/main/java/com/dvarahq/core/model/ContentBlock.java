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

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One part of a message's content.
 *
 * <p>Text and images are what every doorway produces. The two thinking kinds are Anthropic's extended
 * thinking, which only the Anthropic Messages doorway carries and only an Anthropic provider takes: a
 * request that holds one is refused on any other provider, so no other provider ever sees one. A tool call is
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
        @JsonSubTypes.Type(value = ContentBlock.ImageBlock.class, name = "image"),
        @JsonSubTypes.Type(value = ContentBlock.ThinkingBlock.class, name = "thinking"),
        @JsonSubTypes.Type(value = ContentBlock.RedactedThinkingBlock.class, name = "redacted_thinking")
})
public sealed interface ContentBlock
        permits ContentBlock.TextBlock, ContentBlock.ImageBlock, ContentBlock.ThinkingBlock,
        ContentBlock.RedactedThinkingBlock {

    /**
     * Returns the textual content of this block, or {@code null} if the block carries no text. Text
     * blocks return their text; image blocks return {@code null}, their content being binary. Thinking
     * blocks return {@code null} too: thinking is not what the message says, and code that reads a message's
     * text must not take it for that. The response checks read it through {@link ThinkingBlock#thinking()}.
     *
     * <p>This helper exists so mock-scenario Groovy scripts can write
     * {@code request.messages.last().textContent()} without walking the block list and downcasting to
     * {@link TextBlock} by hand.
     */
    String textContent();

    record TextBlock(String text) implements ContentBlock {
        @Override public String textContent() { return text; }
    }

    /**
     * An image: base64 {@code data} with its {@code mediaType}, or — when {@code mediaType} is
     * {@link #URL_MEDIA_TYPE} — a URL in {@code data} for a provider that fetches it.
     * {@code detail} is OpenAI's {@code low} / {@code high} / {@code auto}, or null when the caller
     * sent none. It changes what the call costs (a {@code low} image is a fixed, small number of
     * tokens), so it travels to the provider rather than being dropped.
     */
    record ImageBlock(String mediaType, String data, String detail) implements ContentBlock {

        /** The media type an image carried as a URL has, in place of a real one. */
        public static final String URL_MEDIA_TYPE = "image/url";

        public ImageBlock(String mediaType, String data) {
            this(mediaType, data, null);
        }

        /** True when {@code data} is a URL, not base64. Not a property: the cache round-trips this record as JSON. */
        @JsonIgnore
        public boolean isUrl() { return URL_MEDIA_TYPE.equals(mediaType); }

        @Override public String textContent() { return null; }
    }

    /**
     * A block of the model's extended thinking, as Anthropic returns it. The {@code signature} is
     * Anthropic's proof that the text is what the model wrote: the block has to go back to Anthropic
     * unchanged on a later turn, so nothing here may rewrite it on the way in. On the way out it is output
     * like any other, and the response checks read it.
     */
    record ThinkingBlock(String thinking, String signature) implements ContentBlock {
        @Override public String textContent() { return null; }
    }

    /**
     * Thinking Anthropic returned encrypted. Nobody can read it, the gateway included, so it is carried as
     * it came, both ways.
     */
    record RedactedThinkingBlock(String data) implements ContentBlock {
        @Override public String textContent() { return null; }
    }
}