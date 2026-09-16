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

import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.settings.WorkspaceSettingEntry;
import com.dvarahq.core.workspace.settings.WorkspaceSettingEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContentFilterDetectorTest {

    private ContentFilterDetector detector;

    @BeforeEach
    void setUp() {
        WorkspaceRepository workspaceRepository = mock(WorkspaceRepository.class);
        // No workspace metadata → detector uses the default content-pattern registry.
        when(workspaceRepository.findById(any())).thenReturn(Optional.empty());
        detector = new ContentFilterDetector(new ContentPatternRegistry(), workspaceRepository);
    }

    @Test
    void scanRequest_detectsContentViolationInMessages() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("user")
                                .content(List.of(new ContentBlock.TextBlock("I want to kill them all")))
                                .build()))
                .build();

        List<GuardrailScanResult> results = detector.scanRequest(request, "workspace-1");
        assertThat(results).anyMatch(GuardrailScanResult::hasDetections);
    }

    @Test
    void scanRequest_detectsContentViolationInToolCallArguments() {
        // a content-policy violation smuggled into a tool call's arguments
        // (replayed as assistant history) must be caught, not just violations in
        // message text. Content is null — the real assistant tool-call replay shape —
        // so the only source of a detection is the tool-call arguments, genuinely
        // exercising the tool-call-only assistant message path: the early
        // content guard must not skip the tool-arg scan.
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("assistant")
                                .content(null)
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call_1").name("run")
                                        .arguments("{\"note\":\"I want to kill them all\"}")
                                        .build()))
                                .build()))
                .build();

        List<GuardrailScanResult> results = detector.scanRequest(request, "workspace-1");
        assertThat(results).anyMatch(GuardrailScanResult::hasDetections);
    }

    @Test
    void scanRequest_benignToolCallArguments_noDetections() {
        // benign content inside tool-call arguments must not trip the filter.
        // Null content (the real assistant tool-call replay shape) → tool args are
        // the only scanned source.
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("assistant")
                                .content(null)
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call_1").name("get_weather")
                                        .arguments("{\"city\":\"What is the weather today?\"}")
                                        .build()))
                                .build()))
                .build();

        List<GuardrailScanResult> results = detector.scanRequest(request, "workspace-1");
        assertThat(results).noneMatch(GuardrailScanResult::hasDetections);
    }

    // ---------------------------------------------------------------------------------------------
    // guardrail.content.enabled is read with SettingsBooleans, not Boolean.parseBoolean.
    //
    // Under Boolean.parseBoolean anything other than "true" is false, so a workspace writing "yes",
    // or one with a typo, would have content filtering silently OFF.
    // ---------------------------------------------------------------------------------------------

    private static ContentFilterDetector detectorWith(Object contentEnabledValue) {
        Workspace workspace = new Workspace();
        workspace.setId("workspace-1");
        workspace.setMetadata(Map.of("guardrail.content.enabled", contentEnabledValue));
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById(any())).thenReturn(Optional.of(workspace));
        return new ContentFilterDetector(new ContentPatternRegistry(), workspaces);
    }

    @Test
    void yamlTruthyValuesKeepContentFilteringOn() {
        for (Object value : List.of("yes", "on", "1", "true", "TRUE", "y", Boolean.TRUE, 1)) {
            assertThat(detectorWith(value).scan("I want to kill them all", "workspace-1").hasDetections())
                    .as("content filtering must stay on for %s", value)
                    .isTrue();
        }
    }

    @Test
    void yamlFalsyValuesSwitchContentFilteringOff() {
        for (Object value : List.of("no", "off", "0", "false", "n", Boolean.FALSE, 0)) {
            assertThat(detectorWith(value).scan("I want to kill them all", "workspace-1").hasDetections())
                    .as("content filtering must be off for %s", value)
                    .isFalse();
        }
    }

    @Test
    void unreadableValueInheritsRatherThanDisabling() {
        // A value the system cannot read must not be able to mean "unprotected".
        assertThat(detectorWith("maybe").scan("I want to kill them all", "workspace-1").hasDetections())
                .isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // The custom denylist reads its typed rows, which win over the metadata map.
    // ---------------------------------------------------------------------------------------------

    private static ContentFilterDetector detectorWith(Map<String, Object> metadata,
                                                      Map<String, String> typedDenylist) {
        Workspace workspace = new Workspace();
        workspace.setId("workspace-1");
        workspace.setMetadata(metadata);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById(any())).thenReturn(Optional.of(workspace));
        ContentFilterDetector detector = new ContentFilterDetector(new ContentPatternRegistry(), workspaces);
        if (typedDenylist != null) {
            WorkspaceSettingEntryRepository entries = mock(WorkspaceSettingEntryRepository.class);
            when(entries.mapOf(anyString(), any(WorkspaceSettingEntry.Kind.class))).thenReturn(typedDenylist);
            detector.setSettingEntries(entries);
        }
        return detector;
    }

    @Test
    void customDenylistFromTypedRowsIsApplied() {
        ContentFilterDetector detector = detectorWith(Map.of(), Map.of("project-codename", "bluebird"));

        GuardrailScanResult result = detector.scan("the bluebird launch date", "workspace-1");

        assertThat(result.hasDetections()).isTrue();
        assertThat(result.detections()).anyMatch(d -> "project-codename".equals(d.label()));
    }

    @Test
    void typedDenylistRowsWinOverTheMetadataMap() {
        ContentFilterDetector detector = detectorWith(
                Map.of("guardrail.content.custom-denylist", Map.of("map-rule", "starling")),
                Map.of("typed-rule", "bluebird"));

        assertThat(detector.scan("bluebird", "workspace-1").hasDetections()).isTrue();
        assertThat(detector.scan("starling", "workspace-1").hasDetections()).isFalse();
    }

    @Test
    void customDenylistStillReadsTheMetadataMapWhenThereAreNoRows() {
        ContentFilterDetector detector = detectorWith(
                Map.of("guardrail.content.custom-denylist", Map.of("map-rule", "starling")), Map.of());

        assertThat(detector.scan("starling", "workspace-1").hasDetections()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------
    // The response side scans tool calls as well as text.
    // ---------------------------------------------------------------------------------------------

    @Test
    void scanResponse_detectsViolationInToolCallArguments() {
        // Content null is the real assistant tool-call shape, so the only possible source of a
        // detection is the arguments.
        ChatResponse response = ChatResponse.builder()
                .choices(List.of(ChatResponse.Choice.builder()
                        .message(MultimodalMessage.builder()
                                .role("assistant")
                                .content(null)
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call_1").name("send_message")
                                        .arguments("{\"body\":\"I want to kill them all\"}")
                                        .build()))
                                .build())
                        .build()))
                .build();

        assertThat(detector.scanResponse(response, "workspace-1"))
                .anyMatch(GuardrailScanResult::hasDetections);
    }

    @Test
    void scanResponse_stillScansText() {
        ChatResponse response = ChatResponse.builder()
                .choices(List.of(ChatResponse.Choice.builder()
                        .message(MultimodalMessage.builder().role("assistant")
                                .content(List.of(new ContentBlock.TextBlock("I want to kill them all")))
                                .build())
                        .build()))
                .build();

        assertThat(detector.scanResponse(response, "workspace-1"))
                .anyMatch(GuardrailScanResult::hasDetections);
    }

    // ---------------------------------------------------------------------------------------------
    // A workspace with no metadata map still gets its typed settings read.
    // ---------------------------------------------------------------------------------------------

    @Test
    void nullMetadataDoesNotSkipTheTypedContentRules() {
        Workspace workspace = new Workspace();
        workspace.setId("workspace-1");
        workspace.setMetadata(null);
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        when(workspaces.findById(any())).thenReturn(Optional.of(workspace));
        ContentFilterDetector detector = new ContentFilterDetector(new ContentPatternRegistry(), workspaces);
        WorkspaceSettingEntryRepository entries = mock(WorkspaceSettingEntryRepository.class);
        when(entries.mapOf(anyString(), any(WorkspaceSettingEntry.Kind.class)))
                .thenReturn(Map.of("codename", "bluebird"));
        detector.setSettingEntries(entries);

        assertThat(detector.scan("the bluebird launch", "workspace-1").hasDetections()).isTrue();
    }
}
