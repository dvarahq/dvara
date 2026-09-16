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

import com.dvarahq.core.guardrail.GuardrailDetection;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.settings.SettingsBooleans;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Detects content policy violations (profanity, violence, sexual content,
 * competitor mentions, topic restrictions) using regex patterns.
 */
public class ContentFilterDetector implements GuardrailDetector {

    private final ContentPatternRegistry contentPatternRegistry;
    private final WorkspaceRepository workspaceRepository;

    public ContentFilterDetector(ContentPatternRegistry contentPatternRegistry,
                                  WorkspaceRepository workspaceRepository) {
        this(contentPatternRegistry, workspaceRepository, null);
    }

    /**
     * @param rules the typed rule store, or {@code null} to read the legacy comma-separated
     *              metadata keys.
     */
    public ContentFilterDetector(ContentPatternRegistry contentPatternRegistry,
                                  WorkspaceRepository workspaceRepository,
                                  com.dvarahq.core.workspace.settings.ContentRuleRepository rules) {
        this.contentPatternRegistry = contentPatternRegistry;
        this.workspaceRepository = workspaceRepository;
        this.rules = rules;
    }

    private final com.dvarahq.core.workspace.settings.ContentRuleRepository rules;

    /** The custom-denylist store, or null to read the legacy nested map from metadata. */
    private com.dvarahq.core.workspace.settings.WorkspaceSettingEntryRepository settingEntries;

    public void setSettingEntries(com.dvarahq.core.workspace.settings.WorkspaceSettingEntryRepository settingEntries) {
        this.settingEntries = settingEntries;
    }

    /** The typed guardrail store, or null to read the legacy metadata key. */
    private com.dvarahq.core.workspace.settings.GuardrailSettingsRepository guardrailSettings;

    public void setGuardrailSettings(com.dvarahq.core.workspace.settings.GuardrailSettingsRepository guardrailSettings) {
        this.guardrailSettings = guardrailSettings;
    }

    @Override
    public GuardrailScanResult scan(String text, String workspaceId) {
        List<ContentPatternRegistry.PatternEntry> patterns = resolvePatterns(workspaceId);
        return scanWithPatterns(text, patterns);
    }

