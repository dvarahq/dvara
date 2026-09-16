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

import com.dvarahq.core.model.MultimodalMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared extraction helpers for guardrail request scanning.
 *
 * <p>Public because a contributed detector outside this module builds its detections through these
 * helpers, and two copies of "how a detection is shaped" would drift on exactly the edge cases —
 * offsets, truncation, confidence — where a guardrail is judged.</p>
 */
public final class GuardrailScanSupport {

    private GuardrailScanSupport() {
    }

    /**
     * The non-empty tool-call argument strings on a message. A model's
     * tool-call arguments are replayed on the next turn as assistant history and
     * can carry injection / disallowed content, so guardrail request scanners
     * examine them alongside message text — not just message content blocks.
     * Tool <em>results</em> already arrive as message content and are scanned there.
     */
    public static List<String> toolCallArguments(MultimodalMessage msg) {
        if (msg.getToolCalls() == null || msg.getToolCalls().isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (var toolCall : msg.getToolCalls()) {
            String args = toolCall.getArguments();
            if (args != null && !args.isEmpty()) {
                out.add(args);
            }
        }
        return out;
    }
}