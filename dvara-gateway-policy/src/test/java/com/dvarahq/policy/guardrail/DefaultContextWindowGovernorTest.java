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
package com.dvarahq.policy.guardrail;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.guardrail.ContextWindowResult;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultContextWindowGovernorTest {

    private TokenEstimator tokenEstimator;
    private AuditWriter auditWriter;
    private WorkspaceRepository workspaceRepository;
    private DefaultContextWindowGovernor governor;

    @BeforeEach
    void setUp() {
        tokenEstimator = mock(TokenEstimator.class);
        auditWriter = mock(AuditWriter.class);
        workspaceRepository = mock(WorkspaceRepository.class);
        governor = new DefaultContextWindowGovernor(tokenEstimator, auditWriter, workspaceRepository);

        when(workspaceRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Test
    void withinLimits_noWarnings() {
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(5000);

        ContextWindowResult result = governor.evaluate(buildRequest(1), 128000, "workspace-1");

        assertThat(result.warningThresholdBreached()).isFalse();
        assertThat(result.hardThresholdBreached()).isFalse();
        assertThat(result.prunedRequest()).isNull();
    }

    @Test
    void warningThreshold_breached() {
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(90000);

        ContextWindowResult result = governor.evaluate(buildRequest(1), 128000, "workspace-1");

        assertThat(result.warningThresholdBreached()).isTrue();
        assertThat(result.hardThresholdBreached()).isFalse();
        assertThat(result.prunedRequest()).isNull();
        verify(auditWriter).write(any());
    }

    @Test
    void hardThreshold_breached_noPruning() {
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(120000);

        ContextWindowResult result = governor.evaluate(buildRequest(1), 128000, "workspace-1");

        assertThat(result.hardThresholdBreached()).isTrue();
        assertThat(result.prunedRequest()).isNull();
        verify(auditWriter).write(any());
    }

    @Test
    void hardThreshold_breached_withTruncateOldest() {
        // First call = original (high), subsequent calls for pruning candidates = lower
        when(tokenEstimator.estimateTokens(any(ChatRequest.class)))
                .thenReturn(120000)
                .thenReturn(50000);

        Workspace workspace = Workspace.builder().id("workspace-1")
                .metadata(Map.of(
                        "guardrail.context.pruning-strategy", "TRUNCATE_OLDEST",
                        "guardrail.context.hard-threshold-pct", "90"))
                .build();
        when(workspaceRepository.findById("workspace-1")).thenReturn(Optional.of(workspace));

        ContextWindowResult result = governor.evaluate(buildRequest(5), 128000, "workspace-1");

        assertThat(result.hardThresholdBreached()).isTrue();
        assertThat(result.prunedRequest()).isNotNull();
        verify(auditWriter).write(any());
    }

    @Test
    void customThresholds_fromWorkspaceMetadata() {
        // 50000 / 128000 = ~39% — with custom warning threshold of 30%, this should breach
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(50000);

        Workspace workspace = Workspace.builder().id("workspace-1")
                .metadata(Map.of(
                        "guardrail.context.warning-threshold-pct", "30",
                        "guardrail.context.hard-threshold-pct", "50"))
                .build();
        when(workspaceRepository.findById("workspace-1")).thenReturn(Optional.of(workspace));

        ContextWindowResult result = governor.evaluate(buildRequest(1), 128000, "workspace-1");

        assertThat(result.warningThresholdBreached()).isTrue();
    }

    @Test
    void zeroMaxTokens_returnsWithinLimits() {
        ContextWindowResult result = governor.evaluate(buildRequest(1), 0, "workspace-1");

        assertThat(result.warningThresholdBreached()).isFalse();
        assertThat(result.hardThresholdBreached()).isFalse();
    }

    private ChatRequest buildRequest(int messageCount) {
        List<MultimodalMessage> messages = new ArrayList<>();
        messages.add(MultimodalMessage.builder()
                .role("system")
                .content(List.of(new ContentBlock.TextBlock("You are helpful")))
                .build());
        for (int i = 0; i < messageCount; i++) {
            messages.add(MultimodalMessage.builder()
                    .role(i % 2 == 0 ? "user" : "assistant")
                    .content(List.of(new ContentBlock.TextBlock("Message " + i)))
                    .build());
        }
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(messages)
                .build();
    }

    /** A prune rewrites the messages; every other field, tools, tool_choice and top_p included, must come through. */
    @Test
    void pruning_carriesEveryOtherField() throws Exception {
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(120000).thenReturn(50000);
        Workspace workspace = Workspace.builder().id("workspace-1")
                .metadata(Map.of("guardrail.context.pruning-strategy", "TRUNCATE_OLDEST",
                        "guardrail.context.hard-threshold-pct", "90")).build();
        when(workspaceRepository.findById("workspace-1")).thenReturn(Optional.of(workspace));
        ChatRequest original = fullyPopulated().toBuilder().messages(List.of(
                MultimodalMessage.user("one"), MultimodalMessage.user("two"), MultimodalMessage.user("three"))).build();

        ContextWindowResult result = governor.evaluate(original, 128000, "workspace-1");

        assertThat(result.prunedRequest()).isNotNull();
        assertOtherFieldsUnchanged(original, result.prunedRequest(), "messages");
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
