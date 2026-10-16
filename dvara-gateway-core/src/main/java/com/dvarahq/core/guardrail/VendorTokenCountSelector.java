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
 * Picks the requests whose tokens are counted by the provider that would serve them, when the vendor count is set to
 * {@code selected} ({@code dvara.llm-gateway.token-count.vendor.enabled=selected}). A request is counted by the vendor
 * when any selector picks it; with no selector registered, none is.
 *
 * <p>The vendor count costs a network call, so a host picks the requests where an exact count changes a decision, such
 * as a request checked against a cost limit before it is sent.
 */
@FunctionalInterface
public interface VendorTokenCountSelector {

    /** @param request the request, with its workspace in the metadata ({@code workspace_id}) */
    boolean wantsVendorCount(ChatRequest request);
}
