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

class PriorityAdmissionResultTest {

    @Test
    void admitted_isAdmitted() {
        PriorityAdmissionResult result = PriorityAdmissionResult.admitted(PriorityTier.PREMIUM);

        assertThat(result.admitted()).isTrue();
        assertThat(result.tier()).isEqualTo(PriorityTier.PREMIUM);
        assertThat(result.reason()).isNull();
    }

    @Test
    void rejected_hasReason() {
        PriorityAdmissionResult result = PriorityAdmissionResult.rejected(
                PriorityTier.BULK, "Load at 90%, bulk threshold is 50%");

        assertThat(result.admitted()).isFalse();
        assertThat(result.tier()).isEqualTo(PriorityTier.BULK);
        assertThat(result.reason()).contains("90%");
    }
}