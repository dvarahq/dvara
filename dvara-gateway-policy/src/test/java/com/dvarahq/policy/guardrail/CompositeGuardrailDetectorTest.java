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

import com.dvarahq.core.guardrail.GuardrailCategory;
import com.dvarahq.core.guardrail.GuardrailDetection;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositeGuardrailDetectorTest {

    @Test
    void mergesResultsFromMultipleDetectors() {
        GuardrailDetector d1 = mock(GuardrailDetector.class);
        GuardrailDetector d2 = mock(GuardrailDetector.class);

        GuardrailDetection det1 = new GuardrailDetection(
                GuardrailCategory.INJECTION, "inj", "ignore", 0.9, 0.9, "r1");
        GuardrailDetection det2 = new GuardrailDetection(
                GuardrailCategory.PROFANITY, "prof", "bad word", 0.8, 0.8, "r2");

        when(d1.scan(any(), any())).thenReturn(new GuardrailScanResult(List.of(det1), "text"));
        when(d2.scan(any(), any())).thenReturn(new GuardrailScanResult(List.of(det2), "text"));

        CompositeGuardrailDetector composite = new CompositeGuardrailDetector(List.of(d1, d2));
        GuardrailScanResult result = composite.scan("text", "workspace-1");

        assertThat(result.hasDetections()).isTrue();
        assertThat(result.detectionCount()).isEqualTo(2);
    }

    @Test
    void deduplicatesSameMatchAndCategory() {
        GuardrailDetector d1 = mock(GuardrailDetector.class);
        GuardrailDetector d2 = mock(GuardrailDetector.class);

        GuardrailDetection det1 = new GuardrailDetection(
                GuardrailCategory.INJECTION, "inj", "ignore", 0.7, 0.7, "r1");
        GuardrailDetection det2 = new GuardrailDetection(
                GuardrailCategory.INJECTION, "inj", "ignore", 0.9, 0.9, "r1");

        when(d1.scan(any(), any())).thenReturn(new GuardrailScanResult(List.of(det1), "text"));
        when(d2.scan(any(), any())).thenReturn(new GuardrailScanResult(List.of(det2), "text"));

        CompositeGuardrailDetector composite = new CompositeGuardrailDetector(List.of(d1, d2));
        GuardrailScanResult result = composite.scan("text", "workspace-1");

        assertThat(result.detectionCount()).isEqualTo(1);
        // Should keep the one with higher risk score
        assertThat(result.detections().getFirst().riskScore()).isEqualTo(0.9);
    }

    @Test
    void noDetections_returnsEmpty() {
        GuardrailDetector d1 = mock(GuardrailDetector.class);
        when(d1.scan(any(), any())).thenReturn(GuardrailScanResult.EMPTY);

        CompositeGuardrailDetector composite = new CompositeGuardrailDetector(List.of(d1));
        GuardrailScanResult result = composite.scan("hello", "workspace-1");

        assertThat(result.hasDetections()).isFalse();
    }
}