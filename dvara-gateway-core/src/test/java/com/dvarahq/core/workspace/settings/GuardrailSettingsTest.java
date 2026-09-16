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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The typed form of a workspace's guardrail settings.
 *
 * <p>The contract worth holding is an <b>asymmetry</b>: the read path is deliberately lenient, because
 * it is reading values a store that validated nothing already accepted, and a bad one must degrade to
 * inheriting. The write path is deliberately strict, because a threshold outside {@code [0,1]} does
 * not make a guardrail stricter: it makes it match nothing, or everything, which is the worst outcome
 * available for a safety control.</p>
 */
class GuardrailSettingsTest {

    private static Map<String, Object> metadata(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    // --- unset ---------------------------------------------------------------------------------

    @Test
    void unset_isUnsetAndKeepsItsWorkspaceId() {
        GuardrailSettings settings = GuardrailSettings.unset("t1");

        assertThat(settings.isUnset()).isTrue();
        assertThat(settings.workspaceId()).isEqualTo("t1");
        assertThat(settings.toMetadata()).isEmpty();
    }

    @Test
    void anAbsentOrEmptyMapIsUnset() {
        assertThat(GuardrailSettings.fromMetadata("t1", null).isUnset()).isTrue();
        assertThat(GuardrailSettings.fromMetadata("t1", Map.of()).isUnset()).isTrue();
    }

    /** One set field is enough to stop being unset — that is what distinguishes it from a default. */
    @Test
    void oneSetFieldMakesItNotUnset() {
        assertThat(GuardrailSettings.fromMetadata("t1", metadata("guardrail.enabled", true)).isUnset())
                .isFalse();
    }

    // --- reading the map: every field ---------------------------------------------------------------

    @Test
    void fromMetadata_readsEveryKey() {
        GuardrailSettings s = GuardrailSettings.fromMetadata("t1", metadata(
                "guardrail.enabled", true,
                "guardrail.action", "BLOCK",
                "guardrail.risk-score-threshold", 0.7,
                "guardrail.max-input-tokens", 32000,
                "guardrail.max-messages-per-request", 100,
                "guardrail.max-message-length", 50000,
                "guardrail.default-max-response-tokens", 4096,
                "guardrail.scan-streaming-responses", true,
                "guardrail.context.warning-threshold-pct", 70,
                "guardrail.context.hard-threshold-pct", 90,
                "guardrail.context.pruning-strategy", "TRUNCATE_OLDEST",
                "guardrail.content.enabled", true,
                "guardrail.mcp-injection.enabled", true,
                "guardrail.mcp-injection.action", "SANITIZE",
                "guardrail.mcp-injection.risk-score-threshold", 0.5,
                "guardrail.semantic.enabled", true,
                "guardrail.semantic.similarity-threshold", 0.8,
                "grounding.enabled", true,
                "grounding.action", "FLAG",
                "grounding.max-sources", 50,
                "grounding.max-source-length", 10000));

        assertThat(s.enabled()).isTrue();
        assertThat(s.action()).isEqualTo("BLOCK");
        assertThat(s.riskScoreThreshold()).isEqualTo(0.7);
        assertThat(s.maxInputTokens()).isEqualTo(32000);
        assertThat(s.maxMessagesPerRequest()).isEqualTo(100);
        assertThat(s.maxMessageLength()).isEqualTo(50000);
        assertThat(s.defaultMaxResponseTokens()).isEqualTo(4096);
        assertThat(s.scanStreamingResponses()).isTrue();
        assertThat(s.contextWarningThresholdPct()).isEqualTo(70);
        assertThat(s.contextHardThresholdPct()).isEqualTo(90);
        assertThat(s.contextPruningStrategy()).isEqualTo("TRUNCATE_OLDEST");
        assertThat(s.contentEnabled()).isTrue();
        assertThat(s.mcpInjectionEnabled()).isTrue();
        assertThat(s.mcpInjectionAction()).isEqualTo("SANITIZE");
        assertThat(s.mcpInjectionRiskThreshold()).isEqualTo(0.5);
        assertThat(s.semanticEnabled()).isTrue();
        assertThat(s.semanticSimilarityThreshold()).isEqualTo(0.8);
        assertThat(s.groundingEnabled()).isTrue();
        assertThat(s.groundingAction()).isEqualTo("FLAG");
        assertThat(s.groundingMaxSources()).isEqualTo(50);
        assertThat(s.groundingMaxSourceLength()).isEqualTo(10000);
    }

    /** Numbers arrive from JSONB as whatever Jackson made of them, so strings must parse too. */
    @Test
    void fromMetadata_acceptsNumbersAsStrings() {
        GuardrailSettings s = GuardrailSettings.fromMetadata("t1", metadata(
                "guardrail.risk-score-threshold", "0.7",
                "guardrail.max-input-tokens", "32000",
                "guardrail.context.warning-threshold-pct", " 70 "));

        assertThat(s.riskScoreThreshold()).isEqualTo(0.7);
        assertThat(s.maxInputTokens()).isEqualTo(32000);
        assertThat(s.contextWarningThresholdPct()).isEqualTo(70);
    }

    // --- the leniency of the read path ---------------------------------------------------------------

    /**
     * Out of range on a read means <b>inherit</b>, not clamp and not reject. The store validated
     * nothing, so a workspace carrying such a value falls back to the install-wide value rather than
     * to a guardrail that matches nothing.
     */
    @Test
    void fromMetadata_treatsAnOutOfRangeThresholdAsUnset() {
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.risk-score-threshold", 1.5)).riskScoreThreshold()).isNull();
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.risk-score-threshold", -0.1)).riskScoreThreshold()).isNull();
    }

    @Test
    void fromMetadata_treatsANonNumericValueAsUnset() {
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.risk-score-threshold", "high")).riskScoreThreshold()).isNull();
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.max-input-tokens", "lots")).maxInputTokens()).isNull();
    }

    /**
     * Zero is how every one of these limits is switched off. Reading it as unset would mean
     * inheriting the install-wide value the workspace had just switched off.
     */
    @Test
    void fromMetadata_keepsAZeroCountBecauseThatIsHowALimitIsSwitchedOff() {
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.max-input-tokens", 0)).maxInputTokens()).isZero();
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.max-messages-per-request", 0)).maxMessagesPerRequest()).isZero();
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.max-message-length", 0)).maxMessageLength()).isZero();
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.default-max-response-tokens", 0)).defaultMaxResponseTokens()).isZero();
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("grounding.max-sources", 0)).groundingMaxSources()).isZero();
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("grounding.max-source-length", 0)).groundingMaxSourceLength()).isZero();
    }

    @Test
    void fromMetadata_treatsANegativeCountAsUnset() {
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.max-input-tokens", -5)).maxInputTokens()).isNull();
    }

    /**
     * The projection is how a typed row and the config bundle carry what the map said, so a zero
     * that survives the read has to survive the round trip too. A zero dropped here would put the
     * workspace back under the install-wide limit on a bundle-serving pod.
     */
    @Test
    void aSwitchedOffLimitSurvivesTheRoundTripToMetadata() {
        var settings = GuardrailSettings.fromMetadata("t1", metadata("guardrail.max-input-tokens", 0));

        assertThat(settings.toMetadata()).containsEntry("guardrail.max-input-tokens", 0);
        assertThat(settings.isUnset()).isFalse();
    }

    @Test
    void fromMetadata_treatsAnOutOfRangePercentAsUnset() {
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.context.warning-threshold-pct", 0)).contextWarningThresholdPct()).isNull();
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.context.warning-threshold-pct", 101)).contextWarningThresholdPct()).isNull();
    }

    @Test
    void fromMetadata_trimsTextAndTreatsBlankAsUnset() {
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.action", "  BLOCK  ")).action()).isEqualTo("BLOCK");
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.action", "   ")).action()).isNull();
    }

    /**
     * {@code "yes"} means yes. Under {@code Boolean.parseBoolean} anything that is not {@code "true"}
     * is {@code false}, so a workspace whose map said {@code guardrail.enabled: "yes"} would have
     * guardrails <b>off</b> while believing it had switched them on.
     *
     * <p>Every setting in this package reads YAML 1.1 spellings through {@code SettingsBooleans},
     * which is what an operator writing this map is almost always writing.</p>
     */
    @Test
    void fromMetadata_readsYaml11TruthyAndFalsySpellings() {
        for (Object truthy : new Object[]{true, "true", "TRUE", "t", "yes", "Y", "on", "1", 1}) {
            assertThat(GuardrailSettings.fromMetadata("t1", metadata("guardrail.enabled", truthy)).enabled())
                    .as("%s should enable the guardrail", truthy).isTrue();
        }
        for (Object falsy : new Object[]{false, "false", "f", "no", "N", "off", "0", 0}) {
            assertThat(GuardrailSettings.fromMetadata("t1", metadata("guardrail.enabled", falsy)).enabled())
                    .as("%s should disable it", falsy).isFalse();
        }
    }

    /**
     * An unreadable value inherits rather than disabling. This is the half that matters most for a
     * safety control: a typo must not silently turn the guardrail off.
     */
    @Test
    void fromMetadata_treatsAnUnreadableBooleanAsUnsetRatherThanDisabled() {
        assertThat(GuardrailSettings.fromMetadata("t1", metadata("guardrail.enabled", "maybe")).enabled())
                .isNull();
        assertThat(GuardrailSettings.fromMetadata("t1", metadata("grounding.enabled", "")).groundingEnabled())
                .isNull();
    }

    // --- projecting back -------------------------------------------------------------------------------

    @Test
    void toMetadata_emitsOnlySetFields() {
        GuardrailSettings s = GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.enabled", true, "guardrail.action", "BLOCK"));

        assertThat(s.toMetadata())
                .containsOnlyKeys("guardrail.enabled", "guardrail.action")
                .containsEntry("guardrail.action", "BLOCK");
    }

    /**
     * The round trip is what makes the typed store and the config bundle interchangeable: a bundle
     * carrying the map must produce the same settings, and a settings row written back into a bundle
     * must carry the same keys.
     */
    @Test
    void toMetadata_roundTripsThroughFromMetadata() {
        Map<String, Object> original = metadata(
                "guardrail.enabled", true,
                "guardrail.action", "BLOCK",
                "guardrail.risk-score-threshold", 0.7,
                "guardrail.context.hard-threshold-pct", 90,
                "grounding.max-sources", 50);

        GuardrailSettings once = GuardrailSettings.fromMetadata("t1", original);
        GuardrailSettings twice = GuardrailSettings.fromMetadata("t1", once.toMetadata());

        assertThat(twice).isEqualTo(once);
    }

    /** A value the read path dropped is not resurrected by the round trip. */
    @Test
    void toMetadata_doesNotCarryValuesTheReadPathRejected() {
        GuardrailSettings s = GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.risk-score-threshold", 1.5, "guardrail.action", "BLOCK"));

        assertThat(s.toMetadata()).doesNotContainKey("guardrail.risk-score-threshold");
    }

    // --- the strictness of the write path ------------------------------------------------------------

    @Test
    void parseThreshold_acceptsTheClosedUnitInterval() {
        assertThat(GuardrailSettings.parseThreshold("0")).isEqualTo(0.0);
        assertThat(GuardrailSettings.parseThreshold("1")).isEqualTo(1.0);
        assertThat(GuardrailSettings.parseThreshold(" 0.7 ")).isEqualTo(0.7);
    }

    @Test
    void parseThreshold_treatsBlankAsUnsetRatherThanZero() {
        assertThat(GuardrailSettings.parseThreshold(null)).isNull();
        assertThat(GuardrailSettings.parseThreshold("")).isNull();
        assertThat(GuardrailSettings.parseThreshold("   ")).isNull();
    }

    /**
     * The write path refuses what the read path merely ignores, and says why in the message — an
     * operator setting 1.5 is told it would make the guardrail match nothing, rather
     * than having it silently dropped.
     */
    @Test
    void parseThreshold_refusesValuesOutsideTheUnitInterval() {
        assertThatThrownBy(() -> GuardrailSettings.parseThreshold("1.5"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0 and 1")
                .hasMessageContaining("match nothing, or everything");
        assertThatThrownBy(() -> GuardrailSettings.parseThreshold("-0.1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseThreshold_refusesSomethingThatIsNotANumber() {
        assertThatThrownBy(() -> GuardrailSettings.parseThreshold("high"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a number");
    }

    @Test
    void parseThreshold_refusesNaN() {
        assertThatThrownBy(() -> GuardrailSettings.parseThreshold("NaN"))
                .as("NaN parses as a double but compares false against every bound")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsePercent_acceptsOneThroughHundredAndNamesTheFieldWhenItRefuses() {
        assertThat(GuardrailSettings.parsePercent("1", "Warning threshold")).isEqualTo(1);
        assertThat(GuardrailSettings.parsePercent("100", "Warning threshold")).isEqualTo(100);
        assertThat(GuardrailSettings.parsePercent(null, "Warning threshold")).isNull();

        assertThatThrownBy(() -> GuardrailSettings.parsePercent("0", "Warning threshold"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Warning threshold")
                .hasMessageContaining("between 1 and 100");
        assertThatThrownBy(() -> GuardrailSettings.parsePercent("101", "Hard threshold"))
                .hasMessageContaining("Hard threshold");
        assertThatThrownBy(() -> GuardrailSettings.parsePercent("most", "Warning threshold"))
                .hasMessageContaining("whole percentage");
    }

    /**
     * The two paths disagree on purpose, and this is the assertion that says so: the same value is
     * ignored on a read and refused on a write.
     */
    @Test
    void theReadPathIgnoresWhatTheWritePathRefuses() {
        assertThat(GuardrailSettings.fromMetadata("t1",
                metadata("guardrail.risk-score-threshold", 1.5)).riskScoreThreshold()).isNull();

        assertThatThrownBy(() -> GuardrailSettings.parseThreshold("1.5"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}