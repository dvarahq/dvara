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
package com.dvarahq.core.guardrail;

import java.util.List;

/**
 * Where a request's input tokens go: the system prompt, the user's turns, the assistant's turns, the tool
 * definitions, the tool results and the images, each counted with the same vocabulary and correction as the
 * request's total. The parts add up to {@link #total()} exactly; {@code overhead} holds what belongs to no part:
 * the few tokens each message costs for its role and separators, the reply priming, a provider's fixed text in
 * front of the tools, and rounding.
 *
 * @param system      system and developer messages
 * @param user        user messages, text only
 * @param assistant   assistant messages, text, reasoning and the tool calls they made
 * @param tools       the tool definitions sent with the request
 * @param toolResults tool messages: what the tools returned
 * @param images      images, in any message, by their provider's formula
 * @param overhead    everything else, as above
 * @param perMessage  each message's own tokens, in request order; these are the same counts as the parts, before
 *                    the message overhead, so they need not add up to the total
 * @param encoding    the vocabulary the text was counted with, such as {@code o200k_base}
 * @param exact       whether that vocabulary is the model's own, rather than a stand-in for one not available here
 */
public record TokenBreakdown(int system, int user, int assistant, int tools, int toolResults, int images,
                             int overhead, List<Integer> perMessage, String encoding, boolean exact) {

    public TokenBreakdown {
        perMessage = perMessage == null ? List.of() : List.copyOf(perMessage);
    }

    /** All the parts together: the request's input tokens. */
    public int total() {
        long sum = (long) system + user + assistant + tools + toolResults + images + overhead;
        return (int) Math.min(sum, Integer.MAX_VALUE);
    }
}
