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

import com.dvarahq.core.enforcement.ContinuationGroup;
import com.dvarahq.core.enforcement.ContinuationGroupId;
import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.enforcement.Segment;
import com.dvarahq.core.enforcement.SegmentId;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiEntityType;
import com.dvarahq.core.pii.PiiScanResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The composite asks each layer for the whole document, which is what makes the batching seam
 * reachable.
 *
 * <p>{@code PiiDetector.scanDocument} exists so a detector backed by a remote service can answer for a
 * whole response in one call instead of one call per continuation group. The composite is the
 * {@code @Primary} detector, so it is the one the streaming guards call. If it fell back to the
 * interface default, which loops {@code scan} over the groups, a layer's batching override would never
 * be reached, and silently: the results would still be correct, and the only symptom would be N times
 * the network calls.
 */
class CompositeScanDocumentTest {

    /** A layer that records how it was asked, and can answer for the whole document at once. */
    private static final class RecordingLayer implements PiiDetector {
        final List<String> scanCalls = new ArrayList<>();
        int documentCalls = 0;

        @Override
        public PiiScanResult scan(String text, Map<String, String> customPatterns) {
            scanCalls.add(text);
            return PiiScanResult.clean(text);
        }

        @Override
        public List<PiiScanResult> scanDocument(ResponseDocument document,
                                                Map<String, String> customPatterns) {
            documentCalls++;
            return document.groups().stream().map(g -> PiiScanResult.clean(g.text())).toList();
        }
    }

    private static ResponseDocument documentOf(String... texts) {
        List<ContinuationGroup> groups = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            groups.add(new ContinuationGroup(
                    ContinuationGroupId.textStream(),
                    List.of(new Segment(SegmentId.ofText(i), texts[i]))));
        }
        return new ResponseDocument(groups);
    }

    @Test
    void eachLayerIsAskedForTheWholeDocumentOnce_notOncePerGroup() {
        RecordingLayer layer = new RecordingLayer();
        var composite = new CompositePiiDetector(List.of(layer));

        composite.scanDocument(documentOf("alpha", "beta", "gamma"), Map.of());

        assertThat(layer.documentCalls)
                .as("one document call, which is the whole point of the seam")
                .isEqualTo(1);
        assertThat(layer.scanCalls)
                .as("not once per group")
                .isEmpty();
    }

    @Test
    void oneResultPerGroupInOrder_becauseTheCallerIndexesThemAgainstTheGroups() {
        var composite = new CompositePiiDetector(List.of(new RecordingLayer()));

        List<PiiScanResult> results = composite.scanDocument(documentOf("one", "two", "three"), Map.of());

        assertThat(results).hasSize(3);
        assertThat(results.get(0).sourceText()).isEqualTo("one");
        assertThat(results.get(1).sourceText()).isEqualTo("two");
        assertThat(results.get(2).sourceText()).isEqualTo("three");
    }

    @Test
    void entitiesFromEveryLayerLandOnTheRightGroup() {
        // One layer finds something in the second group only; the other finds nothing anywhere.
        PiiDetector finder = new PiiDetector() {
            @Override
            public PiiScanResult scan(String text, Map<String, String> customPatterns) {
                if (!text.contains("@")) {
                    return PiiScanResult.clean(text);
                }
                int at = text.indexOf('@');
                return new PiiScanResult(List.of(new PiiEntity(
                        PiiEntityType.EMAIL, text, 0, text.length(), "email", 0.9)), text);
            }
        };
        var composite = new CompositePiiDetector(List.of(new RecordingLayer(), finder));

        List<PiiScanResult> results =
                composite.scanDocument(documentOf("nothing here", "a@b.com"), Map.of());

        assertThat(results.get(0).hasPii()).isFalse();
        assertThat(results.get(1).hasPii()).isTrue();
        assertThat(results.get(1).entities().get(0).type()).isEqualTo(PiiEntityType.EMAIL);
    }

    @Test
    void aCleanResultCarriesTheTextItScanned() {
        // sourceText is documented as the text that was scanned, and the guardrail twin uses it as a
        // join key. A blank one would make every clean result compare equal to every other.
        var composite = new CompositePiiDetector(List.of(new RecordingLayer()));

        assertThat(composite.scan("nothing to find here", Map.of()).sourceText())
                .isEqualTo("nothing to find here");
    }
}
