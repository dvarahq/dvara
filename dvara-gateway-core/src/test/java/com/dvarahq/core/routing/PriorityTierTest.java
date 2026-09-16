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
package com.dvarahq.core.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityTierTest {

    @Test
    void fromString_premium_returnsPremium() {
        assertThat(PriorityTier.fromString("premium")).isEqualTo(PriorityTier.PREMIUM);
    }

    @Test
    void fromString_upperCase_returnsCorrectTier() {
        assertThat(PriorityTier.fromString("STANDARD")).isEqualTo(PriorityTier.STANDARD);
    }

    @Test
    void fromString_mixedCase_returnsCorrectTier() {
        assertThat(PriorityTier.fromString("Bulk")).isEqualTo(PriorityTier.BULK);
    }

    @Test
    void fromString_null_returnsStandard() {
        assertThat(PriorityTier.fromString(null)).isEqualTo(PriorityTier.STANDARD);
    }

    @Test
    void fromString_blank_returnsStandard() {
        assertThat(PriorityTier.fromString("  ")).isEqualTo(PriorityTier.STANDARD);
    }

    @Test
    void fromString_unknown_returnsStandard() {
        assertThat(PriorityTier.fromString("unknown")).isEqualTo(PriorityTier.STANDARD);
    }

    @Test
    void defaultThresholdPct_premium_is100() {
        assertThat(PriorityTier.PREMIUM.defaultThresholdPct()).isEqualTo(100);
    }

    @Test
    void defaultThresholdPct_standard_is80() {
        assertThat(PriorityTier.STANDARD.defaultThresholdPct()).isEqualTo(80);
    }

    @Test
    void defaultThresholdPct_bulk_is50() {
        assertThat(PriorityTier.BULK.defaultThresholdPct()).isEqualTo(50);
    }
}