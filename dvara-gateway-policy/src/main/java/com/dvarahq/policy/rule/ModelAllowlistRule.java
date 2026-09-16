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

/** Matches a request whose model name is not exactly, case included, one of the listed names. */
public final class ModelAllowlistRule implements PolicyRule {

    private final Set<String> allowlist;

    public ModelAllowlistRule(Set<String> allowlist) {
        this.allowlist = Set.copyOf(allowlist);
    }

    @Override
    public boolean matches(PolicyContext context, ChatRequest request) {
        String model = request.getModel();
        return model != null && !allowlist.contains(model);
    }
}