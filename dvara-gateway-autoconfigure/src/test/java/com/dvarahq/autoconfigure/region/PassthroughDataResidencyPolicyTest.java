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
package com.dvarahq.autoconfigure.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PassthroughDataResidencyPolicyTest {

    private final PassthroughDataResidencyPolicy policy = new PassthroughDataResidencyPolicy();

    @Test
    void allowedRegions_returnsAllCandidates() {
        List<String> candidates = List.of("us-east-1", "eu-west-1", "ap-south-1");

        List<String> result = policy.allowedRegions("us-east-1", candidates);

        assertThat(result).containsExactlyElementsOf(candidates);
    }

    @Test
    void allowedRegions_returnsEmptyWhenNoCandidates() {
        List<String> result = policy.allowedRegions("us-east-1", List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void isAllowed_alwaysReturnsTrue() {
        assertThat(policy.isAllowed("us-east-1", "eu-west-1")).isTrue();
        assertThat(policy.isAllowed(null, "us-east-1")).isTrue();
        assertThat(policy.isAllowed("us-east-1", null)).isTrue();
    }
}