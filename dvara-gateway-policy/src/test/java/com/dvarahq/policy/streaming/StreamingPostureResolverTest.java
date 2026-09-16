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

import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.enforcement.StreamingPosture;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.workspace.settings.GuardrailSettings;
import com.dvarahq.core.workspace.settings.PiiSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Group D of the streaming enforcement test matrix: configuration precedence.
 *
 * <p>Twelve cells: six fields against two conflicts each. Each cell states a contradiction between
 * two sources and asserts which wins: the typed row over metadata, and a global switch as an AND
 * rather than a default a workspace can override.</p>
 */
class StreamingPostureResolverTest {

    private static final StreamingDefaults ALL_ON = new StreamingDefaults(
            true, true, PiiAction.LOG,
            true, true, GuardrailAction.LOG, 0.7,
            false, GuardrailAction.LOG, 50, 10_000);

    // ---------------------------------------------- D/*/TYPED_VS_META — typed wins

    @Test
    @DisplayName("D/pii.enabled/TYPED_VS_META")
    void typedPiiEnabledBeatsMetadata() {
        var posture = resolve(ALL_ON, pii(false, null), null, Map.of("pii.enabled", "true"));

        assertThat(posture.piiEnabled()).isFalse();
    }

    @Test
    @DisplayName("D/pii.scan-streaming-responses/TYPED_VS_META")
    void typedRowHasNoStreamingColumnSoMetadataGovernsThatField() {
        // The typed table carries `scanResponses`, which is the non-streaming `pii.scan-responses`.
        // It must not stand in for the streaming field: posture resolves per field, and a typed value
        // for a different field overriding this one is the inversion the cell exists to catch.
        var typedOff = resolve(ALL_ON, pii(null, null, false), null,
                Map.of("pii.scan-streaming-responses", "true"));
        var typedOn = resolve(ALL_ON, pii(null, null, true), null,
                Map.of("pii.scan-streaming-responses", "false"));

        assertThat(typedOff.piiEnabled()).isTrue();
        assertThat(typedOn.piiEnabled()).isFalse();
    }

    @Test
    @DisplayName("a typed row with NO custom patterns clears them; metadata cannot resurrect deleted ones")
    void typedRowWithEmptyPatternsIsCleared() {
        var typed = new PiiSettings("t1", null, null, null, null, null, Map.of());
        var posture = resolve(ALL_ON, typed, null,
                Map.of("pii.custom-patterns", Map.of("secret", "SECRET:[0-9]+")));

        assertThat(posture.customPiiPatterns()).isEmpty();
    }

    @Test
    @DisplayName("without a typed row, metadata custom patterns apply; with one, the row's win")
    void metadataPatternsOnlyWithoutATypedRow() {
        var meta = Map.<String, Object>of("pii.custom-patterns", Map.of("secret", "SECRET:[0-9]+"));

        assertThat(resolve(ALL_ON, null, null, meta).customPiiPatterns())
                .containsEntry("secret", "SECRET:[0-9]+");
        var typed = new PiiSettings("t1", null, null, null, null, null, Map.of("id", "ID-[0-9]{6}"));
        assertThat(resolve(ALL_ON, typed, null, meta).customPiiPatterns())
                .containsOnlyKeys("id");
    }

    @Test
    @DisplayName("typed pii.action beats metadata")
    void typedPiiActionBeatsMetadata() {
        var posture = resolve(ALL_ON, pii(null, PiiAction.BLOCK), null, Map.of("pii.action", "LOG"));

        assertThat(posture.piiAction()).isEqualTo(PiiAction.BLOCK);
    }

    @Test
    @DisplayName("pii.scan-responses is a different field and does not switch streaming off")
    void nonStreamingScanFlagDoesNotGovernStreaming() {
        var posture = resolve(ALL_ON, null, null, Map.of("pii.scan-responses", "false"));

        assertThat(posture.piiEnabled()).isTrue();
    }

    @Test
    @DisplayName("D/guardrail.enabled/TYPED_VS_META")
    void typedGuardrailEnabledBeatsMetadata() {
        var posture = resolve(ALL_ON, null, guardrail(false, null, null, null),
                Map.of("guardrail.enabled", "true"));

        assertThat(posture.guardrailEnabled()).isFalse();
    }

    @Test
    @DisplayName("D/guardrail.scan-streaming-responses/TYPED_VS_META")
    void typedGuardrailStreamScanBeatsMetadata() {
        var posture = resolve(ALL_ON, null, guardrail(null, false, null, null),
                Map.of("guardrail.scan-streaming-responses", "true"));

        assertThat(posture.guardrailEnabled()).isFalse();
    }

