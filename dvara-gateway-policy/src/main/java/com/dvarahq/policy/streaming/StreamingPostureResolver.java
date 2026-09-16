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
package com.dvarahq.policy.streaming;

import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.enforcement.StreamingPosture;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.workspace.settings.GuardrailSettings;
import com.dvarahq.core.workspace.settings.PiiSettings;
import com.dvarahq.core.workspace.settings.SettingsBooleans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one algorithm that decides a workspace's streaming posture.
 *
 * <pre>
 * resolve(field):
 *     1. typed settings row        -&gt; if the field is non-null, use it
 *     2. legacy workspace metadata -&gt; if present and readable (YAML 1.1), use it
 *     3. install-wide default
 *
 * effective(enablement flag) = globalSwitch AND resolve(field)
 * effective(everything else) = resolve(field)
 * </pre>
 *
 * <p>Metadata fills only what the typed row left null. Both are live while workspace settings
 * migrate from the metadata map to typed rows, so a workspace can carry an old map and a new row at
 * once, and reading the map over the row would undo whatever the operator last saved in the
 * Console.</p>
 *
 * <p>Global enablement is an AND, not a starting value. An operator switching a scan off switches
 * it off for everyone; no workspace value restores it.</p>
 *
 * <p>Grounding is an override rather than a kill switch: there is no separate global scan flag for it,
 * and a workspace enabling it is asking for <em>more</em> checking, not less.</p>
 *
 * <p>The posture's grounding sources are the ones that survive {@code grounding.max-sources} and
 * {@code grounding.max-source-length}, resolved by the same three steps. Capping here rather than at
 * each plane's extraction point keeps the LLM and A2A planes in agreement.</p>
 */
public final class StreamingPostureResolver {

    private static final Logger log = LoggerFactory.getLogger(StreamingPostureResolver.class);

    private StreamingPostureResolver() {
    }

