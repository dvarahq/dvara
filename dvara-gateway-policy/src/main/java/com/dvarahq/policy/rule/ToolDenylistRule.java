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
import com.dvarahq.core.policy.PolicyContext;

import java.util.Set;

/**
 * Matches when the request names a denied tool — whether it offers the definition or replays a
 * call to it. The gateway decides before dispatch, so offering a denied tool is the act worth
 * stopping; by the time a call exists it has already been made.
 */
public final class ToolDenylistRule implements PolicyRule {

    private final Set<String> denylist;

    public ToolDenylistRule(Set<String> denylist) {
        this.denylist = Set.copyOf(denylist);
    }

    @Override
    public boolean matches(PolicyContext context, ChatRequest request) {
        return ToolNames.of(request).stream().anyMatch(denylist::contains);
    }
}