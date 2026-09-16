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
package com.dvarahq.core.guardrail;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailScanResultTest {

    @Test
    void empty_hasNoDetections() {
        assertThat(GuardrailScanResult.EMPTY.hasDetections()).isFalse();
        assertThat(GuardrailScanResult.EMPTY.detectionCount()).isZero();
        assertThat(GuardrailScanResult.EMPTY.detections()).isEmpty();
    }

    @Test
    void withDetections_reportsCorrectly() {
        GuardrailDetection detection = new GuardrailDetection(
                GuardrailCategory.INJECTION, "test-label", "matched text",
                0.9, 0.95, "rule-1");

        GuardrailScanResult result = new GuardrailScanResult(
                List.of(detection), "source text");

        assertThat(result.hasDetections()).isTrue();
        assertThat(result.detectionCount()).isEqualTo(1);
        assertThat(result.detections()).containsExactly(detection);
        assertThat(result.sourceText()).isEqualTo("source text");
    }

    @Test
    void multipleDetections_countCorrectly() {
        GuardrailDetection d1 = new GuardrailDetection(
                GuardrailCategory.INJECTION, "inj", "ignore", 0.9, 0.9, "r1");
        GuardrailDetection d2 = new GuardrailDetection(
                GuardrailCategory.PROFANITY, "prof", "bad", 0.8, 0.8, "r2");

        GuardrailScanResult result = new GuardrailScanResult(List.of(d1, d2), "text");

        assertThat(result.detectionCount()).isEqualTo(2);
    }

    @Test
    void detection_recordFieldsAccessible() {
        GuardrailDetection detection = new GuardrailDetection(
                GuardrailCategory.JAILBREAK, "jailbreak-attempt", "do anything now",
                0.95, 0.88, "rule-jb-1");

        assertThat(detection.category()).isEqualTo(GuardrailCategory.JAILBREAK);
        assertThat(detection.label()).isEqualTo("jailbreak-attempt");
        assertThat(detection.matchedText()).isEqualTo("do anything now");
        assertThat(detection.riskScore()).isEqualTo(0.95);
        assertThat(detection.confidence()).isEqualTo(0.88);
        assertThat(detection.ruleId()).isEqualTo("rule-jb-1");
    }

    @Test
    void contextWindowResult_withinLimits() {
        ContextWindowResult result = ContextWindowResult.withinLimits(500, 128000);

        assertThat(result.estimatedTokens()).isEqualTo(500);
        assertThat(result.maxTokens()).isEqualTo(128000);
        assertThat(result.utilizationPct()).isZero();
        assertThat(result.warningThresholdBreached()).isFalse();
        assertThat(result.hardThresholdBreached()).isFalse();
        assertThat(result.prunedRequest()).isNull();
    }

    @Test
    void contextWindowResult_calculatesPercentage() {
        ContextWindowResult result = ContextWindowResult.withinLimits(64000, 128000);
        assertThat(result.utilizationPct()).isEqualTo(50);
    }
}