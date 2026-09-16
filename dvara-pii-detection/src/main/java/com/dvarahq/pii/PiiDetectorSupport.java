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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiScanResult;
import com.dvarahq.core.pii.PiiScans;

import java.util.List;
import java.util.Map;

/**
 * Shared utilities for PII detector implementations.
 */
public final class PiiDetectorSupport {

    private PiiDetectorSupport() {}

    public static String extractText(ContentBlock block) {
        return PiiScans.extractText(block);
    }

    public static String redact(String text, List<PiiEntity> entities) {
        return PiiScans.redact(text, entities);
    }

    public static List<PiiScanResult> scanRequest(PiiDetector detector, ChatRequest request,
                                             Map<String, String> customPatterns) {
        return PiiScans.scanRequest(detector, request, customPatterns);
    }

    public static List<PiiScanResult> scanResponse(PiiDetector detector, ChatResponse response,
                                              Map<String, String> customPatterns) {
        return PiiScans.scanResponse(detector, response, customPatterns);
    }

    public static List<PiiEntity> deduplicateOverlapping(List<PiiEntity> entities) {
        return PiiScans.deduplicateOverlapping(entities);
    }
}
