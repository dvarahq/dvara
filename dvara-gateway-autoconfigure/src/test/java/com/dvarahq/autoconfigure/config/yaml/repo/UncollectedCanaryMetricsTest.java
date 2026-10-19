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
package com.dvarahq.autoconfigure.config.yaml.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A canary route in a build that records no canary comparison says so, once per route: the split works, but the
 * comparison a canary is usually run for is not kept.
 */
class UncollectedCanaryMetricsTest {

    @Test
    void eachCanaryRouteIsReportedOnce_andNothingIsKept() {
        UncollectedRoutingMetrics.Canary canary = new UncollectedRoutingMetrics.Canary();

        canary.record("route-a", "primary", 10, 0.01, false);
        canary.record("route-a", "variant", 12, 0.02, false);
        canary.record("route-b", "primary", 9, 0.01, true);

        assertThat(canary.hasReported("route-a")).isTrue();
        assertThat(canary.hasReported("route-b")).isTrue();
        assertThat(canary.hasReported("route-c")).isFalse();
    }
}
