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
import com.dvarahq.core.model.ChatResponse;

/**
 * Validates chat responses against configured output schemas.
 *
 * <p>The policy module provides the working JSON-schema implementation. There is no no-op bean; an
 * assembly whose request path requires this contract must supply the policy implementation.</p>
 */
public interface OutputSchemaValidator {

    /**
     * Validate the response against a configured output schema, if any.
     *
     * @param routeId the id of the route the routing engine matched on the
     *                request, or {@code null} when no configured route
     *                matched. Used together with {@code request.model} to
     *                pick which registered schema applies to this call.
     */
    OutputSchemaResult validate(ChatRequest request, ChatResponse response,
                                 String workspaceId, String routeId);
}