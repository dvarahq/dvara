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
package com.dvarahq.server.prompt;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.prompt.PromptTemplate;
import com.dvarahq.core.prompt.PromptTemplateRenderer;
import com.dvarahq.core.prompt.PromptTemplateRepository;
import com.dvarahq.core.prompt.PromptTemplateResolver;
import com.dvarahq.core.prompt.PromptTemplateStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves prompt templates referenced in chat request metadata.
 *
 * <p>When {@code metadata.prompt_template_id} is present, looks up the ACTIVE template,
 * substitutes variables using {@code metadata.prompt_variables}, and prepends the
 * rendered system/user messages to the request.</p>
 */
public class DefaultPromptTemplateResolver implements PromptTemplateResolver {

    private static final Logger log = LoggerFactory.getLogger(DefaultPromptTemplateResolver.class);
    private static final String TEMPLATE_ID_KEY = "prompt_template_id";
    private static final String VARIABLES_KEY = "prompt_variables";
    private static final String EXPERIMENT_ID_KEY = "prompt_experiment_id";

    private final PromptTemplateRepository repository;

    public DefaultPromptTemplateResolver(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    @Override
    public ChatRequest resolve(ChatRequest request, String workspaceId) {
        if (request.getMetadata() == null) {
            return request;
        }
        if (request.getMetadata().containsKey(EXPERIMENT_ID_KEY)) {
            // Refused rather than ignored: a caller who asked for a split across templates and was
            // served their raw messages would not be able to tell from the response.
            throw new GatewayException("UNSUPPORTED_CAPABILITY",
                    "metadata." + EXPERIMENT_ID_KEY + " names a prompt experiment, and this build does not run "
                            + "prompt experiments; name a " + TEMPLATE_ID_KEY + " instead");
        }
        if (!request.getMetadata().containsKey(TEMPLATE_ID_KEY)) {
            return request;
        }

        String templateId = String.valueOf(request.getMetadata().get(TEMPLATE_ID_KEY));

        // Another workspace's template is not found. Using it would render that workspace's prompt
        // text into this request, where the caller reads the model's answer to it. The same answer as
        // a missing id, and no status, so a caller cannot learn which ids exist elsewhere. A template
        // with no workspace is shared and usable by every workspace.
        PromptTemplate template = repository.findById(templateId)
                .filter(t -> t.getWorkspaceId() == null || t.getWorkspaceId().equals(workspaceId))
                .orElseThrow(() -> new GatewayException("PROMPT_TEMPLATE_NOT_FOUND",
                        "Prompt template not found: " + templateId));

        if (template.getStatus() != PromptTemplateStatus.ACTIVE) {
            throw new GatewayException("PROMPT_TEMPLATE_NOT_ACTIVE",
                    "Prompt template is not active: " + templateId + " (status: " + template.getStatus() + ")");
        }

        @SuppressWarnings("unchecked")
        Map<String, String> variables = request.getMetadata().get(VARIABLES_KEY) instanceof Map<?, ?> raw
                ? (Map<String, String>) raw
                : Map.of();

        // Render template messages
        List<MultimodalMessage> messages = new ArrayList<>();

        if (template.getSystemPrompt() != null && !template.getSystemPrompt().isBlank()) {
            try {
                String renderedSystem = PromptTemplateRenderer.render(template.getSystemPrompt(), variables);
                messages.add(MultimodalMessage.builder()
                        .role("system")
                        .content(List.of(new ContentBlock.TextBlock(renderedSystem)))
                        .build());
            } catch (IllegalArgumentException e) {
                throw new GatewayException("PROMPT_VARIABLE_MISSING", e.getMessage());
            }
        }

        try {
            String renderedUser = PromptTemplateRenderer.render(template.getUserTemplate(), variables);
            messages.add(MultimodalMessage.builder()
                    .role("user")
                    .content(List.of(new ContentBlock.TextBlock(renderedUser)))
                    .build());
        } catch (IllegalArgumentException e) {
            throw new GatewayException("PROMPT_VARIABLE_MISSING", e.getMessage());
        }

        // Append original messages after template messages
        if (request.getMessages() != null) {
            messages.addAll(request.getMessages());
        }

        // Resolve model: request model takes priority, template model is fallback
        String model = request.getModel();
        if ((model == null || model.isBlank()) && template.getModel() != null) {
            model = template.getModel();
        }

        // Strip template keys from metadata to avoid re-resolution on retries
        Map<String, Object> cleanMetadata = new HashMap<>(request.getMetadata());
        cleanMetadata.remove(TEMPLATE_ID_KEY);
        cleanMetadata.remove(VARIABLES_KEY);
        cleanMetadata.put("resolved_template_id", templateId);
        cleanMetadata.put("resolved_template_version", template.getVersion());

        // toBuilder() rather than a hand-rolled copy, so every field (topP, tools, toolChoice...) carries over.
        return request.toBuilder().model(model).messages(messages).metadata(cleanMetadata).build();
    }

}