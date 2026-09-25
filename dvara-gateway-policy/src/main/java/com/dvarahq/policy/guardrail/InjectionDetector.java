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

import com.dvarahq.core.guardrail.MlClassifierHook;
import com.dvarahq.core.guardrail.GuardrailDetection;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.settings.WorkspaceSettingEntry;
import com.dvarahq.core.workspace.settings.WorkspaceSettingEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Detects jailbreak and prompt injection patterns in text using regex patterns.
 * Supports custom per-workspace patterns and optional ML classifier integration.
 *
 * <p>{@code guardrail.injection.custom-patterns} is resolved here from the workspace id the
 * {@link GuardrailDetector} contract carries, the same way {@link ContentFilterDetector} resolves
 * competitor keywords and topic restrictions. Resolving from the workspace rather than threading a
 * map through the interface keeps {@code GuardrailDetector} unchanged, which matters because
 * detectors outside this module implement it.</p>
 */
public class InjectionDetector implements GuardrailDetector {

    private static final Logger log = LoggerFactory.getLogger(InjectionDetector.class);

    private final InjectionPatternRegistry patternRegistry;
    private final MlClassifierHook mlClassifierHook;

    /** The workspace source, or {@code null} to scan with the built-in patterns alone. */
    private final WorkspaceRepository workspaceRepository;

    /** The typed store (custom patterns as rows), or null to read the legacy metadata map. */
    private WorkspaceSettingEntryRepository settingEntries;

    public void setSettingEntries(WorkspaceSettingEntryRepository settingEntries) {
        this.settingEntries = settingEntries;
    }

    /** Without a workspace source: built-in patterns only, for a caller that has no workspaces to read. */
    public InjectionDetector(InjectionPatternRegistry patternRegistry, MlClassifierHook mlClassifierHook) {
        this(patternRegistry, mlClassifierHook, null);
    }

    public InjectionDetector(InjectionPatternRegistry patternRegistry, MlClassifierHook mlClassifierHook,
                             WorkspaceRepository workspaceRepository) {
        this.patternRegistry = patternRegistry;
        this.mlClassifierHook = mlClassifierHook;
        this.workspaceRepository = workspaceRepository;
    }

    @Override
    public GuardrailScanResult scan(String text, String workspaceId) {
        return scanWithPatterns(text, resolvePatterns(workspaceId));
    }

    /**
     * Scan against the built-ins plus an explicitly supplied pattern set. Named rather than a second
     * {@code scan} overload: {@code scan(String, Map)} beside {@code scan(String, String)} would make
     * {@code scan(text, null)} ambiguous.
     */
    public GuardrailScanResult scanWithCustomPatterns(String text, Map<String, String> customPatterns) {
        List<InjectionPatternRegistry.PatternEntry> patterns = patternRegistry.mergeWithCustom(customPatterns);
        return scanWithPatterns(text, patterns);
    }

