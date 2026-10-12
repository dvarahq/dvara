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
package com.dvarahq.server.service;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.provider.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The request as one provider can take it, made just before it is sent there. Every doorway builds one
 * request; what only some providers can take is changed here, per provider, so a failover to another
 * provider gets its own shape:
 *
 * <ul>
 *   <li>A custom tool goes to a provider without custom tools as a function with one string argument,
 *       {@code input}. Its calls already travel as {@code {"input": "..."}}, so they need no change.</li>
 *   <li>An image in a tool result goes to a provider that cannot take one there in a user message right after
 *       the tool results, each image labelled with the call it came from. The tool result keeps its text.</li>
 *   <li>OpenAI's tuning fields are not sent to a provider without them; that is logged at DEBUG, with why.</li>
 * </ul>
 */
public final class ProviderShapes {

    private static final Logger log = LoggerFactory.getLogger(ProviderShapes.class);

    private ProviderShapes() {
    }

    public static ChatRequest forProvider(LlmProvider provider, ChatRequest request) {
        if (request == null) {
            return null;
        }
        ChatRequest out = request;
        if (!provider.takesCustomTools() && out.getTools() != null && out.getTools().stream().anyMatch(ToolDefinition::isCustom)) {
            out = out.toBuilder().tools(out.getTools().stream().map(ToolDefinition::asFunction).toList()).build();
        }
        if (!provider.takesImagesInToolResults() && imageInToolResult(out.getMessages())) {
            out = out.toBuilder().messages(toolImagesAfterResults(out.getMessages())).build();
        }
        if (!provider.takesOpenAiTuning() && log.isDebugEnabled()) {
            List<String> left = new ArrayList<>();
            if (out.getReasoningEffort() != null) left.add("reasoning effort " + out.getReasoningEffort());
            if (out.getVerbosity() != null) left.add("verbosity " + out.getVerbosity());
            if (out.getTools() != null && out.getTools().stream().anyMatch(t -> Boolean.TRUE.equals(t.getStrict()))) {
                left.add("strict function tools");
            }
            if (!left.isEmpty()) {
                log.debug("Not sent to provider {}, which has no such setting: {}", provider.name(), String.join(", ", left));
            }
        }
        return out;
    }

    private static boolean imageInToolResult(List<MultimodalMessage> messages) {
        return messages != null && messages.stream().anyMatch(m -> "tool".equals(m.getRole()) && m.getContent() != null
                && m.getContent().stream().anyMatch(b -> b instanceof ContentBlock.ImageBlock));
    }

    /**
     * The messages with every image taken out of the tool results and put, labelled, in one user message after
     * each run of tool results. The run stays unbroken because providers expect a turn's results together.
     */
    private static List<MultimodalMessage> toolImagesAfterResults(List<MultimodalMessage> messages) {
        List<MultimodalMessage> out = new ArrayList<>();
        List<ContentBlock> moved = new ArrayList<>();
        for (MultimodalMessage m : messages) {
            boolean tool = "tool".equals(m.getRole());
            if (!tool && !moved.isEmpty()) {
                out.add(MultimodalMessage.builder().role("user").content(moved).build());
                moved = new ArrayList<>();
            }
            if (!tool || m.getContent() == null) {
                out.add(m);
                continue;
            }
            List<ContentBlock> kept = new ArrayList<>();
            List<ContentBlock> images = new ArrayList<>();
            for (ContentBlock b : m.getContent()) {
                (b instanceof ContentBlock.ImageBlock ? images : kept).add(b);
            }
            if (images.isEmpty()) {
                out.add(m);
                continue;
            }
            if (kept.isEmpty()) {
                kept.add(new ContentBlock.TextBlock("The image this tool returned follows in the next message."));
            }
            out.add(m.toBuilder().content(kept).build());
            moved.add(new ContentBlock.TextBlock("Image returned by tool call " + m.getToolCallId() + ":"));
            moved.addAll(images);
        }
        if (!moved.isEmpty()) {
            out.add(MultimodalMessage.builder().role("user").content(moved).build());
        }
        return out;
    }
}
