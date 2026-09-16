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
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiEntityType;
import com.dvarahq.core.pii.PiiScanResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompositePiiDetectorTest {


    @Test
    void mergesResultsFromMultipleDetectors() {
        PiiDetector d1 = mock(PiiDetector.class);
        PiiDetector d2 = mock(PiiDetector.class);

        when(d1.scan(any(), any())).thenReturn(new PiiScanResult(
                List.of(new PiiEntity(PiiEntityType.EMAIL, "a@b.com", 0, 7, "email", 0.95)),
                "a@b.com John"));
        when(d2.scan(any(), any())).thenReturn(new PiiScanResult(
                List.of(new PiiEntity(PiiEntityType.PERSON_NAME, "John", 8, 12, "ner:person", 0.9)),
                "a@b.com John"));

        var composite = new CompositePiiDetector(List.of(d1, d2));
        PiiScanResult result = composite.scan("a@b.com John", Map.of());

        assertThat(result.entityCount()).isEqualTo(2);
        assertThat(result.entities()).extracting(PiiEntity::type)
                .containsExactly(PiiEntityType.EMAIL, PiiEntityType.PERSON_NAME);
    }

    @Test
    void deduplicatesOverlappingSpans_keepsHigherConfidence() {
        PiiDetector d1 = mock(PiiDetector.class);
        PiiDetector d2 = mock(PiiDetector.class);

        // Both detect same span, d2 has higher confidence
        when(d1.scan(any(), any())).thenReturn(new PiiScanResult(
                List.of(new PiiEntity(PiiEntityType.PERSON_NAME, "John", 0, 4, "regex", 0.7)),
                "John Smith"));
        when(d2.scan(any(), any())).thenReturn(new PiiScanResult(
                List.of(new PiiEntity(PiiEntityType.PERSON_NAME, "John", 0, 4, "ner:person", 0.95)),
                "John Smith"));

        var composite = new CompositePiiDetector(List.of(d1, d2));
        PiiScanResult result = composite.scan("John Smith", Map.of());

        assertThat(result.entityCount()).isEqualTo(1);
        assertThat(result.entities().get(0).confidence()).isEqualTo(0.95);
    }

    @Test
    void singleDetectorPassthrough() {
        PiiDetector d1 = mock(PiiDetector.class);
        when(d1.scan(any(), any())).thenReturn(new PiiScanResult(
                List.of(new PiiEntity(PiiEntityType.SSN, "123-45-6789", 0, 11, "ssn", 0.9)),
                "123-45-6789"));

        var composite = new CompositePiiDetector(List.of(d1));
        PiiScanResult result = composite.scan("123-45-6789", Map.of());

        assertThat(result.entityCount()).isEqualTo(1);
    }

    @Test
    void noDetections_returnsEmpty() {
        PiiDetector d1 = mock(PiiDetector.class);
        PiiDetector d2 = mock(PiiDetector.class);
        when(d1.scan(any(), any())).thenReturn(PiiScanResult.EMPTY);
        when(d2.scan(any(), any())).thenReturn(PiiScanResult.EMPTY);

        var composite = new CompositePiiDetector(List.of(d1, d2));
        assertThat(composite.scan("safe text", Map.of()).hasPii()).isFalse();
    }

    @Test
    void deduplicateOverlapping_partialOverlap_keepsHigherConfidence() {
        var entities = List.of(
                new PiiEntity(PiiEntityType.PERSON_NAME, "John Smith", 0, 10, "ner", 0.95),
                new PiiEntity(PiiEntityType.PERSON_NAME, "Smith", 5, 10, "regex", 0.7)
        );

        var result = PiiDetectorSupport.deduplicateOverlapping(entities);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).confidence()).isEqualTo(0.95);
    }

    // The case above cannot tell the two rules apart: its higher-confidence entity also starts
    // first, so it passes whether the winner is chosen by confidence or by position. These three
    // separate them.

    @Test
    void deduplicateOverlapping_higherConfidenceStartingLater_stillWins() {
        var entities = List.of(
                new PiiEntity(PiiEntityType.PERSON_NAME, "Robin C", 5, 12, "ner", 0.60),
                new PiiEntity(PiiEntityType.CREDIT_CARD, "4111111111111111", 10, 26, "regex", 1.0)
        );

        var result = PiiDetectorSupport.deduplicateOverlapping(entities);

        assertThat(result).singleElement()
                .extracting(PiiEntity::type).isEqualTo(PiiEntityType.CREDIT_CARD);
    }

    @Test
    void deduplicateOverlapping_equalConfidence_keepsTheLongerSpan() {
        var entities = List.of(
                new PiiEntity(PiiEntityType.CUSTOM, "short", 4, 9, "a", 0.8),
                new PiiEntity(PiiEntityType.CUSTOM, "the longer one", 0, 14, "b", 0.8)
        );

        var result = PiiDetectorSupport.deduplicateOverlapping(entities);

        assertThat(result).singleElement()
                .extracting(PiiEntity::end).isEqualTo(14);
    }

    @Test
    void deduplicateOverlapping_returnsEntitiesInStartOrder() {
        var entities = List.of(
                new PiiEntity(PiiEntityType.EMAIL, "b@x.io", 40, 46, "regex", 0.9),
                new PiiEntity(PiiEntityType.PHONE_NUMBER, "555-0100", 10, 18, "regex", 1.0),
                new PiiEntity(PiiEntityType.PERSON_NAME, "Robin", 0, 5, "ner", 0.5)
        );

        var result = PiiDetectorSupport.deduplicateOverlapping(entities);

        assertThat(result).extracting(PiiEntity::start).containsExactly(0, 10, 40);
    }
}