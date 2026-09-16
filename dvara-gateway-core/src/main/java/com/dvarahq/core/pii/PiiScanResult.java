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
package com.dvarahq.core.pii;

import java.util.List;

/**
 * Result of scanning a single text segment for PII entities.
 *
 * @param entities   detected PII entities, sorted by start offset
 * @param sourceText the text that was scanned
 */
public record PiiScanResult(List<PiiEntity> entities, String sourceText) {

    /**
     * No entities and no source text — for a scan that was never performed, because there was
     * nothing to scan.
     *
     * <p><b>Not for a clean scan of real text.</b> {@code sourceText} is documented as the text that
     * was scanned, and returning this from a scan that found nothing makes that field a lie on the
     * path taken by most requests. Use {@link #clean(String)}, which keeps the field honest.
     *
     * <p>It matters because the sibling {@code GuardrailScanResult} is joined on this field: the
     * guardrail composite pairs each detector's per-message results by source text, so every blank
     * one would collapse into a single entry.
     */
    public static final PiiScanResult EMPTY = new PiiScanResult(List.of(), "");

    /** A scan that ran over {@code text} and found nothing. */
    public static PiiScanResult clean(String text) {
        return new PiiScanResult(List.of(), text == null ? "" : text);
    }

    public boolean hasPii() {
        return !entities.isEmpty();
    }

    public int entityCount() {
        return entities.size();
    }
}