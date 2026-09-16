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
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The readiness indicator, named in {@code management.endpoint.health.group.readiness.include} so
 * the readiness probe reads it. It reports the provider count and the current region.
 */
@Component("gatewayReadiness")
public class GatewayReadinessIndicator implements HealthIndicator {

    private final List<LlmProvider> providers;
    private final RegionContext regionContext;


    public GatewayReadinessIndicator(List<LlmProvider> providers, RegionContext regionContext) {
        this.providers = providers;
        this.regionContext = regionContext;
    }

    @Override
    public Health health() {
        // Always UP. An empty provider list does not mean "not ready": a BYOK deployment registers
        // no static providers and resolves credentials per workspace at request time. Reporting
        // DOWN would keep the pod out of the Service even though it can serve. A workspace whose
        // credentials do not resolve gets a per-request NO_PROVIDER error from ProviderDispatcher.
        // A module that serves configuration from a bundle registers its own readiness indicator
        // for the case where no bundle is held, so this one only reports providers and region.
        Health.Builder builder = Health.up()
                .withDetail("providerCount", providers.size())
                .withDetail("mode", providers.isEmpty() ? "byok" : "mixed");
        regionContext.currentRegion().ifPresent(r -> builder.withDetail("region", r));
        return builder.build();
    }
}