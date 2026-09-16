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
package com.dvarahq.autoconfigure;

import com.dvarahq.autoconfigure.region.PassthroughDataResidencyPolicy;
import com.dvarahq.autoconfigure.region.PropertyRegionContext;
import com.dvarahq.autoconfigure.region.SingleRegionHealthRegistry;
import com.dvarahq.core.region.DataResidencyPolicy;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.region.RegionHealthRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(GatewayRegionProperties.class)
/**
 * Region defaults: the configured region, a pass-through residency policy and a single-region
 * health registry.
 *
 * <p>All three beans are {@code @ConditionalOnMissingBean} on their interfaces, so an application
 * or another module registering its own gets a replacement rather than an ambiguous context.
 */
public class RegionAutoConfiguration {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(RegionContext.class)
    public RegionContext regionContext(GatewayRegionProperties properties,
                                       org.springframework.core.env.Environment environment) {
        // Resolved through RegionId rather than read from the bound property alone, so an install
        // on the legacy dvara.llm-gateway.region.id key also gets a populated RegionContext.
        String resolved = com.dvarahq.autoconfigure.region.RegionId.resolve(environment);
        return new PropertyRegionContext(resolved != null ? resolved : properties.getId());
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(DataResidencyPolicy.class)
    public DataResidencyPolicy dataResidencyPolicy() {
        return new PassthroughDataResidencyPolicy();
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(RegionHealthRegistry.class)
    public RegionHealthRegistry regionHealthRegistry(RegionContext regionContext) {
        return new SingleRegionHealthRegistry(regionContext);
    }
}