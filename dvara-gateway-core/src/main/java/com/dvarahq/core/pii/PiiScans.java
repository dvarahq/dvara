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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The parts of scanning that do not depend on how a detector finds entities: walking a request or a
 * response, and replacing what was found.
 *
 * <p>It lives here so {@link PiiDetector} can carry them as defaults. The composite asks a layered
 * contribution only to {@link PiiDetector#scan}; the walk is done once, here.
 *
 * <p>{@code com.dvarahq.pii.PiiDetectorSupport} delegates here and keeps its published signatures.
 */
public final class PiiScans {

    private PiiScans() {}

    /** The text of a content block, or null for a block that carries none. */
    public static String extractText(ContentBlock block) {
        if (block instanceof ContentBlock.TextBlock tb) {
            return tb.text();
        }
        // A tool result is a `tool`-role message whose output is a text block, so the branch above
        // already returns it. There is no separate tool-result block kind.
        return null;
    }

    /**
     * Replace every detected value with an irreversible placeholder.
     *
     * <p>Nothing is written anywhere and the original cannot be recovered — minting a reversible
     * token is {@link PiiTokenizationService}'s job, and it needs key material precisely because it
     * stores something. Entities are applied back-to-front so replacing one does not move the offsets
     * of the next.</p>
     *
     * <p><b>That holds only for spans that do not overlap, which is what callers must pass.</b> Two
     * overlapping spans cut into each other's placeholder whichever end you start from, so the text
     * comes back malformed and part of a value can survive. Every detector here runs
     * {@link #deduplicateOverlapping} before returning.</p>
     */
    public static String redact(String text, List<PiiEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return text;
        }
        var sorted = new ArrayList<>(entities);
        sorted.sort(Comparator.comparingInt(PiiEntity::start).reversed());

        StringBuilder sb = new StringBuilder(text);
        for (PiiEntity entity : sorted) {
            sb.replace(entity.start(), entity.end(), placeholderFor(entity));
        }
        return sb.toString();
    }

    /**
     * {@code [REDACTED_EMAIL]}, {@code [REDACTED_SSN]}, and so on.
     *
     * <p>The type is named rather than replaced with a uniform marker because a downstream model reads
     * the prompt: telling it a phone number stood here keeps the sentence meaningful, where one opaque
     * marker for everything loses the shape of what was said. A {@code CUSTOM} entity carries the
     * operator's own label, which is what makes their pattern legible in the output.</p>
     */
    private static String placeholderFor(PiiEntity entity) {
        String label = entity.type() == null ? "PII" : entity.type().name();
        if (entity.type() == PiiEntityType.CUSTOM && entity.label() != null && !entity.label().isBlank()) {
            label = entity.label().trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        }
        return "[REDACTED_" + label + "]";
    }

    /** Every text in a request worth scanning: each message's blocks, and each tool call's arguments. */
    public static List<PiiScanResult> scanRequest(PiiDetector detector, ChatRequest request,
                                                  Map<String, String> customPatterns) {
        if (request == null || request.getMessages() == null) {
            return List.of();
        }
        var results = new ArrayList<PiiScanResult>();
        for (var message : request.getMessages()) {
            if (message.getContent() != null) {
                for (var block : message.getContent()) {
                    addIfFound(results, detector, extractText(block), customPatterns);
                }
            }
            // A tool call's arguments — model output echoed back on the next turn — can carry PII,
            // e.g. send_email({"body":"SSN 123-45-6789"}). Scanning them is what keeps governance from
            // having a function-calling blind spot.
            if (message.getToolCalls() != null) {
                for (var toolCall : message.getToolCalls()) {
                    addIfFound(results, detector, toolCall.getArguments(), customPatterns);
                }
            }
        }
        return results;
    }

    /** The same for a response, including a turn that IS a tool call and has no content at all. */
    public static List<PiiScanResult> scanResponse(PiiDetector detector, ChatResponse response,
                                                   Map<String, String> customPatterns) {
        if (response == null || response.getChoices() == null) {
            return List.of();
        }
        var results = new ArrayList<PiiScanResult>();
        for (var choice : response.getChoices()) {
            if (choice.getMessage() == null) continue;
            if (choice.getMessage().getContent() != null) {
                for (var block : choice.getMessage().getContent()) {
                    addIfFound(results, detector, extractText(block), customPatterns);
                }
            }
            // A model-generated tool call's arguments are as capable of carrying personal data as its
            // prose, and a turn that is only a tool call has null content, so this must not be
            // skipped when content is null.
            if (choice.getMessage().getToolCalls() != null) {
                for (var toolCall : choice.getMessage().getToolCalls()) {
                    addIfFound(results, detector, toolCall.getArguments(), customPatterns);
                }
            }
        }
        return results;
    }

    private static void addIfFound(List<PiiScanResult> results, PiiDetector detector,
                                   String text, Map<String, String> customPatterns) {
        if (text == null || text.isEmpty()) {
            return;
        }
        PiiScanResult result = detector.scan(text, customPatterns);
        if (result.hasPii()) {
            results.add(result);
        }
    }

    /**
     * Overlapping spans collapse to one, the higher confidence winning.
     *
     * <p><b>Confidence decides, not position.</b> Settling a staggered overlap by whichever span
     * begins earlier would let a low-confidence {@code PERSON_NAME} displace a checksum-validated
     * {@code CREDIT_CARD} that starts a few characters later, and there is only one deduplication
     * pass, so nothing downstream would recover it.</p>
     *
     * <p>Equal confidence is settled by the <b>longer</b> span: with nothing to choose between two
     * claims, covering more of the text is the safer direction. Start order settles the rest, so the
     * result does not depend on the order the layers ran in.</p>
     *
     * <p>Returned in ascending start order.</p>
     */
    public static List<PiiEntity> deduplicateOverlapping(List<PiiEntity> entities) {
        if (entities.size() <= 1) {
            return new ArrayList<>(entities);
        }
        var byPreference = new ArrayList<>(entities);
        byPreference.sort(Comparator.comparingDouble(PiiEntity::confidence).reversed()
                .thenComparing(Comparator.comparingInt((PiiEntity e) -> e.end() - e.start()).reversed())
                .thenComparingInt(PiiEntity::start));

        var kept = new ArrayList<PiiEntity>();
        for (PiiEntity candidate : byPreference) {
            boolean overlaps = false;
            for (PiiEntity existing : kept) {
                if (candidate.start() < existing.end() && candidate.end() > existing.start()) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                kept.add(candidate);
            }
        }
        kept.sort(Comparator.comparingInt(PiiEntity::start));
        return kept;
    }
}
