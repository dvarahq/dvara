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
package com.dvarahq.core.pii;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@code REDACT} becomes when a token cannot be minted (D3 iv).
 *
 * <p>Small, and worth having: every case here is a way an unreadable setting could end up disclosing
 * personal data, and the default has to hold for all of them.
 */
class PiiDegradedActionTest {

    @Test
    void theDefaultIsBlockBecauseDisclosureIsTheIrreversibleDirection() {
        // A budget cap may overshoot and a revocation may be late, because both reconcile.
        // Sending raw PII to a provider does not: no recall, and the workspace cannot even see it
        // happen, because the control plane being down is why it happened.
        assertThat(PiiDegradedAction.fromMetadata(null)).isEqualTo(PiiDegradedAction.BLOCK);
    }

    @Test
    void logIsAvailableButOnlyBySayingSo() {
        assertThat(PiiDegradedAction.fromMetadata("LOG")).isEqualTo(PiiDegradedAction.LOG);
        assertThat(PiiDegradedAction.fromMetadata("log")).isEqualTo(PiiDegradedAction.LOG);
        assertThat(PiiDegradedAction.fromMetadata("  LOG  ")).isEqualTo(PiiDegradedAction.LOG);
    }

    @Test
    void anythingUnreadableMeansBlock() {
        // A typo, a stale value, a boolean someone expected to work — none of them may open the
        // door. Failing towards disclosure on an unparseable setting is the one outcome that cannot
        // be walked back.
        assertThat(PiiDegradedAction.fromMetadata("")).isEqualTo(PiiDegradedAction.BLOCK);
        assertThat(PiiDegradedAction.fromMetadata("lgo")).isEqualTo(PiiDegradedAction.BLOCK);
        assertThat(PiiDegradedAction.fromMetadata("true")).isEqualTo(PiiDegradedAction.BLOCK);
        assertThat(PiiDegradedAction.fromMetadata(Boolean.TRUE)).isEqualTo(PiiDegradedAction.BLOCK);
        assertThat(PiiDegradedAction.fromMetadata(1)).isEqualTo(PiiDegradedAction.BLOCK);
        assertThat(PiiDegradedAction.fromMetadata("REDACT")).isEqualTo(PiiDegradedAction.BLOCK);
    }
}