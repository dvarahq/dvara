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
}
