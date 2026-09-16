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
package com.dvarahq.core.workspace.settings;

import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiDegradedAction;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The typed form of a workspace's PII posture.
 *
 * <p>Same asymmetry as {@link GuardrailSettings}: an unknown action on a <em>read</em> is
 * warn-and-inherit; on a <em>write</em> it is refused with the list of valid values. This matters
 * because the fallback is not {@code BLOCK} but whatever the install is configured for, so a typo
 * silently relaxes a workspace's stated posture rather than tightening it.</p>
 */
class PiiSettingsTest {

    private static Map<String, Object> metadata(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void unset_andEmptyMapsAreUnset() {
        assertThat(PiiSettings.unset("t1").isUnset()).isTrue();
        assertThat(PiiSettings.fromMetadata("t1", null).isUnset()).isTrue();
        assertThat(PiiSettings.fromMetadata("t1", Map.of()).isUnset()).isTrue();
        assertThat(PiiSettings.unset("t1").workspaceId()).isEqualTo("t1");
    }

    @Test
    void fromMetadata_readsEveryKey() {
        PiiSettings s = PiiSettings.fromMetadata("t1", metadata(
                "pii.enabled", true,
                "pii.action", "REDACT",
                "pii.scan-responses", true,
                "pii.auto-detokenize-response", true,
                "pii.degraded-action", "LOG",
                "pii.custom-patterns", Map.of("employee-id", "EMP-\\d{6}")));

        assertThat(s.enabled()).isTrue();
        assertThat(s.action()).isEqualTo(PiiAction.REDACT);
        assertThat(s.scanResponses()).isTrue();
        assertThat(s.autoDetokenizeResponse()).isTrue();
        assertThat(s.degradedAction()).isEqualTo(PiiDegradedAction.LOG);
        assertThat(s.customPatterns()).containsEntry("employee-id", "EMP-\\d{6}");
    }

    @Test
    void fromMetadata_acceptsAnActionInAnyCase() {
        assertThat(PiiSettings.fromMetadata("t1", metadata("pii.action", "block")).action())
                .isEqualTo(PiiAction.BLOCK);
    }

    /**
     * An unrecognised action on a read is dropped, so the workspace inherits. Recorded because the
     * direction is worth being clear about: inheriting is not the same as {@code BLOCK}, so a typo
     * relaxes the posture the workspace thought it had set.
     */
    @Test
    void fromMetadata_dropsAnUnknownActionSoTheWorkspaceInherits() {
        assertThat(PiiSettings.fromMetadata("t1", metadata("pii.action", "SCRAMBLE")).action()).isNull();
    }

    @Test
    void customPatterns_skipNullKeysAndValuesAndStringifyTheRest() {
        Map<Object, Object> raw = new LinkedHashMap<>();
        raw.put("employee-id", "EMP-\\d+");
        raw.put("numeric", 42);
        raw.put(null, "orphan");
        raw.put("no-value", null);

        PiiSettings s = PiiSettings.fromMetadata("t1", metadata("pii.custom-patterns", raw));

        assertThat(s.customPatterns())
                .containsEntry("employee-id", "EMP-\\d+")
                .containsEntry("numeric", "42")
                .hasSize(2);
    }

    @Test
    void customPatterns_thatAreNotAMapAreIgnored() {
        assertThat(PiiSettings.fromMetadata("t1", metadata("pii.custom-patterns", "not-a-map"))
                .customPatterns()).isEmpty();
    }

    @Test
    void toMetadata_emitsOnlySetFieldsAndRoundTrips() {
        Map<String, Object> original = metadata(
                "pii.enabled", true, "pii.action", "REDACT", "pii.degraded-action", "BLOCK");

        PiiSettings once = PiiSettings.fromMetadata("t1", original);
        PiiSettings twice = PiiSettings.fromMetadata("t1", once.toMetadata());

        assertThat(twice).isEqualTo(once);
        assertThat(once.toMetadata()).doesNotContainKey("pii.scan-responses");
    }

    // --- the write path -------------------------------------------------------------------------

    @Test
    void parseAction_acceptsTheKnownActionsInAnyCase() {
        assertThat(PiiSettings.parseAction("block")).isEqualTo(PiiAction.BLOCK);
        assertThat(PiiSettings.parseAction(" REDACT ")).isEqualTo(PiiAction.REDACT);
        assertThat(PiiSettings.parseAction("Log")).isEqualTo(PiiAction.LOG);
    }

    @Test
    void parseAction_treatsBlankAsUnset() {
        assertThat(PiiSettings.parseAction(null)).isNull();
        assertThat(PiiSettings.parseAction("   ")).isNull();
    }

    @Test
    void parseAction_refusesAnUnknownActionAndListsTheValidOnes() {
        assertThatThrownBy(() -> PiiSettings.parseAction("SCRAMBLE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown PII action")
                .hasMessageContaining("BLOCK");
    }

    /** The two paths disagree by design: dropped on a read, refused on a write. */
    @Test
    void theReadPathDropsWhatTheWritePathRefuses() {
        assertThat(PiiSettings.fromMetadata("t1", metadata("pii.action", "SCRAMBLE")).action()).isNull();
        assertThatThrownBy(() -> PiiSettings.parseAction("SCRAMBLE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // withFallback — what a writer needs to carry a workspace's map forward
    // -------------------------------------------------------------------------

    /**
     * Each null field takes the fallback's value; each stated field keeps its own.
     *
     * <p>This exists for write paths. A row is read wholesale, so a writer that seeds a new row from
     * {@link PiiSettings#unset} and fills in part of a form stores nulls for the rest and the
     * workspace silently loses whatever its metadata said — its own PII patterns included. A writer
     * with the map in front of it overlays onto it instead.
     */
    @Test
    void withFallback_takesTheFallbackOnlyWhereThisSaysNothing() {
        PiiSettings fromMap = PiiSettings.fromMetadata("ws-1", Map.of(
                "pii.enabled", true,
                "pii.action", "REDACT",
                "pii.custom-patterns", Map.of("employee-id", "EMP-[0-9]{6}"),
                "pii.degraded-action", "LOG"));

        PiiSettings formInput = new PiiSettings("ws-1", true,
                PiiSettings.parseAction("LOG"), true, null, null, Map.of());

        PiiSettings merged = formInput.withFallback(fromMap);

        assertThat(merged.action()).as("the form's own field wins")
                .isEqualTo(PiiSettings.parseAction("LOG"));
        assertThat(merged.scanResponses()).isTrue();
        assertThat(merged.customPatterns())
                .as("an empty pattern map is no opinion, not an instruction to forget them")
                .containsEntry("employee-id", "EMP-[0-9]{6}");
        assertThat(merged.degradedAction()).isEqualTo(fromMap.degradedAction());
    }

    @Test
    void withFallback_onAnUnsetFallbackOrNullChangesNothing() {
        PiiSettings stated = new PiiSettings("ws-1", false,
                PiiSettings.parseAction("BLOCK"), false, false, null, Map.of("a", "b"));

        assertThat(stated.withFallback(PiiSettings.unset("ws-1"))).isEqualTo(stated);
        assertThat(stated.withFallback(null)).isSameAs(stated);
    }

    /** A stated {@code false} is an opinion and must not be replaced by a fallback's {@code true}. */
    @Test
    void withFallback_doesNotTreatFalseAsUnset() {
        PiiSettings off = new PiiSettings("ws-1", false, null, false, null, null, Map.of());
        PiiSettings on = new PiiSettings("ws-1", true, null, true, null, null, Map.of());

        PiiSettings merged = off.withFallback(on);

        assertThat(merged.enabled()).isFalse();
        assertThat(merged.scanResponses()).isFalse();
    }
}
