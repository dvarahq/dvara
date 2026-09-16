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
package com.dvarahq.server.filter;

import com.dvarahq.core.filter.ChatFilter;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.FilterOrder;
import com.dvarahq.core.guardrail.GuardrailEnforcer;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import org.springframework.stereotype.Component;

@Component
public class GuardrailEnforcementFilter implements ChatFilter {

    private final GuardrailEnforcer guardrailEnforcer;

    public GuardrailEnforcementFilter(GuardrailEnforcer guardrailEnforcer) {
        this.guardrailEnforcer = guardrailEnforcer;
    }

    @Override public int order() { return FilterOrder.GUARDRAIL_ENFORCEMENT; }

    @Override
    public ChatRequest preDispatch(ChatRequest request, FilterContext ctx) {
        return guardrailEnforcer.enforceRequest(request, ctx.getWorkspaceId());
    }

    @Override
    public ChatResponse postDispatch(ChatRequest request, ChatResponse response, FilterContext ctx) {
        return guardrailEnforcer.enforceResponse(response, ctx.getWorkspaceId(), request);
    }
}