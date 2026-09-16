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

/**
 * Evaluates context window utilization and optionally prunes requests.
 *
 * <p>The policy module provides the working implementation: token estimation, the warning and hard
 * thresholds, and pruning by {@link PruningStrategy}. There is no no-op bean; an assembly whose
 * request path requires this contract must supply an implementation.</p>
 */
public interface ContextWindowGovernor {

    ContextWindowResult evaluate(ChatRequest request, int maxContextTokens, String workspaceId);
}
