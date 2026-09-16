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
package com.dvarahq.autoconfigure.cache;

import com.dvarahq.core.cache.ResponseCache;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Registers {@link InMemoryResponseCache} when an operator asks for it.
 *
 * <p>Off unless asked for: a per-process cache changes what a second identical request returns,
 * and a fleet of replicas gets a different hit rate from a single node, so it is a posture an
 * operator chooses.
 *
 * <p>{@code @ConditionalOnMissingBean} because {@code ResponseCache} is an extension point: an
 * application's own cache, or a fleet-shared cache registered by another module, must win. Those
 * are conditional too, so a bean registered here first would stand them down instead, and only
 * the hit rate would say so.
 *
 * <p>{@link AutoConfigureOrder} at lowest precedence makes that ordering a fact rather than a
 * hope: conditions are evaluated in auto-configuration order, so running last is how this one sees
 * what everything else registered.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(name = "dvara.llm-gateway.cache.in-memory.enabled", havingValue = "true")
public class InMemoryResponseCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ResponseCache.class)
    public ResponseCache inMemoryResponseCache(
            @Value("${dvara.llm-gateway.cache.in-memory.max-entries:1000}") int maxEntries,
            @Value("${dvara.llm-gateway.cache.in-memory.ttl-seconds:300}") int ttlSeconds) {
        return new InMemoryResponseCache(maxEntries, ttlSeconds);
    }
}
