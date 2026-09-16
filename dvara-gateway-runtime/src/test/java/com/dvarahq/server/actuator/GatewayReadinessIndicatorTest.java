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
package com.dvarahq.server.actuator;

import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.region.RegionContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

class GatewayReadinessIndicatorTest {

    private static final RegionContext NO_REGION = () -> Optional.empty();

    @Test
    void returnsUpInByokModeWhenNoStaticProvidersRegistered() {
        // A BYOK deployment registers no static providers on purpose: workspace credentials
        // are resolved per request. An empty static-provider list must not keep the pod
        // out of the Service.
        GatewayReadinessIndicator indicator = new GatewayReadinessIndicator(List.of(), NO_REGION);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(0, health.getDetails().get("providerCount"));
        assertEquals("byok", health.getDetails().get("mode"));
    }

    @Test
    void returnsUpWithProviderCount() {
        LlmProvider provider1 = mock(LlmProvider.class);
        LlmProvider provider2 = mock(LlmProvider.class);
        GatewayReadinessIndicator indicator = new GatewayReadinessIndicator(List.of(provider1, provider2), NO_REGION);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(2, health.getDetails().get("providerCount"));
        assertEquals("mixed", health.getDetails().get("mode"));
    }

    @Test
    void returnsUpWithSingleProvider() {
        LlmProvider provider = mock(LlmProvider.class);
        GatewayReadinessIndicator indicator = new GatewayReadinessIndicator(List.of(provider), NO_REGION);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(1, health.getDetails().get("providerCount"));
    }

    @Test
    void includesRegionDetailWhenConfigured() {
        LlmProvider provider = mock(LlmProvider.class);
        RegionContext withRegion = () -> Optional.of("us-east-1");
        GatewayReadinessIndicator indicator = new GatewayReadinessIndicator(List.of(provider), withRegion);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("us-east-1", health.getDetails().get("region"));
    }

    @Test
    void omitsRegionDetailWhenNotConfigured() {
        LlmProvider provider = mock(LlmProvider.class);
        GatewayReadinessIndicator indicator = new GatewayReadinessIndicator(List.of(provider), NO_REGION);

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertNull(health.getDetails().get("region"));
    }
}
