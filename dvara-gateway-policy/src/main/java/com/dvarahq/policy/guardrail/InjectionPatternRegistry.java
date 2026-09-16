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
import com.dvarahq.core.util.TtlLruCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Registry of compiled regex patterns for jailbreak and prompt injection detection, with built-in
 * patterns plus support for workspace-specific custom patterns.
 *
 * <p>This layer is tuned for precision, not recall. Recall belongs to a classifier (the
 * {@code MlClassifierHook} seam), which weighs a whole prompt instead of matching a phrase. A regex
 * that fires on ordinary use refuses legitimate requests, which is what makes an operator switch
 * guardrails off entirely.</p>
 *
 * <p>Risk scores are ordered against the default threshold of 0.7
 * ({@code dvara.llm-gateway.guardrail.risk-score-threshold}). At or above it means "this phrase does
 * not appear in ordinary use, and a workspace on BLOCK should refuse it"; below it, a detection is
 * discarded before anything is written. Every pattern here is at or above 0.7: a phrase too common
 * to refuse on is tightened until it is specific, not scored down into silence.</p>
 */
public class InjectionPatternRegistry {

    private static final char FIELD_SEPARATOR = '\u0000';
    private static final String ENTRY_SEPARATOR = "\u0001";

    /**
     * Compiled merges of the built-ins with one workspace's custom patterns.
     *
     * <p>Bounded and short-lived. The key is derived from the pattern set itself, so an edited set is
     * a different key and a config change applies on the next scan rather than after the TTL
     * expires.</p>
     */
    private final TtlLruCache<List<PatternEntry>> mergedCache = new TtlLruCache<>(128, 3600);

    private final List<PatternEntry> builtInPatterns;

    public InjectionPatternRegistry() {
        this.builtInPatterns = buildBuiltInPatterns();
    }

    public List<PatternEntry> getPatterns() {
        return builtInPatterns;
    }

    /**
     * Returns built-in patterns merged with workspace-specific custom patterns, compiled once per
     * distinct set.
     *
     * <p>Only successful compilations are cached, and a malformed regex throws on every scan:
     * skipping the bad pattern would turn a loud failure into a silently weakened control.</p>
     */
    public List<PatternEntry> mergeWithCustom(Map<String, String> customPatterns) {
        if (customPatterns == null || customPatterns.isEmpty()) {
            return builtInPatterns;
        }
        String key = cacheKey(customPatterns);
        List<PatternEntry> cached = mergedCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<PatternEntry> merged = new ArrayList<>(builtInPatterns);
        for (Map.Entry<String, String> entry : customPatterns.entrySet()) {
            merged.add(new PatternEntry(
                    Pattern.compile(entry.getValue(), Pattern.CASE_INSENSITIVE),
                    GuardrailCategory.INJECTION,
                    entry.getKey(),
                    0.8,
                    "custom-" + entry.getKey()));
        }
        List<PatternEntry> result = Collections.unmodifiableList(merged);
        mergedCache.put(key, result);
        return result;
    }

