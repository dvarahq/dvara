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
import com.dvarahq.core.prompt.PromptTemplateRepository;
import com.dvarahq.core.prompt.PromptTemplateStatus;
import org.junit.jupiter.api.BeforeEach;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultPromptTemplateResolverTest {

    private PromptTemplateRepository repository;
    private DefaultPromptTemplateResolver resolver;

    @BeforeEach
    void setUp() {
        repository = mock(PromptTemplateRepository.class);
        resolver = new DefaultPromptTemplateResolver(repository);
    }

    @Test
    void noMetadata_returnsRequestUnchanged() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();
        assertThat(resolver.resolve(request, "workspace-1")).isSameAs(request);
    }

    @Test
    void noTemplateIdInMetadata_returnsRequestUnchanged() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of("other_key", "value"))
                .build();
        assertThat(resolver.resolve(request, "workspace-1")).isSameAs(request);
    }

    @Test
    void resolvesActiveTemplate_substitutesVariables() {
        PromptTemplate template = buildTemplate("t1", PromptTemplateStatus.ACTIVE,
                "You are a {{role}} assistant.", "Tell me about {{topic}}");
        when(repository.findById("t1")).thenReturn(Optional.of(template));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of())
                .metadata(Map.of(
                        "prompt_template_id", "t1",
                        "prompt_variables", Map.of("role", "helpful", "topic", "AI")))
                .build();

        ChatRequest resolved = resolver.resolve(request, "workspace-1");

        assertThat(resolved.getMessages()).hasSize(2);
        assertThat(resolved.getMessages().get(0).getRole()).isEqualTo("system");
        assertThat(((ContentBlock.TextBlock) resolved.getMessages().get(0).getContent().get(0)).text())
                .isEqualTo("You are a helpful assistant.");
        assertThat(resolved.getMessages().get(1).getRole()).isEqualTo("user");
        assertThat(((ContentBlock.TextBlock) resolved.getMessages().get(1).getContent().get(0)).text())
                .isEqualTo("Tell me about AI");
    }

    @Test
    void appendsOriginalMessagesAfterTemplate() {
        PromptTemplate template = buildTemplate("t1", PromptTemplateStatus.ACTIVE,
                null, "Hello {{name}}");
        when(repository.findById("t1")).thenReturn(Optional.of(template));

        MultimodalMessage existingMsg = MultimodalMessage.builder()
                .role("user")
                .content(List.of(new ContentBlock.TextBlock("Follow up question")))
                .build();

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(existingMsg))
                .metadata(Map.of(
                        "prompt_template_id", "t1",
                        "prompt_variables", Map.of("name", "Bob")))
                .build();

        ChatRequest resolved = resolver.resolve(request, "workspace-1");

        // Template user message + original message
        assertThat(resolved.getMessages()).hasSize(2);
        assertThat(((ContentBlock.TextBlock) resolved.getMessages().get(0).getContent().get(0)).text())
                .isEqualTo("Hello Bob");
        assertThat(((ContentBlock.TextBlock) resolved.getMessages().get(1).getContent().get(0)).text())
                .isEqualTo("Follow up question");
    }

    @Test
    void templateModelUsedAsFallback() {
        PromptTemplate template = buildTemplate("t1", PromptTemplateStatus.ACTIVE,
                null, "Hello");
        template.setModel("claude-3-sonnet");
        when(repository.findById("t1")).thenReturn(Optional.of(template));

        ChatRequest request = ChatRequest.builder()
                .model(null)
                .metadata(Map.of("prompt_template_id", "t1"))
                .build();

        ChatRequest resolved = resolver.resolve(request, "workspace-1");
        assertThat(resolved.getModel()).isEqualTo("claude-3-sonnet");
    }

    @Test
    void requestModelTakesPriorityOverTemplate() {
        PromptTemplate template = buildTemplate("t1", PromptTemplateStatus.ACTIVE,
                null, "Hello");
        template.setModel("claude-3-sonnet");
        when(repository.findById("t1")).thenReturn(Optional.of(template));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of("prompt_template_id", "t1"))
                .build();

        ChatRequest resolved = resolver.resolve(request, "workspace-1");
        assertThat(resolved.getModel()).isEqualTo("gpt-4o");
    }

    @Test
    void templateNotFound_throws() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of("prompt_template_id", "missing"))
                .build();

        assertThatThrownBy(() -> resolver.resolve(request, "workspace-1"))
                .isInstanceOf(GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "PROMPT_TEMPLATE_NOT_FOUND");
    }

    /**
     * Another workspace's template is not found, with the same message as a missing id and whatever
     * its status, so its prompt text is never rendered into this request.
     */
    @Test
    void anotherWorkspacesTemplateIsNotFound() {
        when(repository.findById("t-active")).thenReturn(Optional.of(
                buildTemplate("t-active", PromptTemplateStatus.ACTIVE, "secret system prompt", "Hello")));
        when(repository.findById("t-draft")).thenReturn(Optional.of(
                buildTemplate("t-draft", PromptTemplateStatus.DRAFT, null, "Hello")));

        for (String id : List.of("t-active", "t-draft")) {
            ChatRequest request = ChatRequest.builder()
                    .model("gpt-4o")
                    .metadata(Map.of("prompt_template_id", id))
                    .build();
            assertThatThrownBy(() -> resolver.resolve(request, "workspace-2"))
                    .isInstanceOf(GatewayException.class)
                    .hasFieldOrPropertyWithValue("code", "PROMPT_TEMPLATE_NOT_FOUND")
                    .hasMessage("Prompt template not found: " + id);
            // A request with no workspace cannot use a workspace's template either.
            assertThatThrownBy(() -> resolver.resolve(request, null))
                    .hasFieldOrPropertyWithValue("code", "PROMPT_TEMPLATE_NOT_FOUND");
        }
    }

    /** A template with no workspace is shared and stays usable by every workspace. */
    @Test
    void aSharedTemplateIsUsableByAnyWorkspace() {
        PromptTemplate shared = buildTemplate("t-shared", PromptTemplateStatus.ACTIVE, null, "Hello");
        shared.setWorkspaceId(null);
        when(repository.findById("t-shared")).thenReturn(Optional.of(shared));

        ChatRequest resolved = resolver.resolve(ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of("prompt_template_id", "t-shared"))
                .build(), "workspace-2");

        assertThat(resolved.getMetadata()).containsEntry("resolved_template_id", "t-shared");
    }

    @Test
    void draftTemplate_throws() {
        PromptTemplate template = buildTemplate("t1", PromptTemplateStatus.DRAFT,
                null, "Hello");
        when(repository.findById("t1")).thenReturn(Optional.of(template));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of("prompt_template_id", "t1"))
                .build();

        assertThatThrownBy(() -> resolver.resolve(request, "workspace-1"))
                .isInstanceOf(GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "PROMPT_TEMPLATE_NOT_ACTIVE");
    }

    @Test
    void missingVariable_throws() {
        PromptTemplate template = buildTemplate("t1", PromptTemplateStatus.ACTIVE,
                null, "Hello {{name}}");
        when(repository.findById("t1")).thenReturn(Optional.of(template));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of("prompt_template_id", "t1"))
                .build();

        assertThatThrownBy(() -> resolver.resolve(request, "workspace-1"))
                .isInstanceOf(GatewayException.class)
                .hasFieldOrPropertyWithValue("code", "PROMPT_VARIABLE_MISSING");
    }

    @Test
    void stripsTemplateKeysFromMetadata() {
        PromptTemplate template = buildTemplate("t1", PromptTemplateStatus.ACTIVE,
                null, "Hello");
        when(repository.findById("t1")).thenReturn(Optional.of(template));

        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of(
                        "prompt_template_id", "t1",
                        "prompt_variables", Map.of(),
                        "other_key", "preserved"))
                .build();

        ChatRequest resolved = resolver.resolve(request, "workspace-1");

        assertThat(resolved.getMetadata()).doesNotContainKey("prompt_template_id");
        assertThat(resolved.getMetadata()).doesNotContainKey("prompt_variables");
        assertThat(resolved.getMetadata()).containsEntry("other_key", "preserved");
        assertThat(resolved.getMetadata()).containsEntry("resolved_template_id", "t1");
    }

    private PromptTemplate buildTemplate(String id, PromptTemplateStatus status,
                                          String systemPrompt, String userTemplate) {
        return PromptTemplate.builder()
                .id(id)
                .workspaceId("workspace-1")
                .name("test")
                .systemPrompt(systemPrompt)
                .userTemplate(userTemplate)
                .variables(List.of())
                .status(status)
                .version(1)
                .tags(List.of())
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void aRequestNamingAnExperimentIsRefusedRatherThanServedPlain() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .metadata(Map.of("prompt_experiment_id", "exp-1"))
                .build();

        assertThatThrownBy(() -> resolver.resolve(request, "ws-1"))
                .isInstanceOf(GatewayException.class)
                .hasMessageContaining("does not run prompt experiments")
                .extracting("code").isEqualTo("UNSUPPORTED_CAPABILITY");
    }

    /** Template resolution rewrites messages and metadata and carries every other field, tools, tool_choice and top_p included. */
    @Test
    void resolve_carriesEveryOtherField() throws Exception {
        PromptTemplate template = buildTemplate("t1", PromptTemplateStatus.ACTIVE, "You are a {{role}} assistant.", "Tell me about {{topic}}");
        when(repository.findById("t1")).thenReturn(Optional.of(template));
        ChatRequest original = fullyPopulated().toBuilder()
                .metadata(Map.of("prompt_template_id", "t1", "prompt_variables", Map.of("role", "helpful", "topic", "AI"))).build();

        ChatRequest resolved = resolver.resolve(original, "workspace-1");

        assertThat(resolved.getMessages()).hasSize(3);
        assertOtherFieldsUnchanged(original, resolved, "messages", "metadata", "model");
        assertThat(resolved.getModel()).as("no model on the template: the request's own stays").isEqualTo(original.getModel());
    }

    // ---- a rewrite carries every other field, by construction ----------------------------

    /** Every ChatRequest field set to a distinct value, so a dropped one is visible. */
    private static ChatRequest fullyPopulated() {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("Hello")))
                .stream(true)
                .maxTokens(321)
                .temperature(0.35)
                .topP(0.91)
                .responseFormat(new ResponseFormat.JsonSchema("person", Map.of("type", "object"), true))
                .metadata(new java.util.HashMap<>(Map.of("workspace_id", "acme")))
                .tools(List.of(ToolDefinition.builder().name("lookup").description("d").parameters(Map.of("type", "object")).build()))
                .toolChoice("auto")
                .build();
    }

    /** Reflects over ChatRequest's fields so a field added later is checked without editing this. */
    private static void assertOtherFieldsUnchanged(ChatRequest original, ChatRequest rewritten, String... changed) throws Exception {
        java.util.Set<String> changedFields = java.util.Set.of(changed);
        int checked = 0;
        for (java.lang.reflect.Field f : ChatRequest.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || changedFields.contains(f.getName())) continue;
            f.setAccessible(true);
            assertThat(f.get(rewritten)).as("field '%s' survives the rewrite", f.getName()).isEqualTo(f.get(original));
            checked++;
        }
        assertThat(checked).as("fields compared").isGreaterThanOrEqualTo(7);
    }
}