    @Test
    @DisplayName("D/grounding.enabled/TYPED_VS_META")
    void typedGroundingEnabledBeatsMetadata() {
        var posture = resolve(ALL_ON, null, guardrail(null, null, true, null),
                Map.of("grounding.enabled", "false"));

        assertThat(posture.groundingEnabled()).isTrue();
    }

    @Test
    @DisplayName("D/grounding.action/TYPED_VS_META")
    void typedGroundingActionBeatsMetadata() {
        var posture = resolve(ALL_ON, null, guardrail(null, null, true, "BLOCK"),
                Map.of("grounding.action", "LOG"));

        assertThat(posture.groundingAction()).isEqualTo(GuardrailAction.BLOCK);
    }

    // ------------------------------------- D/*/GLOBAL_VS_WORKSPACE — AND for kill switches

    @Test
    @DisplayName("D/pii.enabled/GLOBAL_VS_WORKSPACE")
    void globalPiiOffCannotBeReEnabled() {
        var defaults = new StreamingDefaults(false, true, PiiAction.LOG,
                true, true, GuardrailAction.LOG, 0.7, false, GuardrailAction.LOG, 50, 10_000);

        var posture = resolve(defaults, pii(true, PiiAction.BLOCK), null, Map.of("pii.enabled", "true"));

        assertThat(posture.piiEnabled())
                .as("an operator switching PII off switches it off for everyone")
                .isFalse();
    }

    @Test
    @DisplayName("D/pii.scan-streaming-responses/GLOBAL_VS_WORKSPACE")
    void globalStreamScanOffCannotBeReEnabled() {
        var defaults = new StreamingDefaults(true, false, PiiAction.LOG,
                true, true, GuardrailAction.LOG, 0.7, false, GuardrailAction.LOG, 50, 10_000);

        var posture = resolve(defaults, pii(true, null), null,
                Map.of("pii.scan-streaming-responses", "true"));

        assertThat(posture.piiEnabled()).isFalse();
    }

    @Test
    @DisplayName("D/guardrail.enabled/GLOBAL_VS_WORKSPACE")
    void globalGuardrailOffCannotBeReEnabled() {
        var defaults = new StreamingDefaults(true, true, PiiAction.LOG,
                false, true, GuardrailAction.LOG, 0.7, false, GuardrailAction.LOG, 50, 10_000);

        var posture = resolve(defaults, null, guardrail(true, true, null, null),
                Map.of("guardrail.enabled", "true"));

        assertThat(posture.guardrailEnabled()).isFalse();
    }

    @Test
    @DisplayName("D/guardrail.scan-streaming-responses/GLOBAL_VS_WORKSPACE")
    void globalGuardrailStreamScanOffCannotBeReEnabled() {
        var defaults = new StreamingDefaults(true, true, PiiAction.LOG,
                true, false, GuardrailAction.LOG, 0.7, false, GuardrailAction.LOG, 50, 10_000);

        var posture = resolve(defaults, null, guardrail(true, true, null, null), Map.of());

        assertThat(posture.guardrailEnabled()).isFalse();
    }

    @Test
    @DisplayName("D/grounding.enabled/GLOBAL_VS_WORKSPACE — an override, not a kill switch")
    void aWorkspaceMayRaiseGrounding() {
        // Deliberately not an AND: there is no separate global scan flag for grounding, and a
        // workspace enabling it is asking for MORE checking, not less.
        var posture = resolve(ALL_ON, null, guardrail(null, null, true, null), Map.of());

        assertThat(posture.groundingEnabled()).isTrue();
    }

    @Test
    @DisplayName("D/grounding.action/GLOBAL_VS_WORKSPACE — a workspace may tighten it")
    void aWorkspaceMayTightenGroundingAction() {
        var posture = resolve(ALL_ON, null, guardrail(null, null, true, "BLOCK"), Map.of());

        assertThat(posture.groundingAction()).isEqualTo(GuardrailAction.BLOCK);
    }

    // ---------------------------------------------------------------- fallthrough

    @Test
    @DisplayName("metadata governs the field the typed row left null")
    void metadataFillsTheGaps() {
        var posture = resolve(ALL_ON, pii(null, null), null, Map.of("pii.action", "REDACT"));

        assertThat(posture.piiAction())
                .as("second precedence means second, not ignored")
                .isEqualTo(PiiAction.REDACT);
    }

    @Test
    @DisplayName("metadata fills a null guardrail risk threshold")
    void metadataRiskThresholdFillsTheGap() {
        var posture = resolve(ALL_ON, null, guardrail(null, null, null, null),
                Map.of("guardrail.risk-score-threshold", "0.42"));

        assertThat(posture.guardrailRiskThreshold()).isEqualTo(0.42);
    }

