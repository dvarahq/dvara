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

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
// toBuilder because the decoder copies a chunk with one field changed: it holds the chunk carrying
// a finish reason and merges the following usage chunk into it, and a hand-written copy would miss
// any field added later.
@Builder(toBuilder = true)
public class SseChunk {

    private String id;
    private String model;
    private String delta;
    private String finishReason;
    private boolean done;

    /**
     * Exact usage reported by the upstream on the final chunk, or null.
     *
     * <p>OpenAI-compatible upstreams return one when the request sets
     * {@code stream_options: {"include_usage": true}}. Null where an upstream does not report it; the
     * metering path then falls back to the estimate and marks the row {@code estimated}.</p>
     */
    private ChatResponse.Usage usage;

    /**
     * Fragments of streamed tool calls carried by this chunk, or null when it carries none.
     *
     * <p>A chunk may carry text, tool-call fragments, both, or neither. The fragments are grouped by
     * {@link ToolCallDelta#index()}; the streaming guard assembles each call's arguments across chunks
     * and governs them as content, because a function's arguments are where a model puts the values it
     * was asked about.</p>
     */
    private List<ToolCallDelta> toolCalls;

    /**
     * A fragment of Anthropic's extended thinking, or null. Only an Anthropic provider produces one, for
     * a request that asked for thinking. It is output: the streaming guard holds and scans its text like
     * the answer's.
     */
    private ThinkingDelta thinking;

    /**
     * The Anthropic event this chunk was read from, or null. Only an Anthropic provider sets it, for a request
     * whose body it sends on as it came; a chunk made anywhere else carries none.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private AnthropicEvent anthropicEvent;
}
