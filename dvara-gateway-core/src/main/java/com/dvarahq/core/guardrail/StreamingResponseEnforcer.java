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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.SseChunk;

import java.util.Iterator;

/**
 * Wraps a streaming SSE chunk iterator with PII, guardrail and grounding enforcement.
 *
 * <p>The posture is resolved once before the first chunk. When no enabled control can withhold
 * content, chunks are delivered as they arrive and scanned once at the end. When one can, the
 * response is held and judged as a whole, then delivered transformed or refused: a value split
 * across chunks is found, and nothing is released that a later chunk could have changed. The
 * policy module provides the implementation; a build without it passes the upstream iterator
 * through unchanged.</p>
 */
public interface StreamingResponseEnforcer {

    Iterator<SseChunk> wrap(Iterator<SseChunk> upstream, String workspaceId);

    /**
     * Wrap with request context for grounding detection. The request provides
     * source documents via {@code metadata["grounding.sources"]}.
     */
    default Iterator<SseChunk> wrap(Iterator<SseChunk> upstream, String workspaceId, ChatRequest request) {
        return wrap(upstream, workspaceId);
    }
}