    @Override
    public List<GuardrailScanResult> scanRequest(ChatRequest request, String workspaceId) {
        List<GuardrailScanResult> results = new ArrayList<>();
        if (request.getMessages() == null) {
            return results;
        }
        // Resolved once for the whole request, not once per block.
        List<InjectionPatternRegistry.PatternEntry> patterns = resolvePatterns(workspaceId);
        for (MultimodalMessage msg : request.getMessages()) {
            if (msg.getContent() != null) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ContentBlock.TextBlock tb) {
                        GuardrailScanResult result = scanWithPatterns(tb.text(), patterns);
                        if (result.hasDetections()) {
                            results.add(result);
                        }
                    }
                }
            }
            // scan tool-call arguments (model output replayed next turn) so
            // an injection smuggled into a tool call is caught, not just message text.
            for (String args : GuardrailScanSupport.toolCallArguments(msg)) {
                GuardrailScanResult result = scanWithPatterns(args, patterns);
                if (result.hasDetections()) {
                    results.add(result);
                }
            }
        }
        return results;
    }

    @Override
    public List<GuardrailScanResult> scanResponse(ChatResponse response, String workspaceId) {
        List<GuardrailScanResult> results = new ArrayList<>();
        if (response.getChoices() == null) {
            return results;
        }
        List<InjectionPatternRegistry.PatternEntry> patterns = resolvePatterns(workspaceId);
        for (ChatResponse.Choice choice : response.getChoices()) {
            if (choice.getMessage() == null) continue;
            if (choice.getMessage().getContent() != null) {
                for (ContentBlock block : choice.getMessage().getContent()) {
                    if (block instanceof ContentBlock.TextBlock tb) {
                        GuardrailScanResult result = scanWithPatterns(tb.text(), patterns);
                        if (result.hasDetections()) {
                            results.add(result);
                        }
                    }
                }
            }
            // Symmetric with the request side: a tool call is model output, and an indirect injection
            // that reached the model can come back out in the arguments it asks to be called with.
            for (String args : GuardrailScanSupport.toolCallArguments(choice.getMessage())) {
                GuardrailScanResult result = scanWithPatterns(args, patterns);
                if (result.hasDetections()) {
                    results.add(result);
                }
            }
        }
        return results;
    }

    /**
     * The built-in patterns plus this workspace's own, typed rows first and the metadata map as the
     * bridge — the precedence every other per-workspace setting uses.
     */
    private List<InjectionPatternRegistry.PatternEntry> resolvePatterns(String workspaceId) {
        return patternRegistry.mergeWithCustom(resolveCustomPatterns(workspaceId));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> resolveCustomPatterns(String workspaceId) {
        if (workspaceId == null) {
            return Map.of();
        }
        if (settingEntries != null) {
            Map<String, String> typed = settingEntries.mapOf(workspaceId,
                    WorkspaceSettingEntry.Kind.INJECTION_PATTERN);
            if (typed != null && !typed.isEmpty()) {
                return typed;
            }
        }
        if (workspaceRepository == null) {
            return Map.of();
        }
        Workspace workspace = workspaceRepository.findById(workspaceId).orElse(null);
        if (workspace == null) {
            return Map.of();
        }
        Object raw = workspace.governanceSettings().get("guardrail.injection.custom-patterns");
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String label = entry.getKey().toString().trim();
            String regex = entry.getValue().toString().trim();
            if (!label.isEmpty() && !regex.isEmpty()) {
                out.put(label, regex);
            }
        }
        return out;
    }

    private GuardrailScanResult scanWithPatterns(String text, List<InjectionPatternRegistry.PatternEntry> patterns) {
        if (text == null || text.isEmpty()) {
            return GuardrailScanResult.EMPTY;
        }

        List<GuardrailDetection> detections = new ArrayList<>();

        for (InjectionPatternRegistry.PatternEntry entry : patterns) {
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

        // ML classifier integration
        if (mlClassifierHook != null && mlClassifierHook.isAvailable()) {
            try {
                // Marked as the classifier's on the way in. This is the only place in the build that
                // knows a detection came from a hook rather than a pattern, and the audit and metric
                // paths downstream need to know without recognising vendors by name.
                mlClassifierHook.classify(text)
                        .map(GuardrailDetection::asClassifierDetection)
                        .ifPresent(detections::add);
            } catch (Exception e) {
                log.warn("ML classifier failed: {}", e.getMessage());
            }
        }

        // Deduplicate overlapping detections (keep highest risk score)
        detections = deduplicateDetections(detections);

        if (detections.isEmpty()) {
            return GuardrailScanResult.clean(text);
        }

        return new GuardrailScanResult(detections, text);
    }

    private List<GuardrailDetection> deduplicateDetections(List<GuardrailDetection> detections) {
        if (detections.size() <= 1) {
            return detections;
        }

        // Sort by risk score descending, then by rule ID for determinism
        detections.sort(Comparator.comparingDouble(GuardrailDetection::riskScore).reversed()
                .thenComparing(GuardrailDetection::ruleId));

        List<GuardrailDetection> deduped = new ArrayList<>();
        for (GuardrailDetection detection : detections) {
            boolean isDuplicate = deduped.stream().anyMatch(existing ->
                    existing.matchedText().equals(detection.matchedText()) &&
                    existing.category() == detection.category());
            if (!isDuplicate) {
                deduped.add(detection);
            }
        }
        return deduped;
    }
}