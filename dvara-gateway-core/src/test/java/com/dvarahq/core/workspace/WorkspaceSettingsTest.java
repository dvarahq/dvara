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
package com.dvarahq.core.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings a workspace carries apart from its metadata. A store that keeps them in their own place
 * must get them back unchanged, and a workspace written without them must still read.
 */
class WorkspaceSettingsTest {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .build();

    @Test
    void settingsSurviveAJsonRoundTripBesideMetadata() throws Exception {
        Workspace workspace = Workspace.builder()
                .id("w1")
                .name("team")
                .status(WorkspaceStatus.ACTIVE)
                .metadata(Map.of("tier", "pro"))
                .settings(Map.of("pii", Map.of("action", "BLOCK")))
                .build();

        Workspace read = JSON.readValue(JSON.writeValueAsString(workspace), Workspace.class);

        assertThat(read.getSettings()).isEqualTo(Map.of("pii", Map.of("action", "BLOCK")));
        assertThat(read.getMetadata()).isEqualTo(Map.of("tier", "pro"));
        assertThat(read).isEqualTo(workspace);
    }

    @Test
    void aWorkspaceWrittenWithoutSettingsStillReads() throws Exception {
        Workspace read = JSON.readValue(
                "{\"id\":\"w1\",\"name\":\"team\",\"status\":\"ACTIVE\",\"metadata\":{\"tier\":\"pro\"}}",
                Workspace.class);

        assertThat(read.getSettings()).isNull();
        assertThat(read.getMetadata()).containsEntry("tier", "pro");
    }

    @Test
    void settingsTakePartInEquality() {
        Workspace a = Workspace.builder().id("w1").settings(Map.of("pii", Map.of("action", "BLOCK"))).build();
        Workspace b = Workspace.builder().id("w1").settings(Map.of("pii", Map.of("action", "LOG"))).build();

        assertThat(a).isNotEqualTo(b);
    }
    // ---- governanceSettings(): the settings in force -------------------------------------------

    @Test
    void withNoSettingsTheMetadataIsReadAsItIs() {
        Map<String, Object> metadata = Map.of("tier", "pro", "pii.action", "BLOCK");
        Workspace workspace = Workspace.builder().id("w1").metadata(metadata).build();

        assertThat(workspace.governanceSettings()).isEqualTo(metadata);
        assertThat(Workspace.builder().id("w1").metadata(metadata).settings(Map.of()).build().governanceSettings())
                .isEqualTo(metadata);
    }

    @Test
    void withNeitherMapTheResultIsEmptyNotNull() {
        assertThat(Workspace.builder().id("w1").build().governanceSettings()).isEmpty();
    }

    @Test
    void anAreaInTheSettingsIsLaidOverTheMetadata() {
        Workspace workspace = Workspace.builder().id("w1")
                .metadata(Map.of("tier", "pro", "guardrail.action", "LOG"))
                .settings(Map.of("pii", Map.of("pii.action", "BLOCK")))
                .build();

        assertThat(workspace.governanceSettings()).isEqualTo(Map.of(
                "tier", "pro", "guardrail.action", "LOG", "pii.action", "BLOCK"));
    }

    /**
     * A rule cleared in the settings must stop applying. If the area only overwrote the keys it repeats,
     * an older copy in the metadata would take over the one it left out.
     */
    @Test
    void anAreaInTheSettingsHidesEveryMetadataKeyItOwns() {
        Workspace workspace = Workspace.builder().id("w1")
                .metadata(Map.of(
                        "tier", "pro",
                        "guardrail.content.custom-denylist", Map.of("old", "secret"),
                        "grounding.enabled", true,
                        "embedded.enabled-filters", "ZIP_CODE",
                        "pii.action", "LOG"))
                .settings(Map.of("guardrail", Map.of("guardrail.action", "BLOCK")))
                .build();

        assertThat(workspace.governanceSettings()).isEqualTo(Map.of(
                "tier", "pro", "pii.action", "LOG", "guardrail.action", "BLOCK"));
    }

    @Test
    void anEmptyAreaClearsThatArea() {
        Workspace workspace = Workspace.builder().id("w1")
                .metadata(Map.of("approval.required-tools", "delete_*", "tier", "pro"))
                .settings(Map.of("approval", Map.of()))
                .build();

        assertThat(workspace.governanceSettings()).isEqualTo(Map.of("tier", "pro"));
    }

    @Test
    void everyAreaOwnsItsPrefixes() {
        assertThat(Workspace.SETTINGS_AREAS).containsOnlyKeys("pii", "guardrail", "agentic", "approval", "governance");
        for (String key : new String[] {"agentic.loop-detection.enabled", "approval.timeout-seconds",
                "audit.store-prompts", "credentials.require-workspace-credential", "ip-access.allowlist"}) {
            String area = Workspace.SETTINGS_AREAS.entrySet().stream()
                    .filter(e -> e.getValue().stream().anyMatch(key::startsWith))
                    .map(Map.Entry::getKey).findFirst().orElse(null);
            Workspace workspace = Workspace.builder().id("w1").metadata(Map.of(key, "x"))
                    .settings(Map.of(area, Map.of())).build();
            assertThat(workspace.governanceSettings()).as(key).isEmpty();
        }
    }

    @Test
    void theResultCannotBeChanged() {
        Workspace workspace = Workspace.builder().id("w1").metadata(Map.of("tier", "pro")).build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> workspace.governanceSettings().put("x", 1))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theViewIsNotWrittenAsAProperty() throws Exception {
        Workspace workspace = Workspace.builder().id("w1").metadata(Map.of("tier", "pro")).build();

        assertThat(JSON.writeValueAsString(workspace)).doesNotContain("governanceSettings");
    }
}
