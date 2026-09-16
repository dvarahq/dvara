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
package com.dvarahq.core.region;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RegionContextTest {

    @Test
    void isRegionAware_returnsTrueWhenRegionPresent() {
        RegionContext context = () -> Optional.of("us-east-1");

        assertThat(context.isRegionAware()).isTrue();
    }

    @Test
    void isRegionAware_returnsFalseWhenRegionEmpty() {
        RegionContext context = () -> Optional.empty();

        assertThat(context.isRegionAware()).isFalse();
    }

    @Test
    void currentRegion_returnsConfiguredRegion() {
        RegionContext context = () -> Optional.of("eu-west-1");

        assertThat(context.currentRegion()).contains("eu-west-1");
    }
}