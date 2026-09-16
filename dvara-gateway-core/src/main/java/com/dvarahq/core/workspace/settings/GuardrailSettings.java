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
package com.dvarahq.core.workspace.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A workspace's guardrail posture, as typed data rather than ten keys in a JSONB map.
 *
 * <p>{@code guardrail.risk-score-threshold} is a probability, {@code max-input-tokens} is a count
 * and {@code guardrail.action} is an enum; typed fields let each be validated once at write time
 * rather than parsed at every read.
 *
 * <p>Nullable throughout, for the reason {@link PiiSettings} and {@link WorkspaceRateLimits} give: a
 * workspace that never set a threshold inherits the install-wide one, and that is not the same
 * statement as setting it to zero.
 */
public record GuardrailSettings(String workspaceId,
                                Boolean enabled,
                                String action,
                                Double riskScoreThreshold,
                                Integer maxInputTokens,
                                Integer maxMessagesPerRequest,
                                Integer maxMessageLength,
                                Integer defaultMaxResponseTokens,
                                Boolean scanStreamingResponses,
                                Integer contextWarningThresholdPct,
                                Integer contextHardThresholdPct,
                                String contextPruningStrategy,
                                Boolean contentEnabled,
                                Boolean mcpInjectionEnabled,
                                String mcpInjectionAction,
                                Double mcpInjectionRiskThreshold,
                                Boolean semanticEnabled,
                                Double semanticSimilarityThreshold,
                                Boolean groundingEnabled,
                                String groundingAction,
                                Integer groundingMaxSources,
                                Integer groundingMaxSourceLength) {

    public static GuardrailSettings unset(String workspaceId) {
        return new GuardrailSettings(workspaceId, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * This settings' own opinions, with {@code fallback} answering wherever this one says nothing.
     *
     * <p>For write paths. The readers ({@code GuardrailScanService} and the streaming posture
     * provider) take this row field by field and fall through to the metadata map for each null,
     * but a writer whose form carries only some of the fields should still write the row complete,
     * seeding the rest from what the workspace currently has rather than from {@link #unset}.
     */
    public GuardrailSettings withFallback(GuardrailSettings fallback) {
        if (fallback == null) {
            return this;
        }
        return new GuardrailSettings(workspaceId,
                enabled != null ? enabled : fallback.enabled(),
                action != null ? action : fallback.action(),
                riskScoreThreshold != null ? riskScoreThreshold : fallback.riskScoreThreshold(),
                maxInputTokens != null ? maxInputTokens : fallback.maxInputTokens(),
                maxMessagesPerRequest != null ? maxMessagesPerRequest : fallback.maxMessagesPerRequest(),
                maxMessageLength != null ? maxMessageLength : fallback.maxMessageLength(),
                defaultMaxResponseTokens != null ? defaultMaxResponseTokens : fallback.defaultMaxResponseTokens(),
                scanStreamingResponses != null ? scanStreamingResponses : fallback.scanStreamingResponses(),
                contextWarningThresholdPct != null ? contextWarningThresholdPct : fallback.contextWarningThresholdPct(),
                contextHardThresholdPct != null ? contextHardThresholdPct : fallback.contextHardThresholdPct(),
                contextPruningStrategy != null ? contextPruningStrategy : fallback.contextPruningStrategy(),
                contentEnabled != null ? contentEnabled : fallback.contentEnabled(),
                mcpInjectionEnabled != null ? mcpInjectionEnabled : fallback.mcpInjectionEnabled(),
                mcpInjectionAction != null ? mcpInjectionAction : fallback.mcpInjectionAction(),
                mcpInjectionRiskThreshold != null ? mcpInjectionRiskThreshold : fallback.mcpInjectionRiskThreshold(),
                semanticEnabled != null ? semanticEnabled : fallback.semanticEnabled(),
                semanticSimilarityThreshold != null ? semanticSimilarityThreshold : fallback.semanticSimilarityThreshold(),
                groundingEnabled != null ? groundingEnabled : fallback.groundingEnabled(),
                groundingAction != null ? groundingAction : fallback.groundingAction(),
                groundingMaxSources != null ? groundingMaxSources : fallback.groundingMaxSources(),
                groundingMaxSourceLength != null ? groundingMaxSourceLength : fallback.groundingMaxSourceLength());
    }

    public boolean isUnset() {
        return enabled == null && action == null && riskScoreThreshold == null && maxInputTokens == null
                && maxMessagesPerRequest == null && maxMessageLength == null
                && defaultMaxResponseTokens == null && scanStreamingResponses == null
                && contextWarningThresholdPct == null && contextHardThresholdPct == null
                && contextPruningStrategy == null && contentEnabled == null
                && mcpInjectionEnabled == null && mcpInjectionAction == null
                && mcpInjectionRiskThreshold == null && semanticEnabled == null
                && semanticSimilarityThreshold == null && groundingEnabled == null
                && groundingAction == null && groundingMaxSources == null
                && groundingMaxSourceLength == null;
    }

    /** The migration bridge — lenient, because it reads values a store that validated nothing accepted. */
    public static GuardrailSettings fromMetadata(String workspaceId, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return unset(workspaceId);
        }
        return new GuardrailSettings(workspaceId,
                bool(metadata.get("guardrail.enabled")),
                text(metadata.get("guardrail.action")),
                fraction(metadata.get("guardrail.risk-score-threshold")),
                count(metadata.get("guardrail.max-input-tokens")),
                count(metadata.get("guardrail.max-messages-per-request")),
                count(metadata.get("guardrail.max-message-length")),
                count(metadata.get("guardrail.default-max-response-tokens")),
                bool(metadata.get("guardrail.scan-streaming-responses")),
                percent(metadata.get("guardrail.context.warning-threshold-pct")),
                percent(metadata.get("guardrail.context.hard-threshold-pct")),
                text(metadata.get("guardrail.context.pruning-strategy")),
                bool(metadata.get("guardrail.content.enabled")),
                bool(metadata.get("guardrail.mcp-injection.enabled")),
                text(metadata.get("guardrail.mcp-injection.action")),
                fraction(metadata.get("guardrail.mcp-injection.risk-score-threshold")),
                bool(metadata.get("guardrail.semantic.enabled")),
                fraction(metadata.get("guardrail.semantic.similarity-threshold")),
                bool(metadata.get("grounding.enabled")),
                text(metadata.get("grounding.action")),
                count(metadata.get("grounding.max-sources")),
                count(metadata.get("grounding.max-source-length")));
    }

    /** Projects back to the map shape the config bundle carries. Only set fields are emitted. */
    public Map<String, Object> toMetadata() {
        Map<String, Object> out = new LinkedHashMap<>();
        put(out, "guardrail.enabled", enabled);
        put(out, "guardrail.action", action);
        put(out, "guardrail.risk-score-threshold", riskScoreThreshold);
        put(out, "guardrail.max-input-tokens", maxInputTokens);
        put(out, "guardrail.max-messages-per-request", maxMessagesPerRequest);
        put(out, "guardrail.max-message-length", maxMessageLength);
        put(out, "guardrail.default-max-response-tokens", defaultMaxResponseTokens);
        put(out, "guardrail.scan-streaming-responses", scanStreamingResponses);
        put(out, "guardrail.context.warning-threshold-pct", contextWarningThresholdPct);
        put(out, "guardrail.context.hard-threshold-pct", contextHardThresholdPct);
        put(out, "guardrail.context.pruning-strategy", contextPruningStrategy);
        put(out, "guardrail.content.enabled", contentEnabled);
        put(out, "guardrail.mcp-injection.enabled", mcpInjectionEnabled);
        put(out, "guardrail.mcp-injection.action", mcpInjectionAction);
        put(out, "guardrail.mcp-injection.risk-score-threshold", mcpInjectionRiskThreshold);
        put(out, "guardrail.semantic.enabled", semanticEnabled);
        put(out, "guardrail.semantic.similarity-threshold", semanticSimilarityThreshold);
        put(out, "grounding.enabled", groundingEnabled);
        put(out, "grounding.action", groundingAction);
        put(out, "grounding.max-sources", groundingMaxSources);
        put(out, "grounding.max-source-length", groundingMaxSourceLength);
        return out;
    }

    /**
     * Parses a risk threshold for a WRITE.
     *
     * <p>A threshold outside {@code [0, 1]} is not a stricter setting; it is one that can never
     * match, or one that matches everything, so it is refused rather than stored.
     */
    public static Double parseThreshold(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Risk score threshold must be a number between 0 and 1, "
                    + "got '" + raw + "'");
        }
        if (value < 0 || value > 1 || Double.isNaN(value)) {
            throw new IllegalArgumentException("Risk score threshold must be between 0 and 1, got "
                    + value + ". A value outside that range does not make the guardrail stricter — it "
                    + "makes it match nothing, or everything.");
        }
        return value;
    }

    /** Parses a percentage threshold for a WRITE; outside 1–100 is refused for the same reason. */
    public static Integer parsePercent(String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(field + " must be a whole percentage, got '" + raw + "'");
        }
        if (value < 1 || value > 100) {
            throw new IllegalArgumentException(field + " must be between 1 and 100, got " + value);
        }
        return value;
    }

    private static void put(Map<String, Object> out, String key, Object value) {
        if (value != null) {
            out.put(key, value);
        }
    }

    /** YAML 1.1, shared with every other setting in this package (see {@link SettingsBooleans}). */
    private static Boolean bool(Object value) {
        return SettingsBooleans.yaml(value);
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static Double fraction(Object value) {
        Double parsed = number(value);
        return parsed == null || parsed < 0 || parsed > 1 ? null : parsed;
    }

    private static Integer percent(Object value) {
        Double parsed = number(value);
        if (parsed == null) {
            return null;
        }
        int as = parsed.intValue();
        return as >= 1 && as <= 100 ? as : null;
    }

    /**
     * A count, where <b>zero is a value and not an absence</b>.
     *
     * <p>Every count in this record is applied only when it is positive, so zero is how a workspace
     * says <i>do not apply this limit</i>. Null means no opinion, so dropping the zero would make
     * the workspace inherit the install-wide limit it had just switched off.
     * {@code GuardrailScanService} reads the same metadata key and honours the zero, and this
     * must agree with it.</p>
     *
     * <p>Negatives become null. There is no setting here a negative expresses.</p>
     */
    private static Integer count(Object value) {
        Double parsed = number(value);
        if (parsed == null) {
            return null;
        }
        int as = parsed.intValue();
        return as >= 0 ? as : null;
    }

    private static Double number(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}