    @Test
    @DisplayName("an unreadable action names the value and inherits")
    void unreadableActionInherits() {
        var posture = resolve(ALL_ON, null, guardrail(null, null, true, null),
                Map.of("grounding.action", "BLOK"));

        assertThat(posture.groundingAction()).isEqualTo(GuardrailAction.LOG);
    }

    // ------------------------------------------------------------------- helpers

    // ---------------------------------------------- grounding source caps
    //
    // The documents come from the caller's own request body and the detector embeds every one it is
    // given, so these two settings are the only bound on the work one request can ask for. They must
    // apply on the streamed path as on the sync one, or the same stored settings would give one
    // answer on stream=false and another on stream=true.

    @Test
    @DisplayName("D/grounding.max-sources/APPLIED — sources past the cap do not reach the detector")
    void sourcesPastTheCapAreDropped() {
        var posture = resolve(ALL_ON, null, Map.of("grounding.max-sources", "3"), sources(10, 20));

        assertThat(posture.groundingSources()).hasSize(3);
    }

    @Test
    @DisplayName("D/grounding.max-source-length/APPLIED — an oversized document is dropped whole")
    void oversizedSourcesAreDropped() {
        var posture = resolve(ALL_ON, null, Map.of("grounding.max-source-length", "50"),
                List.of("short one", "x".repeat(500), "short two"));

        assertThat(posture.groundingSources()).containsExactly("short one", "short two");
    }

    @Test
    @DisplayName("D/grounding.max-sources/TYPED_VS_META — typed wins")
    void typedGroundingCapsBeatMetadata() {
        var posture = resolve(ALL_ON, guardrail(null, null, null, null, 2, 1000),
                Map.of("grounding.max-sources", "9", "grounding.max-source-length", "9999"),
                sources(9, 20));

        assertThat(posture.groundingSources()).hasSize(2);
    }

    @Test
    @DisplayName("D/grounding caps/DEFAULT — the install-wide values apply when nothing overrides")
    void installWideCapsApplyWithNoOverride() {
        var posture = resolve(ALL_ON, null, Map.of(), sources(80, 20));

        assertThat(posture.groundingSources()).hasSize(ALL_ON.groundingMaxSources());
    }

    @Test
    @DisplayName("D/grounding caps/UNLIMITED — a non-positive value means no bound, as on the sync path")
    void nonPositiveMeansUnlimited() {
        var defaults = new StreamingDefaults(true, true, PiiAction.LOG,
                true, true, GuardrailAction.LOG, 0.7, false, GuardrailAction.LOG, 0, 0);

        var posture = resolve(defaults, null, Map.of(), sources(200, 4000));

        assertThat(posture.groundingSources()).hasSize(200);
    }

    @Test
    @DisplayName("D/grounding.max-sources/INVALID — a value that is not a number warns and inherits")
    void unreadableCapInheritsRatherThanRemovingTheBound() {
        var posture = resolve(ALL_ON, null, Map.of("grounding.max-sources", "lots"), sources(80, 20));

        assertThat(posture.groundingSources()).hasSize(ALL_ON.groundingMaxSources());
    }

    private static StreamingPosture resolve(StreamingDefaults defaults, PiiSettings pii,
                                            GuardrailSettings guardrail, Map<String, Object> meta) {
        return StreamingPostureResolver.resolve(defaults, pii, guardrail, meta, List.of(), "t1");
    }

    private static StreamingPosture resolve(StreamingDefaults defaults, GuardrailSettings guardrail,
                                            Map<String, Object> meta, List<String> sources) {
        return StreamingPostureResolver.resolve(defaults, null, guardrail, meta, sources, "t1");
    }

    private static List<String> sources(int count, int length) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> "s" + i + "-" + "x".repeat(Math.max(0, length - String.valueOf(i).length() - 2)))
                .toList();
    }

    private static PiiSettings pii(Boolean enabled, PiiAction action) {
        return pii(enabled, action, null);
    }

    private static PiiSettings pii(Boolean enabled, PiiAction action, Boolean scanResponses) {
        return new PiiSettings("t1", enabled, action, scanResponses, null, null, Map.of());
    }

    private static GuardrailSettings guardrail(Boolean enabled, Boolean scanStreaming,
                                               Boolean groundingEnabled, String groundingAction) {
        return guardrail(enabled, scanStreaming, groundingEnabled, groundingAction, null, null);
    }

    private static GuardrailSettings guardrail(Boolean enabled, Boolean scanStreaming,
                                               Boolean groundingEnabled, String groundingAction,
                                               Integer maxSources, Integer maxSourceLength) {
        return new GuardrailSettings("t1", enabled, null, null, null, null, null, null,
                scanStreaming, null, null, null, null, null, null, null, null, null,
                groundingEnabled, groundingAction, maxSources, maxSourceLength);
    }
}
