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
package com.dvarahq.core.call;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

/**
 * Sends a chat request the gateway makes for itself through the same path a caller's request
 * takes: the request filters (policy, PII, guardrails, budgets), dispatch with the workspace's own
 * credentials, the response filters, usage and cost rows, and an audit record. Only the response
 * cache is skipped, because the answer belongs to the request that caused the call, not to a
 * caller.
 *
 * <p>The audit record is an {@code INTERNAL_CALL} event naming the call's kind, its own trace id
 * and the parent's.
 */
public interface InternalChatClient {

    /**
     * Runs one non-streaming chat request as {@code call}.
     *
     * @return the governed response
     * @throws com.dvarahq.core.exception.GatewayException when a filter refuses the request or the
     *         provider fails, exactly as for a caller
     * @throws IllegalStateException when called while another internal call runs on this thread:
     *         an internal call never starts another
     */
    ChatResponse chat(ChatRequest request, InternalCall call);
}
