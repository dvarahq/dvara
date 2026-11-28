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
package com.dvarahq.core.policy;

import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.model.ChatRequest;

/**
 * Holds a request a policy rule sent for a person's approval ({@code REQUIRE_APPROVAL}) until it is decided.
 *
 * <p>Optional: a build with no gate refuses every such request with {@code POLICY_APPROVAL_UNAVAILABLE}, so a held
 * request never runs unapproved.
 */
public interface PolicyApprovalGate {

    /**
     * Waits for a decision on {@code request}.
     *
     * @param decision the approval-required decision, naming the policy and the rule
     * @return the request to go on with, once a person approved it
     * @throws com.dvarahq.core.exception.GatewayException when it is refused, times out, or cannot be held
     */
    ChatRequest hold(ChatRequest request, FilterContext ctx, PolicyDecision decision);
}
