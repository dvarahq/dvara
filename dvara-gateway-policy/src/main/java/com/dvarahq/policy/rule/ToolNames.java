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
package com.dvarahq.policy.rule;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Every tool name a request puts in front of the model.
 *
 * <p>Two places carry one, and a rule that reads only one of them governs half the request:
 *
 * <ul>
 *   <li>{@link ChatRequest#getTools()} — the definitions the client is offering on this turn. This is
 *       the one that matters most, because the gateway decides before dispatch: a tool the model is
 *       never offered is one it cannot call, whereas a call is something that has already happened.</li>
 *   <li>{@link MultimodalMessage#getToolCalls()} — calls already in the conversation, which an agent
 *       loop replays on every later turn.</li>
 * </ul>
 *
 * <p>Nothing on the request path produces a {@code ToolUseBlock} content block, so a rule that
 * looked there instead of at these two fields would match nothing and fail open.
 */
final class ToolNames {

    private ToolNames() {
    }

    /** The names offered or already called, in first-seen order, never null. */
    static Set<String> of(ChatRequest request) {
        Set<String> names = new LinkedHashSet<>();
        List<ToolDefinition> tools = request.getTools();
        if (tools != null) {
            for (ToolDefinition tool : tools) {
                if (tool != null && tool.getName() != null) {
                    names.add(tool.getName());
                }
            }
        }
        List<MultimodalMessage> messages = request.getMessages();
        if (messages != null) {
            for (MultimodalMessage message : messages) {
                List<ToolCall> calls = message == null ? null : message.getToolCalls();
                if (calls == null) {
                    continue;
                }
                for (ToolCall call : calls) {
                    if (call != null && call.getName() != null) {
                        names.add(call.getName());
                    }
                }
            }
        }
        return names;
    }
}
