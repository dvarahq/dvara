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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceRateLimitsTest {

    private static Map<String, Object> metadata(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void noMetadataMeansInheritBoth() {
        assertThat(WorkspaceRateLimits.fromMetadata("t1", null).isUnset()).isTrue();
        assertThat(WorkspaceRateLimits.fromMetadata("t1", Map.of()).isUnset()).isTrue();
    }

    @Test
    void bothCeilingsAreRead() {
        var limits = WorkspaceRateLimits.fromMetadata("t1",
                metadata("rate-limit.requests-per-minute", 250,
                        "rate-limit.tokens-per-minute", "50000"));

        assertThat(limits.requestsPerMinute()).isEqualTo(250);
        assertThat(limits.tokensPerMinute()).isEqualTo(50000);
    }

    @Test
    void anUnreadableOrNonPositiveValueInherits_ratherThanMeaningNoLimit() {
        // The value comes from a store that validated nothing. Reading "lots" as zero would
        // black-hole the workspace; reading it as unlimited would remove a ceiling somebody set.
        // Inheriting the installation's limit is the only answer that does neither.
        var limits = WorkspaceRateLimits.fromMetadata("t1",
                metadata("rate-limit.requests-per-minute", "lots",
                        "rate-limit.tokens-per-minute", 0));

        assertThat(limits.isUnset()).isTrue();
    }

    @Test
    void aNegativeValueAlsoInherits() {
        assertThat(WorkspaceRateLimits.fromMetadata("t1",
                metadata("rate-limit.requests-per-minute", -5)).isUnset()).isTrue();
    }

    @Test
    void toMetadataEmitsOnlySetFields_andRoundTrips() {
        var once = new WorkspaceRateLimits("t1", 100, null);

        assertThat(once.toMetadata()).doesNotContainKey("rate-limit.tokens-per-minute");
        assertThat(WorkspaceRateLimits.fromMetadata("t1", once.toMetadata())).isEqualTo(once);
    }

    @Test
    void toMetadataDoesNotCarryAValueTheReadPathRejected() {
        // Round-tripping a rejected value would reintroduce it as though it had been accepted.
        var limits = WorkspaceRateLimits.fromMetadata("t1",
                metadata("rate-limit.requests-per-minute", "nonsense"));

        assertThat(limits.toMetadata()).doesNotContainKey("rate-limit.requests-per-minute");
    }

    @Test
    void itCarriesNothingThisBuildCannotActOn() {
        // The record carries exactly the two ceilings the rate limiter reads, so a workspace cannot
        // set a field nothing acts on.
        assertThat(WorkspaceRateLimits.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("workspaceId", "requestsPerMinute", "tokensPerMinute");
    }
}
