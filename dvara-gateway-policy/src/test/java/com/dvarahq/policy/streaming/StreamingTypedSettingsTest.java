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
package com.dvarahq.policy.streaming;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.settings.PiiSettings;
import com.dvarahq.core.workspace.settings.PiiSettingsRepository;
import com.dvarahq.policy.guardrail.GuardrailProperties;
import com.dvarahq.pii.PiiPatternRegistry;
import com.dvarahq.pii.PiiProperties;
import com.dvarahq.pii.RegexPiiDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The typed settings tables govern a streamed response, not only a non-streamed one.
 *
 * <p>{@code PiiSettingsResolver} documents typed-store-first, and the Portal's Data Protection page
 * writes straight to those tables, so a workspace administrator who chose BLOCK there must get BLOCK
 * on a streamed request as well as on an ordinary one. Nothing would report the disagreement
 * otherwise, because each half would be internally consistent.</p>
 */
class StreamingTypedSettingsTest {

    private static final String EMAIL = "alice@example.com";

    @Test
    @DisplayName("a typed BLOCK overrides a global LOG on the streaming path")
    void typedBlockOverridesGlobalLog() {
        String out = stream(typed(settingsWith(PiiAction.BLOCK)), Map.of());

        assertThat(out)
                .as("the workspace chose BLOCK; a streamed response must not carry the value")
                .doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("a typed REDACT overrides a global LOG on the streaming path")
    void typedRedactOverridesGlobalLog() {
        String out = stream(typed(settingsWith(PiiAction.REDACT)), Map.of());

        assertThat(out).doesNotContain(EMAIL).contains("[REDACTED_EMAIL]");
    }

    @Test
    @DisplayName("a typed row wins over a stale metadata map")
    void typedWinsOverStaleMetadata() {
        // A workspace can carry an old map and a new row. Reading the map here would undo what the
        // operator last saved in the Portal.
        String out = stream(typed(settingsWith(PiiAction.REDACT)),
                Map.of("pii.action", "LOG"));

        assertThat(out).doesNotContain(EMAIL);
    }

    @Test
    @DisplayName("no typed row falls through to metadata, which still governs")
    void metadataStillGovernsWithoutATypedRow() {
        String out = stream(null, Map.of("pii.action", "REDACT"));

        assertThat(out).doesNotContain(EMAIL).contains("[REDACTED_EMAIL]");
    }

    @Test
    @DisplayName("no typed store at all inherits the install-wide setting")
    void absentStoreInherits() {
        // A pod with no datasource has no typed store. Absent must mean inherit, never "everything
        // off" — the resolver's own documented rule.
        String out = stream(null, Map.of());

        assertThat(out).as("global default is LOG, so the value passes through").contains(EMAIL);
    }

    // ---- contradictory typed row vs metadata, per field ----

    @Test
    @DisplayName("a typed disable is not re-enabled by stale metadata")
    void typedDisableBeatsMetadataEnable() {
        String out = stream(typed(new PiiSettings("t1", false, PiiAction.BLOCK, null, null, null, Map.of())),
                Map.of("pii.enabled", "true", "pii.scan-streaming-responses", "true"));

        assertThat(out)
                .as("the workspace turned PII off in the typed store; the map must not turn it back on")
                .contains(EMAIL);
    }

    @Test
    @DisplayName("the install-wide switch is an AND, not a default a workspace can override")
    void globalKillSwitchCannotBeOverridden() {
        // scan-streaming-responses=false globally means off for everyone. A starting value that
        // metadata could flip would make a kill switch behave like a suggestion.
        String out = streamWith(props -> props.setScanStreamingResponses(false),
                typed(settingsWith(PiiAction.BLOCK)),
                Map.of("pii.scan-streaming-responses", "true"));

        assertThat(out).contains(EMAIL);
    }

    @Test
    @DisplayName("metadata still governs the field the typed row left unset")
    void metadataFillsOnlyTheGapsLeftByTheTypedRow() {
        // The typed row sets an action and says nothing about enablement, so the map's enablement
        // still applies — that is what "second precedence" means, as distinct from "ignored".
        String out = stream(typed(settingsWith(PiiAction.REDACT)),
                Map.of("pii.enabled", "false"));

        assertThat(out).as("metadata disabled PII and the typed row did not contradict it")
                .contains(EMAIL);
    }

    // ------------------------------------------------------------------- harness

    private static PiiSettings settingsWith(PiiAction action) {
        return new PiiSettings("t1", null, action, null, null, null, Map.of());
    }

    private PiiSettingsRepository typed(PiiSettings stored) {
        return new PiiSettingsRepository() {
            @Override public Optional<PiiSettings> findByWorkspaceId(String workspaceId) {
                return Optional.ofNullable(stored);
            }
            @Override public List<PiiSettings> findAll() {
                return stored == null ? List.of() : List.of(stored);
            }
            @Override public PiiSettings save(PiiSettings settings) { return settings; }
            @Override public List<String> workspaceIdsByAction(PiiAction action) { return List.of(); }
        };
    }

    private String stream(PiiSettingsRepository typedStore, Map<String, Object> metadata) {
        return streamWith(props -> { }, typedStore, metadata);
    }

    private String streamWith(java.util.function.Consumer<PiiProperties> tune,
                              PiiSettingsRepository typedStore, Map<String, Object> metadata) {
        WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
        org.mockito.Mockito.when(workspaces.findById("t1")).thenReturn(Optional.of(
                Workspace.builder().id("t1").name("t1").metadata(metadata).build()));

        PiiProperties piiProperties = new PiiProperties();
        piiProperties.setDefaultAction(PiiAction.LOG);   // the global default, deliberately permissive
        tune.accept(piiProperties);
        GuardrailProperties guardrailProperties = new GuardrailProperties();
        guardrailProperties.setEnabled(false);

        var enforcer = new StreamingResponseEnforcerImpl(
                new RegexPiiDetector(new PiiPatternRegistry()),
                mock(GuardrailDetector.class),
                mock(AuditWriter.class),
                workspaces, piiProperties, guardrailProperties,
                (req, resp, sources) -> com.dvarahq.core.guardrail.GroundingResult.GROUNDED,
                com.dvarahq.core.guardrail.GroundingConfig.DISABLED,
                typedStore, null);

        Iterator<SseChunk> guarded = enforcer.wrap(chunks("mail " + EMAIL + " now"), "t1", null);
        StringBuilder seen = new StringBuilder();
        while (guarded.hasNext()) {
            SseChunk c = guarded.next();
            if (c != null && c.getDelta() != null) {
                seen.append(c.getDelta());
            }
        }
        return seen.toString();
    }

    private Iterator<SseChunk> chunks(String text) {
        List<SseChunk> list = new ArrayList<>();
        list.add(SseChunk.builder().id("c").model("m").delta(text).build());
        list.add(SseChunk.builder().id("c").model("m").finishReason("stop").done(true).build());
        return list.iterator();
    }
}
