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
package com.dvarahq.core.workspace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {
    private String id;
    private String name;
    /**
     * The owning team. Nullable: every production path sets it, but many tests build a
     * {@code Workspace} directly, and the column is not NOT NULL.
     */
    private String teamId;
    private WorkspaceStatus status;
    private String region;
    private Map<String, Object> metadata;
    /**
     * The workspace's own governance settings, grouped by area (see {@link #SETTINGS_AREAS}), for a
     * store that keeps them apart from {@link #metadata}. Each area maps the same keys {@link #metadata}
     * uses, for example {@code "pii": {"pii.action": "BLOCK"}}. Nullable: the file store does not set it.
     * Read governance settings through {@link #governanceSettings()}, not from either map directly.
     */
    private Map<String, Object> settings;

    /**
     * The settings areas, and the key prefixes each one owns in {@link #metadata}.
     */
    public static final Map<String, List<String>> SETTINGS_AREAS = Map.of(
            "pii", List.of("pii."),
            "guardrail", List.of("guardrail.", "grounding.", "embedded."),
            "agentic", List.of("agentic."),
            "approval", List.of("approval."),
            "governance", List.of("audit.", "credentials.", "ip-access."));

    /**
     * The governance settings in force: {@link #metadata} with each area of {@link #settings} laid over it.
     *
     * <p>An area present in {@link #settings} replaces that area whole: every {@link #metadata} key the
     * area owns is dropped, whether or not the area repeats it. So a rule cleared in the settings stops
     * applying, instead of an older copy in the metadata taking over. An area that is absent leaves the
     * metadata keys as they are, so a workspace with no settings reads exactly its metadata.
     *
     * @return an unmodifiable map; never null
     */
    public Map<String, Object> governanceSettings() {
        Map<String, Object> out = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
        if (settings == null || settings.isEmpty()) {
            return Collections.unmodifiableMap(out);
        }
        for (Map.Entry<String, Object> area : settings.entrySet()) {
            List<String> prefixes = SETTINGS_AREAS.get(area.getKey());
            if (prefixes != null) {
                out.keySet().removeIf(key -> prefixes.stream().anyMatch(key::startsWith));
            }
            if (area.getValue() instanceof Map<?, ?> values) {
                values.forEach((key, value) -> {
                    if (key != null && value != null) {
                        out.put(key.toString(), value);
                    }
                });
            }
        }
        return Collections.unmodifiableMap(out);
    }
    private Instant createdAt;
    private Instant updatedAt;
}