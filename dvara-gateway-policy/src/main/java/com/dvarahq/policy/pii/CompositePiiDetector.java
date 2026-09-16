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
package com.dvarahq.policy.pii;

import com.dvarahq.pii.PiiDetectorSupport;
import com.dvarahq.core.enforcement.ContinuationGroup;
import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiScanResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Composite PII detector that merges results from several layers, such as the regex detector and
 * an NER detector. Deduplicates overlapping entity spans, keeping the higher-confidence detection.
 *
 * <p>Detection answers "where is the personal data in this text". What is then done with those
 * spans is the enforcement layer's decision, so this class holds no token store or key material.</p>
 */
public class CompositePiiDetector implements PiiDetector {

    private final List<PiiDetector> detectors;

    public CompositePiiDetector(List<PiiDetector> detectors) {
        this.detectors = List.copyOf(detectors);
    }

    @Override
    public PiiScanResult scan(String text, Map<String, String> customPatterns) {
        if (text == null || text.isEmpty()) {
            return PiiScanResult.EMPTY;
        }

        var allEntities = new ArrayList<PiiEntity>();
        for (PiiDetector detector : detectors) {
            PiiScanResult result = detector.scan(text, customPatterns);
            if (result.hasPii()) {
                allEntities.addAll(result.entities());
            }
        }

        if (allEntities.isEmpty()) {
            return PiiScanResult.clean(text);
        }

        var deduplicated = PiiDetectorSupport.deduplicateOverlapping(allEntities);
        deduplicated.sort(Comparator.comparingInt(PiiEntity::start));
        return new PiiScanResult(List.copyOf(deduplicated), text);
    }

    /**
     * Asks each layer for the whole document, then merges per group.
     *
     * <p>Overridden rather than inherited: the interface default loops {@link #scan} over the
     * groups, which would call each layer once per group and never reach a layer that batches its
     * own {@code scanDocument}. The composite is the {@code @Primary} detector the guards call, so
     * batching has to pass through here.</p>
     *
     * <p>One result per group, in order, as the contract requires. A layer returning a shorter list
     * contributes nothing for the missing groups rather than shifting the rest.</p>
     */
    @Override
    public List<PiiScanResult> scanDocument(ResponseDocument document,
                                             Map<String, String> customPatterns) {
        List<ContinuationGroup> groups = document.groups();
        List<List<PiiScanResult>> perLayer = new ArrayList<>(detectors.size());
        for (PiiDetector detector : detectors) {
            perLayer.add(detector.scanDocument(document, customPatterns));
        }

        List<PiiScanResult> merged = new ArrayList<>(groups.size());
        for (int g = 0; g < groups.size(); g++) {
            String text = groups.get(g).text();
            var entities = new ArrayList<PiiEntity>();
            for (List<PiiScanResult> layer : perLayer) {
                PiiScanResult result = g < layer.size() ? layer.get(g) : null;
                if (result != null && result.hasPii()) {
                    entities.addAll(result.entities());
                }
            }
            if (entities.isEmpty()) {
                merged.add(PiiScanResult.clean(text));
                continue;
            }
            var deduplicated = PiiDetectorSupport.deduplicateOverlapping(entities);
            deduplicated.sort(Comparator.comparingInt(PiiEntity::start));
            merged.add(new PiiScanResult(List.copyOf(deduplicated), text));
        }
        return merged;
    }

}