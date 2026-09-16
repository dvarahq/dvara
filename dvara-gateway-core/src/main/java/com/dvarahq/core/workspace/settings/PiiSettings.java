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

import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiDegradedAction;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A workspace's PII posture, as typed data rather than as entries in a JSONB map.
 *
 * <p>An untyped map cannot be queried ("which workspaces block PII?" is a JSONB scan), cannot be
 * validated (a mistyped key is silently ignored), and cannot be edited safely (updating one key is
 * a read-modify-write of the whole map).
 *
 * <p><b>Nullable fields mean "not set".</b> A workspace that has never expressed an opinion must
 * inherit the install-wide default, and a boolean cannot say that. A {@code false} here is a
 * workspace that turned PII scanning off; a {@code null} is one that never mentioned it.
 *
 * <p><b>Validation belongs on the way in, defaults on the way out.</b> {@link #parseAction} rejects
 * a bad value so a typo fails at the API boundary where someone is watching. Reads keep falling
 * back, because turning a stored value into a request-path exception would convert a configuration
 * mistake into an outage.
 */
public record PiiSettings(String workspaceId,
                          Boolean enabled,
                          PiiAction action,
                          Boolean scanResponses,
                          Boolean autoDetokenizeResponse,
                          PiiDegradedAction degradedAction,
                          Map<String, String> customPatterns) {

    public PiiSettings {
        customPatterns = customPatterns == null ? Map.of() : Map.copyOf(customPatterns);
    }

    /**
     * A workspace with no stored opinion: every field inherits.
     *
     * <p><b>Not a baseline to write from.</b> A write path that seeds a new row with this and fills
     * in part of it stores nulls for the rest, and a save of one field would drop the workspace's
     * custom patterns. Seed from {@link #fromMetadata} (or the row that is already there) so the row
     * you write says what the workspace currently has.
     */
    public static PiiSettings unset(String workspaceId) {
        return new PiiSettings(workspaceId, null, null, null, null, null, Map.of());
    }

    /**
     * This settings' own opinions, with {@code fallback} answering wherever this one says nothing.
     *
     * <p>A null field means "no opinion", so it takes the fallback's value; {@code customPatterns}
     * falls back when empty, since an empty map and no map are the same statement and the record
     * normalises null to empty in its constructor. A writer whose form carries only some of the
     * fields uses this to carry the rest forward, so the row it writes is complete.
     */
    public PiiSettings withFallback(PiiSettings fallback) {
        if (fallback == null) {
            return this;
        }
        return new PiiSettings(workspaceId,
                enabled != null ? enabled : fallback.enabled(),
                action != null ? action : fallback.action(),
                scanResponses != null ? scanResponses : fallback.scanResponses(),
                autoDetokenizeResponse != null ? autoDetokenizeResponse : fallback.autoDetokenizeResponse(),
                degradedAction != null ? degradedAction : fallback.degradedAction(),
                customPatterns.isEmpty() ? fallback.customPatterns() : customPatterns);
    }

    /**
     * Reads the legacy {@code pii.*} entries out of a metadata map.
     *
     * <p>Deliberately lenient: it reads values accepted by a store that validated nothing, and
     * refusing here would fail a request on stored data.
     */
    public static PiiSettings fromMetadata(String workspaceId, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return unset(workspaceId);
        }
        Map<String, String> patterns = new LinkedHashMap<>();
        if (metadata.get("pii.custom-patterns") instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    patterns.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        return new PiiSettings(workspaceId,
                bool(metadata.get("pii.enabled")),
                lenientAction(metadata.get("pii.action")),
                bool(metadata.get("pii.scan-responses")),
                SettingsBooleans.yamlOrFalse(metadata.get("pii.auto-detokenize-response")),
                metadata.containsKey("pii.degraded-action")
                        ? PiiDegradedAction.fromMetadata(metadata.get("pii.degraded-action"))
                        : null,
                patterns);
    }

    /**
     * Projects back to the metadata shape the config bundle carries on the wire.
     *
     * <p>Only fields that are actually set are emitted: an absent key is how "inherit" is
     * expressed, and writing every field would turn every unset value into an explicit one.
     */
    public Map<String, Object> toMetadata() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (enabled != null) {
            out.put("pii.enabled", enabled);
        }
        if (action != null) {
            out.put("pii.action", action.name());
        }
        if (scanResponses != null) {
            out.put("pii.scan-responses", scanResponses);
        }
        if (autoDetokenizeResponse != null) {
            out.put("pii.auto-detokenize-response", autoDetokenizeResponse);
        }
        if (degradedAction != null) {
            out.put("pii.degraded-action", degradedAction.name());
        }
        if (!customPatterns.isEmpty()) {
            out.put("pii.custom-patterns", customPatterns);
        }
        return out;
    }

    /** True when the workspace expressed no opinion at all — nothing to store. */
    public boolean isUnset() {
        return enabled == null && action == null && scanResponses == null
                && autoDetokenizeResponse == null && degradedAction == null && customPatterns.isEmpty();
    }

    /**
     * Parses an action for a WRITE, rejecting what it cannot understand.
     *
     * <p>A typo is refused where an operator can see it, instead of becoming a default nobody
     * chose. Blank is not an error; it is how the field is cleared.
     */
    public static PiiAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PiiAction.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown PII action '" + raw + "'. Expected one of "
                    + java.util.Arrays.toString(PiiAction.values()));
        }
    }

    /** YAML 1.1, shared with every other setting in this package (see {@link SettingsBooleans}). */
    private static Boolean bool(Object value) {
        return SettingsBooleans.yaml(value);
    }

    private static PiiAction lenientAction(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return PiiAction.valueOf(String.valueOf(value).toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;   // exactly what the map-reading code did: warn-and-inherit
        }
    }
}