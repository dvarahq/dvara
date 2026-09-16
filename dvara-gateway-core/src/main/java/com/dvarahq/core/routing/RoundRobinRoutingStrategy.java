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
package com.dvarahq.core.routing;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.RoutingStrategy;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class RoundRobinRoutingStrategy implements RoutingStrategy {

    private final AtomicLong counter = new AtomicLong(0);
    private final List<String> providerNames;

    public RoundRobinRoutingStrategy(List<String> providerNames) {
        if (providerNames == null || providerNames.isEmpty()) {
            throw new IllegalArgumentException("Round-robin requires at least one provider");
        }
        this.providerNames = List.copyOf(providerNames);
    }

    @Override
    public LlmProvider route(ChatRequest request, List<LlmProvider> providers) {
        int size = providerNames.size();
        long index = counter.getAndIncrement();

        for (int i = 0; i < size; i++) {
            int slot = (int) ((index + i) % size);
            String targetName = providerNames.get(slot);
            LlmProvider match = findProvider(targetName, providers);
            if (match != null) {
                return match;
            }
        }

        throw new GatewayException("NO_PROVIDER",
                "No available provider in round-robin pool for model: " + request.getModel());
    }

    private LlmProvider findProvider(String name, List<LlmProvider> providers) {
        for (LlmProvider p : providers) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        return null;
    }
}