    /**
     * A stable identity for a pattern set.
     *
     * <p>Sorted so two equal maps with different iteration orders share an entry, and delimited with
     * control characters because a label or a regex can contain almost anything printable — a comma
     * would let {@code {"a,b": "x"}} and {@code {"a": "b,x"}} collide, which would serve one
     * workspace's patterns to another.</p>
     */
    private static String cacheKey(Map<String, String> customPatterns) {
        return customPatterns.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + FIELD_SEPARATOR + e.getValue())
                .collect(Collectors.joining(ENTRY_SEPARATOR));
    }

    private static List<PatternEntry> buildBuiltInPatterns() {
        List<PatternEntry> patterns = new ArrayList<>();

        // Jailbreak patterns
        patterns.add(pattern("ignore\\s+(all\\s+)?previous\\s+instructions",
                GuardrailCategory.JAILBREAK, "ignore-previous-instructions", 0.95, "jb-001"));
        // "pretend you are" alone is how many legitimate role-play prompts begin ("pretend you are a
        // patient tutor"), so the bare phrase would be a refusal under BLOCK. What makes it an attack
        // is what follows: a different model, no model at all, or an unconstrained persona.
        patterns.add(pattern("pretend\\s+(?:that\\s+)?you\\s+are\\s+(?:not\\s+|no\\s+longer\\s+)?"
                        + "(?:an?\\s+|the\\s+)?(?:ai|chatgpt|claude|gpt|dan|human|person|sentient|"
                        + "unrestricted|uncensored|unfiltered|unlimited|jailbroken|evil|amoral|"
                        + "language\\s+model)\\b",
                GuardrailCategory.JAILBREAK, "pretend-you-are", 0.85, "jb-002"));
        patterns.add(pattern("act\\s+as\\s+DAN",
                GuardrailCategory.JAILBREAK, "act-as-dan", 0.95, "jb-003"));
        patterns.add(pattern("do\\s+anything\\s+now",
                GuardrailCategory.JAILBREAK, "do-anything-now", 0.9, "jb-004"));
        patterns.add(pattern("developer\\s+mode\\s+(enabled|activated|on)",
                GuardrailCategory.JAILBREAK, "developer-mode", 0.9, "jb-005"));
        // The bare phrase "you are now" appears in ordinary prose ("you are now ready to deploy").
        // The attack is a persona or mode reassignment, so the pattern requires one.
        patterns.add(pattern("you\\s+are\\s+now\\s+(?:in\\s+)?(?:an?\\s+|the\\s+)?(?:\\w+\\s+){0,2}"
                        + "(?:mode|dan|ai|assistant|bot|model|persona|character|admin|root|developer|"
                        + "unrestricted|uncensored|unfiltered|unlimited|jailbroken|evil|amoral)\\b",
                GuardrailCategory.JAILBREAK, "you-are-now", 0.8, "jb-006"));
        patterns.add(pattern("new\\s+instructions\\s*:",
                GuardrailCategory.JAILBREAK, "new-instructions", 0.85, "jb-007"));
        // The bare word would fire on any mention: a security team asking how to test for
        // jailbreaks, a changelog. Asking about the concept is not attempting it, so the pattern
        // needs an attempt.
        patterns.add(pattern("(?:how\\s+to\\s+jailbreak\\b"
                        + "|\\b(?:jailbreak|jailbreaking|jailbroken)\\s+"
                        + "(?:you|yourself|this|the\\s+(?:ai|model|assistant|bot)|"
                        + "your\\s+(?:rules|restrictions|guidelines))\\b)",
                GuardrailCategory.JAILBREAK, "jailbreak-attempt", 0.8, "jb-008"));
        patterns.add(pattern("bypass\\s+(all\\s+)?restrictions",
                GuardrailCategory.JAILBREAK, "bypass-restrictions", 0.9, "jb-009"));
        patterns.add(pattern("unlimited\\s+mode",
                GuardrailCategory.JAILBREAK, "unlimited-mode", 0.85, "jb-010"));

        // Prompt injection patterns
        patterns.add(pattern("disregard\\s+(the\\s+)?above",
                GuardrailCategory.INJECTION, "disregard-above", 0.9, "inj-001"));
        patterns.add(pattern("forget\\s+everything\\s+(above|before|previously)",
                GuardrailCategory.INJECTION, "forget-everything", 0.9, "inj-002"));
        patterns.add(pattern("override\\s+(your\\s+)?safety",
                GuardrailCategory.INJECTION, "override-safety", 0.95, "inj-003"));
        patterns.add(pattern("bypass\\s+(your\\s+)?filters",
                GuardrailCategory.INJECTION, "bypass-filters", 0.9, "inj-004"));
        patterns.add(pattern("\\[INST\\]",
                GuardrailCategory.INJECTION, "inst-delimiter", 0.85, "inj-005"));
        patterns.add(pattern("<<SYS>>",
                GuardrailCategory.INJECTION, "sys-delimiter", 0.85, "inj-006"));
        patterns.add(pattern("```\\s*(system|instruction)",
                GuardrailCategory.INJECTION, "code-block-injection", 0.8, "inj-007"));
        patterns.add(pattern("IMPORTANT\\s*:\\s*new\\s+task",
                GuardrailCategory.INJECTION, "important-new-task", 0.85, "inj-008"));
        patterns.add(pattern("system\\s*:\\s*you\\s+are",
                GuardrailCategory.INJECTION, "system-role-injection", 0.9, "inj-009"));

        // Indirect injection patterns
        patterns.add(pattern("instructions\\s+for\\s+the\\s+AI",
                GuardrailCategory.INJECTION, "instructions-for-ai", 0.8, "ind-001"));
        patterns.add(pattern("when\\s+you\\s+see\\s+this",
                GuardrailCategory.INJECTION, "when-you-see-this", 0.75, "ind-002"));
        patterns.add(pattern("[\\u200B\\u200C\\u200D\\uFEFF]",
                GuardrailCategory.INJECTION, "zero-width-chars", 0.7, "ind-003"));
        patterns.add(pattern("ignore\\s+all\\s+safety\\s+guidelines",
                GuardrailCategory.INJECTION, "ignore-safety-guidelines", 0.95, "inj-010"));

        // System prompt leakage / extraction attempts (OWASP LLM07)
        patterns.add(pattern("(repeat|print|show|display|output|reveal|tell)\\s+(me\\s+)?(your|the)\\s+(system\\s+prompt|instructions|initial\\s+prompt|rules)",
                GuardrailCategory.JAILBREAK, "system-prompt-extraction", 0.9, "spl-001"));
        patterns.add(pattern("what\\s+(are|were)\\s+your\\s+(original\\s+)?instructions",
                GuardrailCategory.JAILBREAK, "what-are-instructions", 0.85, "spl-002"));
        patterns.add(pattern("output\\s+everything\\s+(above|before)",
                GuardrailCategory.JAILBREAK, "output-everything-above", 0.9, "spl-003"));
        patterns.add(pattern("repeat\\s+the\\s+text\\s+above",
                GuardrailCategory.JAILBREAK, "repeat-text-above", 0.85, "spl-004"));
        patterns.add(pattern("what\\s+were\\s+you\\s+told",
                GuardrailCategory.JAILBREAK, "what-were-you-told", 0.8, "spl-005"));
        patterns.add(pattern("(ignore|disregard).*output\\s+(your\\s+)?(initial|original|system)\\s+prompt",
                GuardrailCategory.JAILBREAK, "ignore-output-prompt", 0.95, "spl-006"));
        patterns.add(pattern("(translate|convert|encode)\\s+your\\s+(system\\s+)?prompt",
                GuardrailCategory.JAILBREAK, "encode-system-prompt", 0.85, "spl-007"));
        patterns.add(pattern("(give|provide)\\s+me\\s+(your\\s+)?(complete\\s+)?system\\s+(message|prompt)",
                GuardrailCategory.JAILBREAK, "give-system-prompt", 0.9, "spl-008"));

        return Collections.unmodifiableList(patterns);
    }

    private static PatternEntry pattern(String regex, GuardrailCategory category,
                                         String label, double riskScore, String ruleId) {
        return new PatternEntry(
                Pattern.compile(regex, Pattern.CASE_INSENSITIVE),
                category, label, riskScore, ruleId);
    }

    /**
     * A compiled pattern entry with metadata.
     */
    public record PatternEntry(
            Pattern pattern,
            GuardrailCategory category,
            String label,
            double riskScore,
            String ruleId) {
    }
}