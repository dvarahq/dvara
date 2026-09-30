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

import com.dvarahq.core.guardrail.GuardrailCategory;
import com.dvarahq.core.guardrail.GuardrailDetection;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects system prompt leakage in LLM responses (OWASP LLM07).
 * Compares response text against the original system prompt to detect
 * if the model has revealed its instructions.
 *
 * <p>Two tests. The ratio catches wholesale regurgitation: 60% of the prompt's 4-grams reappearing.
 * A long prompt leaked a paragraph at a time never approaches that, because the secret sentence is
 * a small fraction of the instruction set, so the verbatim span catches partial disclosure directly:
 * an unbroken run of the prompt's own wording is evidence regardless of what share of the prompt it
 * is.</p>
 *
 * <p>Neither test counts wording the caller supplied in the conversation's user or assistant
 * messages: a reply that restates the format the user asked for is following instructions, not
 * disclosing them. The verbatim span must also be contiguous in the reply, so a reply that answers
 * in the prompt's vocabulary a few words at a time is not a quotation.</p>
 *
 * <p>It needs nothing external: it compares the response against the system prompt the caller
 * already sent. Despite implementing the interface it is not a pluggable detector:
 * {@code GuardrailScanService} (a whole reply) and {@code DefaultStreamingEnforcementEngine} (a streamed
 * reply, once its text is complete) each hold one and call {@link #scanForLeakedPrompt} with what
 * {@link #extractSystemPrompt} and {@link #extractConversationText} read from the request.</p>
 */
public class SystemPromptLeakDetector implements GuardrailDetector {

    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.6;
    private static final int MIN_SYSTEM_PROMPT_LENGTH = 20;
    private static final int MIN_NGRAM_SIZE = 4;

    /**
     * A run of consecutive matching 4-grams long enough to be a quotation rather than a coincidence.
     *
     * <p>Eight consecutive 4-grams is an unbroken span of eleven words. Incidental overlap (shared
     * domain vocabulary, a repeated instruction phrase) produces isolated matches, not runs. Not
     * shorter, because a rule that refuses requests has to be right: a model restating its own
     * purpose can legitimately run eight or nine words of the prompt's wording, and eleven is where
     * quotation stops being plausible as paraphrase.</p>
     *
     * <p>A leaked span shorter than eleven words is caught only if the overall ratio reaches the
     * threshold, so a short secret in a long prompt can still get out.</p>
     */
    private static final int MIN_VERBATIM_NGRAM_RUN = 8;

    private final double similarityThreshold;

    public SystemPromptLeakDetector() {
        this(DEFAULT_SIMILARITY_THRESHOLD);
    }

    public SystemPromptLeakDetector(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }

    @Override
    public GuardrailScanResult scan(String text, String workspaceId) {
        // Single text scan can't detect leaks without system prompt context. Carries the text
        // anyway: the composite joins per-message results on it, so a blank one would not pair with
        // the other detectors' result for this message.
        return GuardrailScanResult.clean(text);
    }

    @Override
    public List<GuardrailScanResult> scanRequest(ChatRequest request, String workspaceId) {
        // Leak detection is response-side only. An empty list, not a list holding an empty result:
        // the composite groups results by source text, so a result with no text is one more thing
        // for it to merge and discard.
        return List.of();
    }

    @Override
    public List<GuardrailScanResult> scanResponse(ChatResponse response, String workspaceId) {
        // This detector needs the original request context which is not available here.
        // Instead, it provides a static method that GuardrailScanService calls directly.
        return List.of();
    }

    /**
     * Scans response text for leaked system prompt content, with no conversation to discount.
     */
    public GuardrailScanResult scanForLeakedPrompt(String systemPrompt, String responseText) {
        return scanForLeakedPrompt(systemPrompt, null, responseText);
    }

    /**
     * Scans response text for leaked system prompt content.
     * Called by GuardrailScanService with the original request context.
     *
     * @param conversationText the request's user and assistant wording (see
     *                         {@link #extractConversationText}); its 4-grams are not secret and do
     *                         not count towards either rule. May be null.
     */
    public GuardrailScanResult scanForLeakedPrompt(String systemPrompt, String conversationText,
                                                   String responseText) {
        if (systemPrompt == null || systemPrompt.length() < MIN_SYSTEM_PROMPT_LENGTH) {
            return GuardrailScanResult.EMPTY;
        }
        if (responseText == null || responseText.isBlank()) {
            return GuardrailScanResult.EMPTY;
        }

        Overlap overlap = computeOverlap(systemPrompt, conversationText, responseText);

        if (overlap.ratio() >= similarityThreshold) {
            return detection("system-prompt-leak", Math.min(1.0, overlap.ratio()), overlap.ratio(),
                    "spl-response-001", responseText);
        }

        // A long system prompt can be leaked a paragraph at a time and never approach the ratio: the
        // secret sentence is a small fraction of the whole instruction set, so the threshold that
        // catches wholesale regurgitation misses the disclosure that matters. A verbatim span is the
        // direct evidence, independent of how much of the prompt it represents.
        if (overlap.longestRun() >= MIN_VERBATIM_NGRAM_RUN) {
            return detection("system-prompt-leak-verbatim-span", 0.9, 0.9,
                    "spl-response-002", responseText);
        }

        return GuardrailScanResult.clean(responseText);
    }

    /** Never carries the leaked text: an audit payload must not become a second copy of the prompt. */
    private static GuardrailScanResult detection(String label, double riskScore, double confidence,
                                                 String ruleId, String responseText) {
        return new GuardrailScanResult(List.of(new GuardrailDetection(
                GuardrailCategory.JAILBREAK,
                label,
                "[system prompt content detected in response]",
                riskScore,
                confidence,
                ruleId)), responseText);
    }

    /**
     * Extracts system prompt text from a ChatRequest.
     */
    public static String extractSystemPrompt(ChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (MultimodalMessage msg : request.getMessages()) {
            if ("system".equals(msg.getRole())) {
                if (msg.getContent() != null) {
                    for (ContentBlock block : msg.getContent()) {
                        if (block instanceof ContentBlock.TextBlock tb) {
                            sb.append(tb.text()).append(" ");
                        }
                    }
                }
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /**
     * Extracts the user and assistant wording from a ChatRequest: text the caller already has, so a
     * reply repeating it discloses nothing.
     */
    public static String extractConversationText(ChatRequest request) {
        if (request == null || request.getMessages() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (MultimodalMessage msg : request.getMessages()) {
            if (("user".equals(msg.getRole()) || "assistant".equals(msg.getRole()))
                    && msg.getContent() != null) {
                for (ContentBlock block : msg.getContent()) {
                    if (block instanceof ContentBlock.TextBlock tb) {
                        sb.append(tb.text()).append(" \n ");
                    }
                }
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    /** How much of the system prompt the response repeats, and whether any of it is contiguous. */
    record Overlap(double ratio, int longestRun) {
    }

    /**
     * Computes word n-gram overlap ratio between system prompt and response.
     * Returns fraction of system prompt n-grams found in the response.
     */
    double computeNgramOverlap(String systemPrompt, String responseText) {
        return computeOverlap(systemPrompt, null, responseText).ratio();
    }

    /**
     * The overlap ratio and the longest unbroken run of matching n-grams.
     *
     * <p>The run is a quotation: consecutive prompt n-grams that are also consecutive in the response.
     * Each n-gram merely appearing somewhere is not enough, or a reply that uses the prompt's phrases
     * in its own order would be stitched into a run it never contains.</p>
     *
     * <p>A prompt n-gram that also occurs in the conversation is public: it is neither counted in the
     * ratio nor allowed to extend a run.</p>
     */
    Overlap computeOverlap(String systemPrompt, String conversationText, String responseText) {
        String[] promptWords = normalizeAndSplit(systemPrompt);
        String[] responseWords = normalizeAndSplit(responseText);

        if (promptWords.length < MIN_NGRAM_SIZE || responseWords.length < MIN_NGRAM_SIZE) {
            return new Overlap(0.0, 0);
        }

        // Where each response n-gram occurs, so a run can be followed position by position.
        Map<String, List<Integer>> responsePositions = new HashMap<>();
        for (int j = 0; j <= responseWords.length - MIN_NGRAM_SIZE; j++) {
            responsePositions.computeIfAbsent(joinNgram(responseWords, j, MIN_NGRAM_SIZE),
                    k -> new java.util.ArrayList<>()).add(j);
        }

        Set<String> publicNgrams = new HashSet<>();
        if (conversationText != null && !conversationText.isBlank()) {
            String[] conversationWords = normalizeAndSplit(conversationText);
            for (int i = 0; i <= conversationWords.length - MIN_NGRAM_SIZE; i++) {
                publicNgrams.add(joinNgram(conversationWords, i, MIN_NGRAM_SIZE));
            }
        }

        int totalPromptNgrams = promptWords.length - MIN_NGRAM_SIZE + 1;
        int matchCount = 0;
        int longestRun = 0;
        // Run length ending at each response position, for the previous prompt n-gram.
        Map<Integer, Integer> previousRuns = Map.of();
        for (int i = 0; i <= promptWords.length - MIN_NGRAM_SIZE; i++) {
            String ngram = joinNgram(promptWords, i, MIN_NGRAM_SIZE);
            List<Integer> positions = responsePositions.get(ngram);
            if (positions == null || publicNgrams.contains(ngram)) {
                previousRuns = Map.of();
                continue;
            }
            matchCount++;
            Map<Integer, Integer> runs = new HashMap<>();
            for (int j : positions) {
                int run = previousRuns.getOrDefault(j - 1, 0) + 1;
                runs.put(j, run);
                longestRun = Math.max(longestRun, run);
            }
            previousRuns = runs;
        }

        return new Overlap((double) matchCount / totalPromptNgrams, longestRun);
    }

    private String[] normalizeAndSplit(String text) {
        return text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ")
                .trim().split("\\s+");
    }

    private String joinNgram(String[] words, int start, int size) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < start + size && i < words.length; i++) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }
}