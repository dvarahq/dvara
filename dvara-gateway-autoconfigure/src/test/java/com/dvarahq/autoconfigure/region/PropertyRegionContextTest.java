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

import com.dvarahq.autoconfigure.GatewayRegionProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyRegionContextTest {

    @Test
    void returnsEmptyWhenRegionIdNull() {
        GatewayRegionProperties props = new GatewayRegionProperties();
        props.setId(null);

        PropertyRegionContext context = new PropertyRegionContext(props);

        assertThat(context.currentRegion()).isEmpty();
        assertThat(context.isRegionAware()).isFalse();
    }

    @Test
    void returnsEmptyWhenRegionIdBlank() {
        GatewayRegionProperties props = new GatewayRegionProperties();
        props.setId("   ");

        PropertyRegionContext context = new PropertyRegionContext(props);

        assertThat(context.currentRegion()).isEmpty();
        assertThat(context.isRegionAware()).isFalse();
    }

    @Test
    void returnsPresentWhenRegionIdSet() {
        GatewayRegionProperties props = new GatewayRegionProperties();
        props.setId("us-east-1");

        PropertyRegionContext context = new PropertyRegionContext(props);

        assertThat(context.currentRegion()).contains("us-east-1");
        assertThat(context.isRegionAware()).isTrue();
    }

    @Test
    void returnsEmptyWhenRegionIdEmpty() {
        GatewayRegionProperties props = new GatewayRegionProperties();
        props.setId("");

        PropertyRegionContext context = new PropertyRegionContext(props);

        assertThat(context.currentRegion()).isEmpty();
        assertThat(context.isRegionAware()).isFalse();
    }
}