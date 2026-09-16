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

import com.dvarahq.core.guardrail.TokenEstimation;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;

/**
 * Simple character-based token estimator: text.length() / 4.
 *
 * <p>Counts the same parts of a request as the BPE estimator — message text, tool results, tool calls
 * and the tool definitions — so the two differ in accuracy and not in what they consider part of the
 * request. Images are not counted, for the reason given on {@code TiktokenEstimator}.</p>
 */
public class SimpleTokenEstimator implements TokenEstimator {

    @Override
    public int estimateTokens(ChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return 0;
        }
        int total = 0;
        for (var message : request.getMessages()) {
            if (message.getContent() != null) {
                for (var block : message.getContent()) {
                    if (block instanceof ContentBlock.TextBlock textBlock) {
                        total += estimateTokens(textBlock.text());
                    }
                }
            }
            if (message.getToolCalls() != null) {
                for (var toolCall : message.getToolCalls()) {
                    total += estimateTokens(toolCall.getName());
                    total += estimateTokens(toolCall.getArguments());
                }
            }
        }
        total += estimateTokens(TokenEstimation.renderTools(request.getTools()));
        return total;
    }

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / 4;
    }
}