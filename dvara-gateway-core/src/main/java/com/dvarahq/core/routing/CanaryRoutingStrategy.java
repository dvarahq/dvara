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
import java.util.concurrent.ThreadLocalRandom;

public class CanaryRoutingStrategy implements RoutingStrategy {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CanaryRoutingStrategy.class);

    private final CanaryConfig canaryConfig;

    /**
     * The split actually used: the configured one when it is a percentage, and <b>zero</b> when it
     * is not.
     *
     * <p>The roll below is {@code 0..99}, so a stored 500 would make {@code roll < splitPct} always
     * true and send every request to the candidate, the opposite of what a canary is for. Zero
     * rather than a clamp to 100, because a value this cannot honour should not be guessed at in
     * the direction of more exposure. The write paths refuse such a value, so reaching this means a
     * config written by a path that does not validate. It is logged once per route rather than per
     * request.</p>
     */
    private final int splitPct;

    public CanaryRoutingStrategy(CanaryConfig canaryConfig) {
        this.canaryConfig = canaryConfig;
        int configured = canaryConfig.getSplitPct();
        if (configured < 0 || configured > 100) {
            log.warn("Canary split {}% for test '{}' is not a percentage; sending NO traffic to the "
                    + "candidate '{}' until it is corrected. A canary bounds exposure to an unproven "
                    + "provider, so a value that cannot be honoured is read as zero rather than as all.",
                    configured, canaryConfig.getTestName(), canaryConfig.getCandidateProvider());
            this.splitPct = 0;
        } else {
            this.splitPct = configured;
        }
    }

    @Override
    public LlmProvider route(ChatRequest request, List<LlmProvider> providers) {
        String selectedProvider = selectVariant(request);
        String fallbackProvider = selectedProvider.equals(canaryConfig.getBaselineProvider())
                ? canaryConfig.getCandidateProvider()
                : canaryConfig.getBaselineProvider();

        return findProvider(providers, selectedProvider)
                .or(() -> findProvider(providers, fallbackProvider))
                .orElseThrow(() -> new GatewayException("NO_PROVIDER",
                        "No provider found for canary routing. Baseline: "
                                + canaryConfig.getBaselineProvider() + ", Candidate: "
                                + canaryConfig.getCandidateProvider()));
    }

    private String selectVariant(ChatRequest request) {
        if (canaryConfig.getWorkspaceScope() != null && !canaryConfig.getWorkspaceScope().isBlank()) {
            Object workspaceIdObj = request.getMetadata() != null
                    ? request.getMetadata().get("workspace_id")
                    : null;
            String workspaceId = workspaceIdObj != null ? workspaceIdObj.toString() : null;
            if (workspaceId == null || !workspaceId.equals(canaryConfig.getWorkspaceScope())) {
                return canaryConfig.getBaselineProvider();
            }
        }

        int roll = ThreadLocalRandom.current().nextInt(100);
        return roll < splitPct
                ? canaryConfig.getCandidateProvider()
                : canaryConfig.getBaselineProvider();
    }

    private java.util.Optional<LlmProvider> findProvider(List<LlmProvider> providers, String name) {
        return providers.stream()
                .filter(p -> p.name().equals(name))
                .findFirst();
    }
}