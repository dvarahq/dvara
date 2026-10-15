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
        // A mock answers a default method with 0, which would put every request inside its window. These tests
        // stub the count, so the bound says nothing and every request is counted.
        when(tokenEstimator.upperBound(any(ChatRequest.class))).thenReturn(Integer.MAX_VALUE);
    }

    @Test
    void aRequestWellInsideTheWindowIsNotCounted() {
        when(tokenEstimator.upperBound(any(ChatRequest.class))).thenReturn(10_000);

        ContextWindowResult result = governor.evaluate(buildRequest(1), 128000, "workspace-1");

        assertThat(result.warningThresholdBreached()).isFalse();
        assertThat(result.hardThresholdBreached()).isFalse();
        verify(tokenEstimator, never()).estimateTokens(any(ChatRequest.class));
    }

    @Test
    void aRequestWhoseBoundReachesAThresholdIsCounted() {
        // The bound says the request may be at 70%; the count says it is not, and the count decides.
        when(tokenEstimator.upperBound(any(ChatRequest.class))).thenReturn(90_000);
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(5_000);

        ContextWindowResult result = governor.evaluate(buildRequest(1), 128000, "workspace-1");

        assertThat(result.warningThresholdBreached()).isFalse();
        assertThat(result.estimatedTokens()).isEqualTo(5_000);
        verify(tokenEstimator).estimateTokens(any(ChatRequest.class));
    }

    @Test
    void theBoundIsComparedWithTheWorkspacesOwnLowerThreshold() {
        Workspace workspace = Workspace.builder().id("workspace-1").name("w")
                .metadata(Map.of("guardrail.context.warning-threshold-pct", "5")).build();
        when(workspaceRepository.findById("workspace-1")).thenReturn(Optional.of(workspace));
        when(tokenEstimator.upperBound(any(ChatRequest.class))).thenReturn(10_000);   // 7% of the window
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(9_000);

        ContextWindowResult result = governor.evaluate(buildRequest(1), 128000, "workspace-1");

        assertThat(result.warningThresholdBreached()).isTrue();
        verify(tokenEstimator).estimateTokens(any(ChatRequest.class));
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
        // Six messages at 20,000 each: 94% of the window, and four of them fit under 90%.
        eachMessageCosts(20000);

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
    void aRequestStillOverTheLimitAfterPruningIsRefusedNotSent() {
        // One irreducibly large message: every estimate, before and after pruning, stays over the hard threshold.
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenAnswer(inv -> {
            ChatRequest r = inv.getArgument(0);
            return 3 + r.getMessages().stream().mapToInt(m -> "system".equals(m.getRole()) ? 126990 : 1).sum();
        });
        for (String strategy : new String[]{"TRUNCATE_OLDEST", "TRUNCATE_MIDDLE"}) {
            Workspace workspace = Workspace.builder().id("workspace-1")
                    .metadata(Map.of("guardrail.context.pruning-strategy", strategy,
                            "guardrail.context.hard-threshold-pct", "90"))
                    .build();
            when(workspaceRepository.findById("workspace-1")).thenReturn(Optional.of(workspace));

            ContextWindowResult result = governor.evaluate(buildRequest(5), 128000, "workspace-1");

            assertThat(result.hardThresholdBreached()).as(strategy).isTrue();
            assertThat(result.prunedRequest()).as(strategy + ": nothing to send").isNull();
            assertThat(result.pruningStrategy()).as(strategy).hasToString(strategy);
        }
        var events = org.mockito.ArgumentCaptor.forClass(com.dvarahq.core.audit.AuditEvent.class);
        verify(auditWriter, org.mockito.Mockito.times(2)).write(events.capture());
        assertThat(events.getAllValues()).allSatisfy(e -> {
            assertThat(e.eventType()).isEqualTo("CONTEXT_WINDOW_EXCEEDED");
            assertThat((Integer) e.payload().get("estimated_tokens_after_pruning"))
                    .as("still over 90% of 128,000 after pruning").isGreaterThan(115200);
        });
    }

    @Test
    void aSuccessfulPruneNamesItsStrategy() {
        eachMessageCosts(20000);
        Workspace workspace = Workspace.builder().id("workspace-1")
                .metadata(Map.of("guardrail.context.pruning-strategy", "TRUNCATE_MIDDLE",
                        "guardrail.context.hard-threshold-pct", "90"))
                .build();
        when(workspaceRepository.findById("workspace-1")).thenReturn(Optional.of(workspace));

        ContextWindowResult result = governor.evaluate(buildRequest(5), 128000, "workspace-1");

        assertThat(result.prunedRequest()).isNotNull();
        assertThat(result.pruningStrategy()).hasToString("TRUNCATE_MIDDLE");
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

    /** A threshold kept in the workspace's settings applies, and hides an older one in its metadata. */
    @Test
    void customThresholds_fromWorkspaceSettings() {
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenReturn(50000);

        Workspace workspace = Workspace.builder().id("workspace-1")
                .metadata(Map.of("guardrail.context.warning-threshold-pct", "90"))
                .settings(Map.of("guardrail", Map.of(
                        "guardrail.context.warning-threshold-pct", "30",
                        "guardrail.context.hard-threshold-pct", "50")))
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

    /** Adds up its messages, as both shipped estimators do: 3 for the request, then each message's cost. */
    private void eachMessageCosts(int perMessage) {
        when(tokenEstimator.estimateTokens(any(ChatRequest.class))).thenAnswer(inv -> {
            ChatRequest r = inv.getArgument(0);
            return 3 + perMessage * r.getMessages().size();
        });
    }

    /**
     * Pruning counts each message once. It used to count the whole candidate again for every message
     * it tried, which is quadratic in the conversation's length.
     */
    @Test
    void pruningCountsEachMessageOnce() {
        eachMessageCosts(1000);
        for (String strategy : new String[]{"TRUNCATE_OLDEST", "TRUNCATE_MIDDLE"}) {
            clearInvocations(tokenEstimator);
            Workspace workspace = Workspace.builder().id("workspace-1")
                    .metadata(Map.of("guardrail.context.pruning-strategy", strategy,
                            "guardrail.context.hard-threshold-pct", "90"))
                    .build();
            when(workspaceRepository.findById("workspace-1")).thenReturn(Optional.of(workspace));

            ChatRequest request = buildRequest(200);  // 201 messages, 201,003 tokens
            ContextWindowResult result = governor.evaluate(request, 128000, "workspace-1");

            assertThat(result.prunedRequest()).as(strategy).isNotNull();
            assertThat(result.estimatedTokens()).as(strategy + ": the sum agrees with a fresh count")
                    .isEqualTo(3 + 1000 * result.prunedRequest().getMessages().size());
            // The request once, the request with no messages once, then each message once.
            verify(tokenEstimator, times(2 + request.getMessages().size()).description(strategy))
                    .estimateTokens(any(ChatRequest.class));
        }
    }

    /**
     * The real tokenizer adds up per message, which is what lets pruning sum instead of recount: the
     * figure it reports for a pruned request is the figure a fresh count gives.
     */
    @Test
    void aPrunedCountMatchesAFreshCountWithTheRealTokenizer() {
        TiktokenEstimator real = new TiktokenEstimator();
        DefaultContextWindowGovernor withReal = new DefaultContextWindowGovernor(real, auditWriter, workspaceRepository);
        for (String strategy : new String[]{"TRUNCATE_OLDEST", "TRUNCATE_MIDDLE"}) {
            Workspace workspace = Workspace.builder().id("workspace-1")
                    .metadata(Map.of("guardrail.context.pruning-strategy", strategy,
                            "guardrail.context.hard-threshold-pct", "90"))
                    .build();
            when(workspaceRepository.findById("workspace-1")).thenReturn(Optional.of(workspace));
            List<MultimodalMessage> messages = new ArrayList<>();
            messages.add(MultimodalMessage.builder().role("system")
                    .content(List.of(new ContentBlock.TextBlock("You are a careful assistant."))).build());
            for (int i = 0; i < 60; i++) {
                messages.add(MultimodalMessage.user("Turn " + i + ": " + "the quick brown fox jumps over the lazy dog. ".repeat(3 + i % 7)));
            }
            ChatRequest request = fullyPopulated().toBuilder().messages(messages).build();
            int window = real.estimateTokens(request) * 2 / 3;

            ContextWindowResult result = withReal.evaluate(request, window, "workspace-1");

            assertThat(result.prunedRequest()).as(strategy).isNotNull();
            assertThat(result.estimatedTokens()).as(strategy)
                    .isEqualTo(real.estimateTokens(result.prunedRequest()));
        }
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
        eachMessageCosts(40000);
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
