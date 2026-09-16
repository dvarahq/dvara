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
package com.dvarahq.core.guardrail;

import java.util.List;

/**
 * Result of scanning text for guardrail violations.
 *
 * @param detections detected guardrail violations
 * @param sourceText the text that was scanned
 */
public record GuardrailScanResult(List<GuardrailDetection> detections, String sourceText) {

    /**
     * No detections and no source text — for a scan that was never performed, because there was
     * nothing to scan.
     *
     * <p><b>Not for a clean scan of real text.</b> {@code sourceText} is the join key the composite
     * detector uses to pair each detector's per-message results, so blank ones from several clean
     * messages would merge into one. Use {@link #clean(String)}.
     */
    public static final GuardrailScanResult EMPTY = new GuardrailScanResult(List.of(), "");

    /** A scan that ran over {@code text} and found nothing. */
    public static GuardrailScanResult clean(String text) {
        return new GuardrailScanResult(List.of(), text == null ? "" : text);
    }

    public boolean hasDetections() {
        return !detections.isEmpty();
    }

    public int detectionCount() {
        return detections.size();
    }
}