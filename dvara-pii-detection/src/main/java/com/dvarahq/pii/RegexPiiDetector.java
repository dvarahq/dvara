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
package com.dvarahq.pii;

import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiEntityType;
import com.dvarahq.core.pii.PiiScanResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/**
 * Regex-based PII detector. A match for a type that carries a checksum or a fixed format is kept
 * only if it validates: Luhn for cards and NPI numbers, the DEA checksum, Verhoeff for Aadhaar, and
 * the PAN structure.
 */
public class RegexPiiDetector implements PiiDetector {

    private final PiiPatternRegistry patternRegistry;

    /** Takes the pattern registry and nothing else: a detector finds PII and needs no key material. */
    public RegexPiiDetector(PiiPatternRegistry patternRegistry) {
        this.patternRegistry = patternRegistry;
    }

    @Override
    public PiiScanResult scan(String text, Map<String, String> customPatterns) {
        if (text == null || text.isEmpty()) {
            return PiiScanResult.EMPTY;
        }

        var patterns = patternRegistry.mergeWithCustom(customPatterns);
        var entities = new ArrayList<PiiEntity>();

        for (var entry : patterns) {
            Matcher matcher = entry.pattern().matcher(text);
            while (matcher.find()) {
                String matched = matcher.group();

                // A match for a checksummed or fixed-format type must validate or it is dropped.
                if (entry.type() == PiiEntityType.CREDIT_CARD && !LuhnValidator.isValid(matched)) {
                    continue;
                }
                if (entry.type() == PiiEntityType.DEA_NUMBER && !DeaChecksumValidator.isValid(matched)) {
                    continue;
                }
                if (entry.type() == PiiEntityType.NPI_NUMBER) {
                    String digits = matched.replaceAll("\\D", "");
                    if (!LuhnValidator.isValid(digits)) {
                        continue;
                    }
                }
                if (entry.type() == PiiEntityType.AADHAAR && !VerhoeffValidator.isValid(matched)) {
                    continue;
                }
                if (entry.type() == PiiEntityType.PAN && !PanValidator.isValid(matched)) {
                    continue;
                }

                entities.add(new PiiEntity(
                        entry.type(),
                        matched,
                        matcher.start(),
                        matcher.end(),
                        entry.label(),
                        entry.confidence()
                ));
            }
        }

        var deduplicated = PiiDetectorSupport.deduplicateOverlapping(entities);
        deduplicated.sort(Comparator.comparingInt(PiiEntity::start));

        return new PiiScanResult(List.copyOf(deduplicated), text);
    }

}