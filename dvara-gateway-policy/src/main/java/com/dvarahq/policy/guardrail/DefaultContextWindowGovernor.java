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
package com.dvarahq.policy.guardrail;

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.guardrail.ContextWindowGovernor;
import com.dvarahq.core.guardrail.ContextWindowResult;
import com.dvarahq.core.guardrail.PruningStrategy;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.id.Ids;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Context window governor with token estimation, threshold checks,
 * and configurable pruning strategies.
 */
public class DefaultContextWindowGovernor implements ContextWindowGovernor {

    private static final Logger log = LoggerFactory.getLogger(DefaultContextWindowGovernor.class);

    private static final int DEFAULT_WARNING_THRESHOLD_PCT = 70;
    private static final int DEFAULT_HARD_THRESHOLD_PCT = 90;

    private final TokenEstimator tokenEstimator;
    private final AuditWriter auditWriter;
    private final WorkspaceRepository workspaceRepository;

    public DefaultContextWindowGovernor(TokenEstimator tokenEstimator,
                                           AuditWriter auditWriter,
                                           WorkspaceRepository workspaceRepository) {
        this.tokenEstimator = tokenEstimator;
        this.auditWriter = auditWriter;
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public ContextWindowResult evaluate(ChatRequest request, int maxContextTokens, String workspaceId) {
        if (maxContextTokens <= 0) {
            return ContextWindowResult.withinLimits(0, maxContextTokens);
        }

        int estimatedTokens = tokenEstimator.estimateTokens(request);
        int utilizationPct = (int) ((long) estimatedTokens * 100 / maxContextTokens);

        // Resolve workspace config
        int warningThreshold = DEFAULT_WARNING_THRESHOLD_PCT;
        int hardThreshold = DEFAULT_HARD_THRESHOLD_PCT;
        PruningStrategy pruningStrategy = PruningStrategy.NONE;

        if (workspaceId != null) {
            Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
            if (workspace != null && workspace.getMetadata() != null) {
                Map<String, Object> meta = workspace.getMetadata();
                warningThreshold = parseIntOrDefault(
                        meta.get("guardrail.context.warning-threshold-pct"),
                        DEFAULT_WARNING_THRESHOLD_PCT,
                        "guardrail.context.warning-threshold-pct", workspaceId);
                hardThreshold = parseIntOrDefault(
                        meta.get("guardrail.context.hard-threshold-pct"),
                        DEFAULT_HARD_THRESHOLD_PCT,
                        "guardrail.context.hard-threshold-pct", workspaceId);
                Object strategyObj = meta.get("guardrail.context.pruning-strategy");
                String strategyStr = strategyObj != null ? strategyObj.toString() : null;
                if (strategyStr != null && !strategyStr.isBlank()) {
                    try {
                        pruningStrategy = PruningStrategy.valueOf(strategyStr.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        // A workspace that wrote TRUNCATE-OLDEST for TRUNCATE_OLDEST gets NONE,
                        // and NONE means an over-long request is refused with
                        // CONTEXT_WINDOW_EXCEEDED rather than pruned, so the typo has to be said.
                        log.warn("Invalid guardrail.context.pruning-strategy '{}' for workspace {}, "
                                + "using {}", strategyStr, workspaceId, pruningStrategy);
                    }
                }
            }
        }

        boolean warningBreached = utilizationPct >= warningThreshold;
        boolean hardBreached = utilizationPct >= hardThreshold;

        if (warningBreached && !hardBreached) {
            writeAuditEvent(workspaceId, "CONTEXT_WINDOW_WARNING", estimatedTokens, maxContextTokens,
                    utilizationPct, null);
            return new ContextWindowResult(estimatedTokens, maxContextTokens, utilizationPct,
                    true, false, null);
        }

        if (hardBreached) {
            if (pruningStrategy == PruningStrategy.NONE) {
                writeAuditEvent(workspaceId, "CONTEXT_WINDOW_EXCEEDED", estimatedTokens, maxContextTokens,
                        utilizationPct, "NONE");
                return new ContextWindowResult(estimatedTokens, maxContextTokens, utilizationPct,
                        true, true, null);
            }

            // Apply pruning
            ChatRequest prunedRequest = applyPruning(request, maxContextTokens, hardThreshold, pruningStrategy);
            int prunedTokens = tokenEstimator.estimateTokens(prunedRequest);
            int prunedPct = (int) ((long) prunedTokens * 100 / maxContextTokens);

            writeAuditEvent(workspaceId, "CONTEXT_WINDOW_PRUNED", estimatedTokens, maxContextTokens,
                    utilizationPct, pruningStrategy.name());

            log.info("Context window pruned: {} -> {} tokens ({}% -> {}%), strategy={}",
                    estimatedTokens, prunedTokens, utilizationPct, prunedPct, pruningStrategy);

            return new ContextWindowResult(prunedTokens, maxContextTokens, prunedPct,
                    true, true, prunedRequest);
        }

        return ContextWindowResult.withinLimits(estimatedTokens, maxContextTokens);
    }

    private ChatRequest applyPruning(ChatRequest request, int maxContextTokens,
                                      int thresholdPct, PruningStrategy strategy) {
        int targetTokens = (int) ((long) maxContextTokens * thresholdPct / 100);
        List<MultimodalMessage> messages = new ArrayList<>(request.getMessages());

        return switch (strategy) {
            case TRUNCATE_OLDEST -> truncateOldest(request, messages, targetTokens);
            case TRUNCATE_MIDDLE -> truncateMiddle(request, messages, targetTokens);
            default -> request;
        };
    }

    /**
     * Removes oldest non-system messages until under target token count.
     */
    private ChatRequest truncateOldest(ChatRequest request, List<MultimodalMessage> messages,
                                        int targetTokens) {
        List<MultimodalMessage> pruned = new ArrayList<>();
        List<MultimodalMessage> nonSystem = new ArrayList<>();

        for (MultimodalMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                pruned.add(msg);
            } else {
                nonSystem.add(msg);
            }
        }

        // Add non-system messages from newest to oldest until we exceed target
        List<MultimodalMessage> kept = new ArrayList<>();
        for (int i = nonSystem.size() - 1; i >= 0; i--) {
            kept.addFirst(nonSystem.get(i));
            List<MultimodalMessage> candidate = new ArrayList<>(pruned);
            candidate.addAll(kept);
            ChatRequest testReq = rebuildRequest(request, candidate);
            if (tokenEstimator.estimateTokens(testReq) > targetTokens && kept.size() > 1) {
                kept.removeFirst();
                break;
            }
        }

        pruned.addAll(kept);
        return rebuildRequest(request, pruned);
    }

    /**
     * Keeps system messages + first user message + most recent messages,
     * removes middle messages.
     */
    private ChatRequest truncateMiddle(ChatRequest request, List<MultimodalMessage> messages,
                                        int targetTokens) {
        List<MultimodalMessage> system = new ArrayList<>();
        List<MultimodalMessage> nonSystem = new ArrayList<>();

        for (MultimodalMessage msg : messages) {
            if ("system".equals(msg.getRole())) {
                system.add(msg);
            } else {
                nonSystem.add(msg);
            }
        }

        if (nonSystem.size() <= 2) {
            return request; // Can't meaningfully prune
        }

        // Keep first and progressively add from the end
        MultimodalMessage first = nonSystem.getFirst();
        List<MultimodalMessage> tail = new ArrayList<>();

        for (int i = nonSystem.size() - 1; i >= 1; i--) {
            tail.addFirst(nonSystem.get(i));
            List<MultimodalMessage> candidate = new ArrayList<>(system);
            candidate.add(first);
            candidate.addAll(tail);
            ChatRequest testReq = rebuildRequest(request, candidate);
            if (tokenEstimator.estimateTokens(testReq) > targetTokens && tail.size() > 1) {
                tail.removeFirst();
                break;
            }
        }

        List<MultimodalMessage> pruned = new ArrayList<>(system);
        pruned.add(first);
        pruned.addAll(tail);
        return rebuildRequest(request, pruned);
    }

    private ChatRequest rebuildRequest(ChatRequest original, List<MultimodalMessage> messages) {
        // toBuilder(), never a hand-rolled copy that could drop fields such as topP, tools and
        // toolChoice.
        return original.toBuilder().messages(messages).build();
    }

    private void writeAuditEvent(String workspaceId, String eventType, int estimatedTokens,
                                  int maxTokens, int utilizationPct, String strategy) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workspace_id", workspaceId);
        payload.put("estimated_tokens", estimatedTokens);
        payload.put("max_tokens", maxTokens);
        payload.put("utilization_pct", utilizationPct);
        if (strategy != null) {
            payload.put("pruning_strategy", strategy);
        }

        auditWriter.write(new AuditEvent(
                Ids.newId(),
                Instant.now(),
                workspaceId,
                eventType,
                payload));
    }

    /** Names the key, so an unusable value is visible rather than merely absorbed. */
    private static int parseIntOrDefault(Object value, int defaultValue, String key, String workspaceId) {
        if (value == null) {
            return defaultValue;
        }
        String str = value.toString().trim();
        if (str.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            if (key != null) {
                log.warn("Invalid {} '{}' for workspace {}, using default {}",
                        key, str, workspaceId, defaultValue);
            }
            return defaultValue;
        }
    }
}