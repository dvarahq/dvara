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
import com.dvarahq.core.enforcement.ContinuationGroup;
import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Composite guardrail detector that wraps multiple detectors (injection + content)
 * and merges their results with deduplication.
 */
public class CompositeGuardrailDetector implements GuardrailDetector {

    private final List<GuardrailDetector> detectors;

    public CompositeGuardrailDetector(List<GuardrailDetector> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    @Override
    public GuardrailScanResult scan(String text, String workspaceId) {
        List<GuardrailDetection> allDetections = new ArrayList<>();
        for (GuardrailDetector detector : detectors) {
            GuardrailScanResult result = detector.scan(text, workspaceId);
            if (result.hasDetections()) {
                allDetections.addAll(result.detections());
            }
        }
        return mergeResults(allDetections, text);
    }

    /**
     * Asks each detector for the whole document, then merges per group.
     *
     * <p>Overridden rather than inherited: the interface default loops {@link #scan} over the
     * groups, which would call each detector once per group and never reach one that batches its
     * own {@code scanDocument}. This composite is the one the guards call.</p>
     *
     * <p>One result per group, in order; the caller indexes them against the groups.</p>
     */
    @Override
    public List<GuardrailScanResult> scanDocument(ResponseDocument document, String workspaceId) {
        List<ContinuationGroup> groups = document.groups();
        List<List<GuardrailScanResult>> perDetector = new ArrayList<>(detectors.size());
        for (GuardrailDetector detector : detectors) {
            perDetector.add(detector.scanDocument(document, workspaceId));
        }

        List<GuardrailScanResult> merged = new ArrayList<>(groups.size());
        for (int g = 0; g < groups.size(); g++) {
            String text = groups.get(g).text();
            List<GuardrailDetection> detections = new ArrayList<>();
            for (List<GuardrailScanResult> perGroup : perDetector) {
                GuardrailScanResult result = g < perGroup.size() ? perGroup.get(g) : null;
                if (result != null && result.hasDetections()) {
                    detections.addAll(result.detections());
                }
            }
            merged.add(mergeResults(detections, text));
        }
        return merged;
    }

    @Override
    public List<GuardrailScanResult> scanRequest(ChatRequest request, String workspaceId) {
        List<GuardrailScanResult> allResults = new ArrayList<>();
        for (GuardrailDetector detector : detectors) {
            allResults.addAll(detector.scanRequest(request, workspaceId));
        }
        return deduplicateResults(allResults);
    }

    @Override
    public List<GuardrailScanResult> scanResponse(ChatResponse response, String workspaceId) {
        List<GuardrailScanResult> allResults = new ArrayList<>();
        for (GuardrailDetector detector : detectors) {
            allResults.addAll(detector.scanResponse(response, workspaceId));
        }
        return deduplicateResults(allResults);
    }

    private GuardrailScanResult mergeResults(List<GuardrailDetection> detections, String text) {
        if (detections.isEmpty()) {
            return GuardrailScanResult.clean(text);
        }
        List<GuardrailDetection> deduped = deduplicateDetections(detections);
        return new GuardrailScanResult(deduped, text);
    }

    /**
     * Pairs each detector's per-message results back together.
     *
     * <p>Every detector returns one result per scanned text, and this concatenation has to be folded
     * back into one result per text, so the source text is the join key. That is why a clean scan
     * must carry the text it scanned: a blank one would compare equal to every other blank one.
     *
     * <p>Inherent to joining on text: two messages whose text is byte-identical merge into one
     * result. Their detections are identical too, so no finding is lost. Joining on position
     * instead would need every detector to return the same number of results in the same order,
     * which the interface does not promise.
     */
    private List<GuardrailScanResult> deduplicateResults(List<GuardrailScanResult> results) {
        if (results.isEmpty()) return results;

        // Group by source text, merge detections
        List<GuardrailScanResult> merged = new ArrayList<>();
        for (GuardrailScanResult result : results) {
            boolean found = false;
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).sourceText().equals(result.sourceText())) {
                    List<GuardrailDetection> combined = new ArrayList<>(merged.get(i).detections());
                    combined.addAll(result.detections());
                    merged.set(i, new GuardrailScanResult(deduplicateDetections(combined),
                            result.sourceText()));
                    found = true;
                    break;
                }
            }
            if (!found) {
                merged.add(result);
            }
        }
        return merged;
    }

    private List<GuardrailDetection> deduplicateDetections(List<GuardrailDetection> detections) {
        if (detections.size() <= 1) return detections;

        detections.sort(Comparator.comparingDouble(GuardrailDetection::riskScore).reversed()
                .thenComparing(GuardrailDetection::ruleId));

        List<GuardrailDetection> deduped = new ArrayList<>();
        for (GuardrailDetection detection : detections) {
            boolean isDuplicate = deduped.stream().anyMatch(existing ->
                    existing.matchedText().equals(detection.matchedText()) &&
                    existing.category() == detection.category() &&
                    existing.ruleId().equals(detection.ruleId()));
            if (!isDuplicate) {
                deduped.add(detection);
            }
        }
        return deduped;
    }
}