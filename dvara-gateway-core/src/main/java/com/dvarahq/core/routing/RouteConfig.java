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

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class RouteConfig {

    private String id;
    private String modelPattern;
    private Strategy strategy;
    private List<ProviderWeight> providers;
    private String pinnedModelVersion;
    private int costTolerancePct;
    private long latencySlaMs;
    private CanaryConfig canaryConfig;
    private ShadowConfig shadowConfig;
    private Map<String, String> modelTiers;
    /**
     * Where this route's requests go when the provider serving them fails (#7): an ordered chain of
     * provider and model, tried in order, and nothing else. Empty: the route does not fail over.
     */
    @Builder.Default
    private List<FallbackTarget> fallbacks = List.of();

    /** Most targets a chain may name: each costs a full upstream attempt on one request. */
    public static final int MAX_FALLBACKS = 5;

    /**
     * One fallback: a provider by name and the model to ask it for. A null model sends the request's own
     * model, for a provider serving the same one (OpenAI and Azure OpenAI); a cross-family backup
     * (GPT to Claude) names its model, since the gateway never guesses one (#7 REQ-CPF-01).
     */
    public record FallbackTarget(String provider, String model) {
        public String describe() {
            return provider + (model == null ? "" : "/" + model);
        }
    }

    /**
     * Why this route's fallback chain can't be served, or null (#7 AC-CPF-03): a target without a provider,
     * more than {@link #MAX_FALLBACKS}, or the same target twice. Checked when routes are loaded, and a
     * table with one bad chain is refused whole, so no partial chain becomes active.
     */
    /**
     * Why this route's pinned model cannot be used, or null when it has none or it is usable. A pin is sent to the
     * provider as the model, so a blank one, or one with spaces in it, is refused when the routes are loaded rather
     * than sent: the whole table is refused, so a bad refresh keeps the routes already in force.
     */
    public String invalidPin() {
        if (pinnedModelVersion == null) {
            return null;
        }
        if (pinnedModelVersion.isBlank()) {
            return "route '" + id + "' has a blank pinned-model-version";
        }
        if (pinnedModelVersion.chars().anyMatch(Character::isWhitespace)) {
            return "route '" + id + "' has a pinned-model-version with spaces in it: '" + pinnedModelVersion + "'";
        }
        return null;
    }

    public String invalidFallbacks() {
        if (fallbacks == null || fallbacks.isEmpty()) {
            return null;
        }
        if (fallbacks.size() > MAX_FALLBACKS) {
            return "route '" + id + "' names " + fallbacks.size() + " fallbacks; at most " + MAX_FALLBACKS;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (FallbackTarget t : fallbacks) {
            if (t == null || t.provider() == null || t.provider().isBlank()) {
                return "route '" + id + "' has a fallback with no provider";
            }
            if (t.model() != null && t.model().isBlank()) {
                return "route '" + id + "' has a fallback to " + t.provider() + " with a blank model";
            }
            if (!seen.add(t.describe())) {
                return "route '" + id + "' names the fallback " + t.describe() + " twice";
            }
        }
        return null;
    }

    public enum Strategy {
        MODEL_PREFIX,
        ROUND_ROBIN,
        WEIGHTED,
        LATENCY_AWARE,
        COST_AWARE,
        CANARY,
        GEO_AWARE,
        INTELLIGENT
    }

    @Data
    @Builder
    public static class ProviderWeight {
        private String provider;
        private int weight;
        private String region;
    }
}