    public static StreamingPosture resolve(StreamingDefaults defaults,
                                           PiiSettings typedPii,
                                           GuardrailSettings typedGuardrail,
                                           Map<String, Object> metadata,
                                           List<String> groundingSources,
                                           String workspaceId) {
        Map<String, Object> meta = metadata == null ? Map.of() : metadata;

        boolean piiEnabled = defaults.piiEnabled()
                && defaults.piiScanStreaming()
                && bool(typedPii == null ? null : typedPii.enabled(), meta.get("pii.enabled"), true)
                // The typed table has no streaming column: `PiiSettings.scanResponses` is the
                // non-streaming `pii.scan-responses`, a different field. Posture resolves per field, so
                // the typed step is null here and the workspace's own metadata key governs.
                && bool(null, meta.get("pii.scan-streaming-responses"), true);

        PiiAction piiAction = defaults.piiAction();
        if (typedPii != null && typedPii.action() != null) {
            piiAction = typedPii.action();
        } else if (meta.get("pii.action") != null) {
            String raw = meta.get("pii.action").toString();
            try {
                piiAction = PiiAction.valueOf(raw.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid pii.action '{}' for workspace {}, using {}", raw, workspaceId, piiAction);
            }
        }

        // A typed row's pattern map wins even when it is empty. PiiSettings normalises null to an
        // empty map, so the type cannot say "cleared" separately from "unset", and the non-streaming
        // path (PiiSettingsResolver) returns the typed row without ever reading metadata. Falling
        // back to metadata on an empty map would resurrect patterns a workspace had deleted, on the
        // streamed path only.
        Map<String, String> patterns = Map.of();
        if (typedPii != null) {
            patterns = typedPii.customPatterns();
        } else if (meta.get("pii.custom-patterns") instanceof Map<?, ?> raw && !raw.isEmpty()) {
            Map<String, String> parsed = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k != null && v != null) {
                    parsed.put(k.toString(), v.toString());
                }
            });
            patterns = parsed;
        }

        Boolean typedGuardrailEnabled = typedGuardrail == null ? null : typedGuardrail.enabled();
        Boolean typedGuardrailStream = typedGuardrail == null ? null : typedGuardrail.scanStreamingResponses();
        boolean guardrailEnabled = defaults.guardrailEnabled()
                && defaults.guardrailScanStreaming()
                && bool(typedGuardrailEnabled, meta.get("guardrail.enabled"), true)
                && bool(typedGuardrailStream, meta.get("guardrail.scan-streaming-responses"), true);

        GuardrailAction guardrailAction = action(
                typedGuardrail == null ? null : typedGuardrail.action(),
                meta.get("guardrail.action"), defaults.guardrailAction(),
                "guardrail.action", workspaceId);

        double riskThreshold = number(
                typedGuardrail == null ? null : typedGuardrail.riskScoreThreshold(),
                meta.get("guardrail.risk-score-threshold"), defaults.guardrailRiskThreshold(),
                "guardrail.risk-score-threshold", workspaceId);

        boolean groundingEnabled = bool(
                typedGuardrail == null ? null : typedGuardrail.groundingEnabled(),
                meta.get("grounding.enabled"), defaults.groundingEnabled());

        GuardrailAction groundingAction = action(
                typedGuardrail == null ? null : typedGuardrail.groundingAction(),
                meta.get("grounding.action"), defaults.groundingAction(),
                "grounding.action", workspaceId);

        int maxSources = integer(
                typedGuardrail == null ? null : typedGuardrail.groundingMaxSources(),
                meta.get("grounding.max-sources"), defaults.groundingMaxSources(),
                "grounding.max-sources", workspaceId);

        int maxSourceLength = integer(
                typedGuardrail == null ? null : typedGuardrail.groundingMaxSourceLength(),
                meta.get("grounding.max-source-length"), defaults.groundingMaxSourceLength(),
                "grounding.max-source-length", workspaceId);

        return new StreamingPosture(piiEnabled, piiAction, patterns,
                guardrailEnabled, guardrailAction, riskThreshold,
                groundingEnabled, groundingAction,
                capped(groundingSources, maxSources, maxSourceLength));
    }

    /**
     * The source documents this posture will hand the detector.
     *
     * <p>The list arrives from {@code request.metadata["grounding.sources"]} — the caller's own
     * request body — and the detector embeds every document it is given and then compares every
     * claim against every one of them. These two settings are the only bound on that work.</p>
     *
     * <p>Order and edges match the non-streaming filter: drop what is too long, then take the first
     * {@code maxSources}, and treat a non-positive value as unlimited.</p>
     */
    private static List<String> capped(List<String> sources, int maxSources, int maxSourceLength) {
        if (sources == null || sources.isEmpty()) {
            return List.of();
        }
        return sources.stream()
                .filter(s -> maxSourceLength <= 0 || s.length() <= maxSourceLength)
                .limit(maxSources > 0 ? maxSources : Long.MAX_VALUE)
                .toList();
    }

    /** Typed, else metadata when it reads as a number, else the default. */
    private static int integer(Integer typed, Object metadata, int fallback,
                               String field, String workspaceId) {
        if (typed != null) {
            return typed;
        }
        if (metadata == null) {
            return fallback;
        }
        try {
            return metadata instanceof Number n
                    ? n.intValue() : Integer.parseInt(metadata.toString().trim());
        } catch (NumberFormatException e) {
            // Named, not silent: the workspace that wrote it believes it is bounded.
            log.warn("Invalid {} '{}' for workspace {}, using {}", field, metadata, workspaceId, fallback);
            return fallback;
        }
    }

    private static double number(Double typed, Object metadata, double fallback,
                                 String field, String workspaceId) {
        if (typed != null) {
            return typed;
        }
        if (metadata == null) {
            return fallback;
        }
        try {
            double value = metadata instanceof Number n
                    ? n.doubleValue() : Double.parseDouble(metadata.toString().trim());
            if (Double.isFinite(value) && value >= 0.0 && value <= 1.0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Warn and inherit, matching the legacy settings readers.
        }
        log.warn("Invalid {} '{}' for workspace {}, using {}", field, metadata, workspaceId, fallback);
        return fallback;
    }

    /** Typed, else metadata when readable, else the default. */
    private static boolean bool(Boolean typed, Object metadataValue, boolean fallback) {
        if (typed != null) {
            return typed;
        }
        Boolean parsed = SettingsBooleans.yaml(metadataValue);
        return parsed != null ? parsed : fallback;
    }

    private static GuardrailAction action(String typed, Object metadataValue, GuardrailAction fallback,
                                          String key, String workspaceId) {
        String raw = typed != null ? typed : metadataValue != null ? metadataValue.toString() : null;
        if (raw == null) {
            return fallback;
        }
        try {
            return GuardrailAction.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            // Named, not silent: a workspace that wrote BLOK believes it is blocking.
            log.warn("Invalid {} '{}' for workspace {}, using {}", key, raw, workspaceId, fallback);
            return fallback;
        }
    }
}
