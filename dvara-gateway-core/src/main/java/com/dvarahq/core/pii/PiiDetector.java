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

import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

import java.util.List;
import java.util.Map;

/**
 * Detects PII/PHI entities in text, chat requests, and chat responses.
 *
 * <p>The implementation is a composite: an always-present pattern-and-checksum detector, plus any
 * {@link AdditionalPiiDetector} contributions, deduplicated. It is {@code @Primary}, and this
 * interface is <b>not</b> a replacement hook — a build that registers a plain {@code PiiDetector}
 * does not swap the composite out, because the regex layer is not optional. Add a layer by
 * registering an {@link AdditionalPiiDetector}.</p>
 */
public interface PiiDetector {

    PiiScanResult scan(String text, Map<String, String> customPatterns);

    /**
     * Scan a whole response in one call, evaluating each continuation group independently.
     *
     * <p>The streaming guards invoke each enabled detector at most once per response. A document
     * can hold many groups (an A2A reply carries messages, artifacts and status updates), and the
     * default loops {@link #scan} over them. An implementation backed by a remote service overrides
     * this to batch, so one logical scan is one round trip.</p>
     *
     * <p>Returns one result per group, in order. Offsets are group-relative.</p>
     */
    default List<PiiScanResult> scanDocument(ResponseDocument document,
                                             Map<String, String> customPatterns) {
        return document.groups().stream()
                .map(group -> scan(group.text(), customPatterns))
                .toList();
    }

    /**
     * Every text in a request worth scanning, each scanned with {@link #scan}.
     *
     * <p>The walk is not the part a detector should be free to vary: getting it wrong means a
     * detector that silently examines less of the request than the others. Override it only to scan
     * something this one does not know about.</p>
     */
    default List<PiiScanResult> scanRequest(ChatRequest request, Map<String, String> customPatterns) {
        return PiiScans.scanRequest(this, request, customPatterns);
    }

    /** The same for a response, including a turn that is a tool call and carries no content. */
    default List<PiiScanResult> scanResponse(ChatResponse response, Map<String, String> customPatterns) {
        return PiiScans.scanResponse(this, response, customPatterns);
    }

    /**
     * Replaces each detected span with an irreversible placeholder. Redaction stores nothing, so it
     * needs no workspace; minting a reversible token is {@link PiiTokenizationService}'s job.
     */
    default String redact(String text, List<PiiEntity> entities) {
        return PiiScans.redact(text, entities);
    }
}