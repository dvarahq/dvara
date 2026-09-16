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

import static org.assertj.core.api.Assertions.assertThat;

class ContextWindowResultTest {

    @Test
    void withinLimits_noThresholdBreaches() {
        ContextWindowResult result = ContextWindowResult.withinLimits(5000, 128000);

        assertThat(result.estimatedTokens()).isEqualTo(5000);
        assertThat(result.maxTokens()).isEqualTo(128000);
        assertThat(result.warningThresholdBreached()).isFalse();
        assertThat(result.hardThresholdBreached()).isFalse();
        assertThat(result.prunedRequest()).isNull();
    }

    @Test
    void withinLimits_utilizationCalculation() {
        ContextWindowResult result = ContextWindowResult.withinLimits(64000, 128000);

        assertThat(result.utilizationPct()).isEqualTo(50);
    }

    @Test
    void withinLimits_zeroTokens() {
        ContextWindowResult result = ContextWindowResult.withinLimits(0, 128000);

        assertThat(result.utilizationPct()).isEqualTo(0);
        assertThat(result.warningThresholdBreached()).isFalse();
    }
}