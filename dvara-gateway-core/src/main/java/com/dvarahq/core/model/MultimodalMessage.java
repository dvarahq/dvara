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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MultimodalMessage {

    private String role;
    private List<ContentBlock> content;

    /**
     * Tool calls the model emitted on an {@code assistant} message.
     * Relayed back to the client; null/empty on messages without tool calls.
     */
    private List<ToolCall> toolCalls;

    /**
     * On a {@code tool}-role result message, the id of the {@link ToolCall}
     * this message answers. Null on non-tool messages.
     */
    private String toolCallId;

    /**
     * The optional participant name OpenAI lets a client attach to a message, relayed to
     * providers whose wire shape carries it and ignored by the rest. Null when absent.
     */
    private String name;

    public static MultimodalMessage user(String text) {
        return MultimodalMessage.builder()
                .role("user")
                .content(List.of(new ContentBlock.TextBlock(text)))
                .build();
    }

    public static MultimodalMessage assistant(String text) {
        return MultimodalMessage.builder()
                .role("assistant")
                .content(List.of(new ContentBlock.TextBlock(text)))
                .build();
    }

    /**
     * A {@code tool}-role result message: the framework's answer to a
     * prior {@link ToolCall}, carrying the tool output text and the id of the
     * call it answers.
     */
    public static MultimodalMessage toolResult(String toolCallId, String text) {
        return MultimodalMessage.builder()
                .role("tool")
                .toolCallId(toolCallId)
                .content(List.of(new ContentBlock.TextBlock(text)))
                .build();
    }

    /**
     * Returns the concatenated text content of this message, joining every
     * block's {@link ContentBlock#textContent()} with a single space. Image
     * blocks (which carry no text) are skipped. Returns an empty string when
     * the message has no content blocks or none of them carry text.
     *
     * <p>This helper exists so mock-scenario Groovy scripts can write
     * {@code request.messages.last().textContent()?.contains('billing')}
     * without walking the block list and downcasting to
     * {@link ContentBlock.TextBlock} by hand.
     *
     * <p><b>It flattens, so do not measure or scan with it.</b> The joining space is a character no
     * block contained, which makes the result longer than the message and lets a pattern match across
     * a boundary the caller never wrote. Count each block's own text, and scan each block on its own.
     */
    public String textContent() {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            String text = block.textContent();
            if (text != null && !text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }
}