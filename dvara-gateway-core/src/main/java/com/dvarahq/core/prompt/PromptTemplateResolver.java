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
package com.dvarahq.core.prompt;

import com.dvarahq.core.model.ChatRequest;

/**
 * Resolves prompt templates referenced in chat request metadata.
 *
 * <p>If {@code request.getMetadata()} contains key {@code prompt_template_id},
 * looks up the template, substitutes {@code {{variable}}} placeholders using
 * values from the {@code prompt_variables} map in metadata, and returns a new
 * {@code ChatRequest} with the resolved messages.</p>
 *
 * <p>There is no no-op default. {@code DefaultPromptTemplateResolver} in
 * {@code dvara-gateway-runtime} is the implementation, so a build carrying the engines and not the
 * runtime fails at startup rather than resolving nothing.</p>
 */
public interface PromptTemplateResolver {

    ChatRequest resolve(ChatRequest request, String workspaceId);

}