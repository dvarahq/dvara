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

/**
 * How <b>anything</b> reads a boolean out of the {@code Workspace.metadata} map.
 *
 * <p>Public because the typed records are not the only readers: the guardrail, PII, grounding and
 * loop-detection filters each fall back to the same keys on the request path. One definition,
 * because {@code Boolean.parseBoolean} treats anything that is not {@code "true"} as {@code false},
 * so a workspace whose map said {@code guardrail.enabled: "yes"} would have guardrails off, and
 * nothing in the map shows which parser a key gets.</p>
 *
 * <p><b>Unrecognised is {@code null}, which means inherit, not {@code false}.</b> For a safety
 * control, a typo must not silently disable whatever it was attached to. Inheriting the
 * install-wide value is both the safer answer and the one the workspace would expect from a value
 * the system could not read.</p>
 *
 * <p>Values follow YAML 1.1, which is what an operator writing this map is almost always actually
 * writing: {@code true/t/yes/y/on/1} and {@code false/f/no/n/off/0}, case-insensitive, plus real
 * {@link Boolean}s and {@link Number}s (non-zero being true) as they arrive from JSONB.</p>
 */
public final class SettingsBooleans {

    private SettingsBooleans() {
    }

    /**
     * {@code null} when absent or unreadable — in both cases the workspace inherits.
     *
     * <p>Right for a setting that <b>protects</b>: PII detection, guardrails, grounding, loop
     * detection. There, {@code false} means unprotected, so a value the system could not read must
     * not be allowed to mean it. Use {@link #yamlOrFalse} for a setting that <b>collects</b>, where
     * the safe answer is the opposite one.</p>
     */
    public static Boolean yaml(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return switch (String.valueOf(value).trim().toLowerCase()) {
            case "true", "t", "yes", "y", "on", "1" -> Boolean.TRUE;
            case "false", "f", "no", "n", "off", "0" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * As {@link #yaml}, but an unreadable value is {@code false} rather than inherit.
     *
     * <p>For the settings that <b>collect</b> rather than protect — {@code audit.store-prompts} and
     * {@code pii.auto-detokenize-response}, both of which put personal data somewhere it would
     * otherwise not be. Inheriting would let a value nobody can read switch collection <em>on</em>,
     * which is the one direction that cannot be undone: prompts stored, or raw PII returned to a
     * caller.</p>
     *
     * <p>The YAML spellings are identical either way. This differs only in what an unreadable value
     * falls back to, because the safe fallback is a property of the setting rather than of the
     * parser.</p>
     */
    public static Boolean yamlOrFalse(Object value) {
        Boolean parsed = yaml(value);
        return parsed != null ? parsed : (value == null ? null : Boolean.FALSE);
    }
}