    @Override
    public List<GuardrailScanResult> scanRequest(ChatRequest request, String workspaceId) {
        List<GuardrailScanResult> results = new ArrayList<>();
        if (request.getMessages() == null) return results;

        List<ContentPatternRegistry.PatternEntry> patterns = resolvePatterns(workspaceId);

        for (MultimodalMessage msg : request.getMessages()) {
            if (msg.getContent() != null) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ContentBlock.TextBlock tb) {
                        GuardrailScanResult result = scanWithPatterns(tb.text(), patterns);
                        if (result.hasDetections()) results.add(result);
                    }
                }
            }
            // scan tool-call arguments so content policy sees function-calling content.
            for (String args : GuardrailScanSupport.toolCallArguments(msg)) {
                GuardrailScanResult result = scanWithPatterns(args, patterns);
                if (result.hasDetections()) results.add(result);
            }
        }
        return results;
    }

    /**
     * Scans a tool call the model asked for, as well as its text. Scanning the call here catches
     * disallowed content a turn earlier than the request-side replay would, and at all for a caller
     * that executes the call without replaying it.
     */
    @Override
    public List<GuardrailScanResult> scanResponse(ChatResponse response, String workspaceId) {
        List<GuardrailScanResult> results = new ArrayList<>();
        if (response.getChoices() == null) return results;

        List<ContentPatternRegistry.PatternEntry> patterns = resolvePatterns(workspaceId);

        for (ChatResponse.Choice choice : response.getChoices()) {
            if (choice.getMessage() == null) continue;
            if (choice.getMessage().getContent() != null) {
                for (ContentBlock block : choice.getMessage().getContent()) {
                    if (block instanceof ContentBlock.TextBlock tb) {
                        GuardrailScanResult result = scanWithPatterns(tb.text(), patterns);
                        if (result.hasDetections()) results.add(result);
                    }
                }
            }
            for (String args : GuardrailScanSupport.toolCallArguments(choice.getMessage())) {
                GuardrailScanResult result = scanWithPatterns(args, patterns);
                if (result.hasDetections()) results.add(result);
            }
        }
        return results;
    }

    private List<ContentPatternRegistry.PatternEntry> resolvePatterns(String workspaceId) {
        if (workspaceId == null) {
            return contentPatternRegistry.getPatterns();
        }

        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            return contentPatternRegistry.getPatterns();
        }

        // A null metadata map must not skip the typed store: the map is the legacy fallback, and its
        // absence cannot decide whether the typed rows are read. Null is unreachable on PostgreSQL,
        // where an empty JSONB deserializes to an empty map, and reachable on any store that leaves
        // the field null.
        Map<String, Object> metadata = workspace.getMetadata() == null
                ? Map.of() : workspace.getMetadata();

        // Content filtering off for this workspace? typed row first, map as the bridge.
        Boolean typedContentEnabled = guardrailSettings == null ? null
                : guardrailSettings.findByWorkspaceId(workspace.getId())
                        .map(com.dvarahq.core.workspace.settings.GuardrailSettings::contentEnabled)
                        .orElse(null);
        if (typedContentEnabled != null) {
            if (!typedContentEnabled) {
                return List.of();
            }
        } else {
            // SettingsBooleans, not Boolean.parseBoolean: under that parser "yes" reads as false and
            // would switch content filtering off for the workspace, as would an unreadable value.
            // Null means inherit, so a typo leaves the install-wide posture in place rather than
            // disabling a protecting control. The other metadata booleans follow the same rule.
            Boolean mapContentEnabled = SettingsBooleans.yaml(
                    metadata.get("guardrail.content.enabled"));
            if (mapContentEnabled != null && !mapContentEnabled) {
                return List.of(); // Disabled: return empty patterns
            }
        }

        // Each of the three comes from typed rows when it has any, and from the metadata map when it
        // does not. The content is identical either way: the projection builds the rows from this
        // same map.
        return contentPatternRegistry.mergeWithWorkspaceConfig(metadata,
                rules == null ? java.util.List.<String>of()
                        : rules.valuesOf(workspace.getId(), com.dvarahq.core.workspace.settings.ContentRule.Kind.COMPETITOR_KEYWORD),
                rules == null ? java.util.List.<String>of()
                        : rules.valuesOf(workspace.getId(), com.dvarahq.core.workspace.settings.ContentRule.Kind.TOPIC_RESTRICTION),
                settingEntries == null ? java.util.Map.<String, String>of()
                        : settingEntries.mapOf(workspace.getId(),
                                com.dvarahq.core.workspace.settings.WorkspaceSettingEntry.Kind.CONTENT_DENYLIST));
    }

    private GuardrailScanResult scanWithPatterns(String text,
                                                   List<ContentPatternRegistry.PatternEntry> patterns) {
        if (text == null || text.isEmpty()) {
            return GuardrailScanResult.EMPTY;
        }
        if (patterns.isEmpty()) {
            // Real text, nothing configured to look for. Carries the text: the composite joins each
            // detector's per-message results on it, and a blank one here would not pair with the
            // other detectors' result for the same message.
            return GuardrailScanResult.clean(text);
        }

        List<GuardrailDetection> detections = new ArrayList<>();
        for (ContentPatternRegistry.PatternEntry entry : patterns) {
            Matcher matcher = entry.pattern().matcher(text);
            while (matcher.find()) {
                detections.add(new GuardrailDetection(
                        entry.category(),
                        entry.label(),
                        matcher.group(),
                        entry.riskScore(),
                        1.0,
                        entry.ruleId()));
            }
        }

        if (detections.isEmpty()) {
            return GuardrailScanResult.clean(text);
        }

        return new GuardrailScanResult(detections, text);
    }
}