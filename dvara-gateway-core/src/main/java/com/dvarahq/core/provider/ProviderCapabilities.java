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
package com.dvarahq.core.provider;

/**
 * What a provider says it can do.
 *
 * <p><b>Not every flag here is a gate.</b> {@code supportsStructuredOutputs}, {@code supportsJsonMode},
 * {@code supportsToolCalls}, {@code supportsStreamingToolCalls} and {@code supportsBatch} are enforced
 * by the dispatcher, and {@code maxContextTokens} by the context-window filter. {@code supportsVision}
 * is <b>not</b> enforced on the primary route: the declarations are deliberately imprecise — a provider
 * whose vision is model-specific declares it off, another declares it on for a family where not every
 * model has it — so refusing on the flag would refuse requests that work. It is enforced on
 * <em>failover</em>, where the caller chose nothing, and it is shown on {@code /v1/models} and
 * {@code /actuator/gateway-status}. A provider that cannot take an image at all refuses it itself.
 */
public record ProviderCapabilities(
        boolean supportsStreaming,
        boolean supportsVision,
        boolean supportsToolCalls,
        boolean supportsStructuredOutputs,
        boolean supportsJsonMode,
        boolean supportsBatch,
        boolean supportsStreamingToolCalls,
        int maxContextTokens) {

    /**
     * The 7-arg form; defaults {@code supportsStreamingToolCalls=false}. A provider declares it
     * true only when its stream decoder puts every tool-call fragment on
     * {@link com.dvarahq.core.model.SseChunk#getToolCalls()}, because the dispatcher uses the flag
     * to keep a {@code tools} + {@code stream=true} request off a provider whose stream would drop
     * the call.
     */
    public ProviderCapabilities(boolean supportsStreaming, boolean supportsVision, boolean supportsToolCalls,
                                boolean supportsStructuredOutputs, boolean supportsJsonMode, boolean supportsBatch,
                                int maxContextTokens) {
        this(supportsStreaming, supportsVision, supportsToolCalls, supportsStructuredOutputs, supportsJsonMode,
                supportsBatch, false, maxContextTokens);
    }

    /**
     * The 6-arg form; defaults {@code supportsBatch=false} (and, through the 7-arg form,
     * {@code supportsStreamingToolCalls=false}). Only providers exposing an OpenAI-style batch
     * endpoint (OpenAI, Azure OpenAI) declare {@code supportsBatch=true}; a provider that has not
     * declared batch support is treated as non-batch, so the dispatcher rejects batch routing to it.
     *
     * <p><b>The three forms differ only by trailing booleans, so adding one argument silently changes
     * what the others mean</b> — a 7-arg call sets {@code supportsBatch}, not
     * {@code supportsStreamingToolCalls}. Name each flag in a comment at the call site, as the
     * providers do, and a wrong guess becomes visible in review rather than at runtime.
     */
    public ProviderCapabilities(boolean supportsStreaming, boolean supportsVision, boolean supportsToolCalls,
                                boolean supportsStructuredOutputs, boolean supportsJsonMode, int maxContextTokens) {
        this(supportsStreaming, supportsVision, supportsToolCalls, supportsStructuredOutputs, supportsJsonMode,
                false, maxContextTokens);
    }
}