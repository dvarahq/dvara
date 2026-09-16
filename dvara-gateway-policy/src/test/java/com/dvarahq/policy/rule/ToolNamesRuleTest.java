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
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.policy.PolicyContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tool rules against the two places a request really names a tool: the tools it offers, and the
 * tool calls replayed in its history.
 *
 * <p>A rule that looked anywhere else would compile, activate and match nothing, so the request
 * would be served and recorded as allowed.
 */
class ToolNamesRuleTest {

    private final PolicyContext ctx = PolicyContext.empty();

    private static ChatRequest offering(String... toolNames) {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("look it up")))
                .tools(List.of(toolNames).stream()
                        .map(n -> ToolDefinition.builder().name(n).build())
                        .toList())
                .build();
    }

    private static ChatRequest replaying(String toolName) {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.user("look it up"),
                        MultimodalMessage.builder()
                                .role("assistant")
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call_1").name(toolName).arguments("{}").build()))
                                .build()))
                .build();
    }

    // ---- denylist ----------------------------------------------------------------

    @Test
    void denylist_matchesAToolDefinitionTheRequestOffers() {
        // The decisive case: the gateway governs before dispatch, so a denied tool being OFFERED is
        // what there is to stop. A call is something that has already happened.
        assertThat(new ToolDenylistRule(Set.of("shell_exec")).matches(ctx, offering("shell_exec")))
                .isTrue();
    }

    @Test
    void denylist_matchesADeniedToolReplayedInTheHistory() {
        // An agent loop resends earlier turns; a client need not resend `tools` with them.
        assertThat(new ToolDenylistRule(Set.of("shell_exec")).matches(ctx, replaying("shell_exec")))
                .isTrue();
    }

    @Test
    void denylist_doesNotMatchAToolOutsideIt() {
        assertThat(new ToolDenylistRule(Set.of("shell_exec")).matches(ctx, offering("web_search")))
                .isFalse();
    }

    @Test
    void denylist_doesNotMatchARequestNamingNoTools() {
        ChatRequest plain = ChatRequest.builder()
                .model("gpt-4o").messages(List.of(MultimodalMessage.user("hello"))).build();
        assertThat(new ToolDenylistRule(Set.of("shell_exec")).matches(ctx, plain)).isFalse();
    }

    @Test
    void denylist_survivesNullMessagesAndNullTools() {
        ChatRequest empty = ChatRequest.builder().model("gpt-4o").messages(null).build();
        assertThat(new ToolDenylistRule(Set.of("shell_exec")).matches(ctx, empty)).isFalse();
    }

    // ---- allowlist --------------------------------------------------------------

    @Test
    void allowlist_matchesAToolItDoesNotCover() {
        assertThat(new ToolAllowlistRule(Set.of("web_search")).matches(ctx, offering("shell_exec")))
                .isTrue();
    }

    @Test
    void allowlist_matchesWhenOneOfSeveralOfferedToolsIsUncovered() {
        assertThat(new ToolAllowlistRule(Set.of("web_search"))
                .matches(ctx, offering("web_search", "shell_exec"))).isTrue();
    }

    @Test
    void allowlist_doesNotMatchWhenEveryNamedToolIsCovered() {
        assertThat(new ToolAllowlistRule(Set.of("web_search", "shell_exec"))
                .matches(ctx, offering("web_search"))).isFalse();
    }

    @Test
    void allowlist_doesNotMatchARequestNamingNoTools() {
        // An allowlist restricts which tools may be used; it is not a requirement to use one.
        ChatRequest plain = ChatRequest.builder()
                .model("gpt-4o").messages(List.of(MultimodalMessage.user("hello"))).build();
        assertThat(new ToolAllowlistRule(Set.of("web_search")).matches(ctx, plain)).isFalse();
    }

    @Test
    void allowlist_matchesAnUncoveredToolReplayedInTheHistory() {
        assertThat(new ToolAllowlistRule(Set.of("web_search")).matches(ctx, replaying("shell_exec")))
                .isTrue();
    }

    @Test
    void aToolDefinitionWithNoNameIsIgnoredRatherThanMatchedAsNull() {
        ChatRequest nameless = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.user("hi")))
                .tools(List.of(ToolDefinition.builder().description("no name").build()))
                .build();
        assertThat(new ToolDenylistRule(Set.of("shell_exec")).matches(ctx, nameless)).isFalse();
        assertThat(new ToolAllowlistRule(Set.of("web_search")).matches(ctx, nameless)).isFalse();
    }
}
