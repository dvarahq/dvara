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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Registry of compiled regex patterns for content policy detection: profanity, violence and sexual
 * content, plus workspace-specific competitor keywords, topic restrictions and custom denylists.
 *
 * <p>Risk scores are ordered against the default threshold of 0.7
 * ({@code dvara.llm-gateway.guardrail.risk-score-threshold}). A detection below the threshold is
 * discarded before the audit event is written, not recorded and allowed, so a band below 0.7 means
 * "not reported unless a workspace lowers its threshold". That is the right place for a term that
 * appears in ordinary use and the wrong place for anything an operator needs to see. Profanity
 * therefore has two bands, so a workspace on BLOCK does not refuse "this damn bug".</p>
 *
 * <p>The violence and sexual patterns are single scores, and {@code torture}, {@code mutilate} and
 * {@code dismember} are bare words that also appear in history, medicine and fiction.</p>
 */
public class ContentPatternRegistry {

    private static final char FIELD_SEPARATOR = '\u0000';
    private static final String ENTRY_SEPARATOR = "\u0001";
    private static final String SECTION_SEPARATOR = "\u0002";

    /**
     * Compiled merges of the built-ins with one workspace's content rules, so competitor keywords,
     * topic restrictions and denylist entries are not recompiled on every scan.
     *
     * <p>Keyed off the rules themselves, so an edited rule is a different key and takes effect on the
     * next scan rather than when a TTL expires. Bounded, because the key is derived from
     * workspace-supplied values.</p>
     */
    private final TtlLruCache<List<PatternEntry>> mergedCache = new TtlLruCache<>(128, 3600);

    private final List<PatternEntry> builtInPatterns;

    public ContentPatternRegistry() {
        this.builtInPatterns = buildBuiltInPatterns();
    }

    public List<PatternEntry> getPatterns() {
        return builtInPatterns;
    }

    /**
     * Returns built-in patterns merged with workspace-specific content config.
     */
    public List<PatternEntry> mergeWithWorkspaceConfig(Map<String, Object> workspaceMetadata) {
        return mergeWithWorkspaceConfig(workspaceMetadata, List.of(), List.of());
    }

    /**
     * The same merge, with the two comma-separated lists supplied as typed rows.
     *
     * <p>A non-empty typed list wins over the map's key for that list, and only for that list.
     * Passing the lists in rather than looking them up keeps this class free of a repository it would
     * otherwise need only to re-read what the caller already has.
     */
    public List<PatternEntry> mergeWithWorkspaceConfig(Map<String, Object> workspaceMetadata,
                                                       List<String> typedCompetitorKeywords,
                                                       List<String> typedTopicRestrictions) {
        return mergeWithWorkspaceConfig(workspaceMetadata, typedCompetitorKeywords,
                typedTopicRestrictions, Map.of());
    }

    /** The same merge with the custom denylist supplied as typed rows too. The production path. */
    public List<PatternEntry> mergeWithWorkspaceConfig(Map<String, Object> workspaceMetadata,
                                                       List<String> typedCompetitorKeywords,
                                                       List<String> typedTopicRestrictions,
                                                       Map<String, String> typedDenylist) {
        Map<String, Object> metadata = workspaceMetadata == null ? Map.of() : workspaceMetadata;

        // Resolve each of the three from its typed rows, falling back to its metadata key. Settled
        // first so the cache can be keyed on what will actually be compiled rather than on the whole
        // metadata map, which carries unrelated settings that would evict this for nothing.
        List<String> competitorKeywords = typedCompetitorKeywords.isEmpty()
                ? splitCsv(metadata.get("guardrail.content.competitor.keywords"))
                : typedCompetitorKeywords;
        List<String> topicRestrictions = typedTopicRestrictions.isEmpty()
                ? splitCsv(metadata.get("guardrail.content.topic-restrictions"))
                : typedTopicRestrictions;
        Map<String, String> denylist = typedDenylist.isEmpty()
                ? asStringMap(metadata.get("guardrail.content.custom-denylist"))
                : typedDenylist;

        if (competitorKeywords.isEmpty() && topicRestrictions.isEmpty() && denylist.isEmpty()) {
            return builtInPatterns;
        }

        String key = cacheKey(competitorKeywords, topicRestrictions, denylist);
        List<PatternEntry> cached = mergedCache.get(key);
        if (cached != null) {
            return cached;
        }

        List<PatternEntry> merged = new ArrayList<>(builtInPatterns);
        for (String keyword : competitorKeywords) {
            merged.add(competitorPattern(keyword));
        }
        for (String topic : topicRestrictions) {
            merged.add(topicPattern(topic));
        }
        for (Map.Entry<String, String> entry : denylist.entrySet()) {
            merged.add(new PatternEntry(
                    Pattern.compile(entry.getValue(), Pattern.CASE_INSENSITIVE),
                    GuardrailCategory.CUSTOM,
                    entry.getKey(),
                    0.8,
                    "custom-content-" + entry.getKey()));
        }

        List<PatternEntry> result = Collections.unmodifiableList(merged);
        mergedCache.put(key, result);
        return result;
    }

    /** A comma-separated metadata value as a list, or empty. */
    private static List<String> splitCsv(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : s.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    /**
     * A nested metadata map as label → regex, or empty.
     *
     * <p>Values are rendered with {@link String#valueOf} rather than cast: a denylist written with a
     * non-string value (a number, a nested map) would otherwise reach {@code Pattern.compile} as an
     * {@code Object} and fail with a {@code ClassCastException} on every scan for that workspace.</p>
     */
    private static Map<String, String> asStringMap(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String label = String.valueOf(entry.getKey()).trim();
            String regex = String.valueOf(entry.getValue()).trim();
            if (!label.isEmpty() && !regex.isEmpty()) {
                out.put(label, regex);
            }
        }
        return out;
    }

    /**
     * A stable identity for one workspace's content rules.
     *
     * <p>Three sections with their own separator, so a keyword and a topic of the same name cannot
     * produce the same key; they compile to different categories and risk scores. Delimited with
     * control characters because a label or a regex can contain almost anything printable, and a
     * comma would let two different rule sets collide and serve one workspace's patterns to
     * another.</p>
     */
    private static String cacheKey(List<String> competitorKeywords, List<String> topicRestrictions,
                                   Map<String, String> denylist) {
        return String.join(ENTRY_SEPARATOR, competitorKeywords)
                + SECTION_SEPARATOR
                + String.join(ENTRY_SEPARATOR, topicRestrictions)
                + SECTION_SEPARATOR
                + denylist.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> e.getKey() + FIELD_SEPARATOR + e.getValue())
                        .collect(Collectors.joining(ENTRY_SEPARATOR));
    }

    private static List<PatternEntry> buildBuiltInPatterns() {
        List<PatternEntry> patterns = new ArrayList<>();

        // Profanity patterns (word-boundary anchored), in two bands relative to the 0.7 default
        // threshold: strong terms are refusable at it, and mild ones are reported only by a
        // workspace that lowers its threshold deliberately. Below the threshold a detection is
        // dropped before the audit event, so the mild band is opt-in, not noise, and a workspace on
        // BLOCK does not refuse a request for saying "this damn bug".
        String[] strongProfanity = {"fuck", "shit", "bitch", "asshole", "bastard"};
        for (String word : strongProfanity) {
            patterns.add(profanityPattern(word, 0.8));
        }
        // "dick" sits here rather than above because it is also a given name, and the word-boundary
        // anchor cannot tell Dick from the insult.
        String[] mildProfanity = {"damn", "crap", "piss", "dick"};
        for (String word : mildProfanity) {
            patterns.add(profanityPattern(word, 0.4));
        }

        // Violence patterns
        patterns.add(new PatternEntry(
                Pattern.compile("\\b(kill|murder|assassinate|slaughter)\\s+(him|her|them|everyone|people)\\b",
                        Pattern.CASE_INSENSITIVE),
                GuardrailCategory.VIOLENCE,
                "violence-direct-threat",
                0.9,
                "viol-001"));
        patterns.add(new PatternEntry(
                Pattern.compile("\\bhow\\s+to\\s+(make|build|create)\\s+(a\\s+)?(bomb|weapon|explosive)\\b",
                        Pattern.CASE_INSENSITIVE),
                GuardrailCategory.VIOLENCE,
                "violence-weapon-instructions",
                0.95,
                "viol-002"));
        patterns.add(new PatternEntry(
                Pattern.compile("\\b(torture|mutilate|dismember)\\b", Pattern.CASE_INSENSITIVE),
                GuardrailCategory.VIOLENCE,
                "violence-graphic",
                0.85,
                "viol-003"));

        // Sexual content patterns
        patterns.add(new PatternEntry(
                Pattern.compile("\\b(explicit|pornographic|sexually\\s+explicit)\\s+(content|material|imagery)\\b",
                        Pattern.CASE_INSENSITIVE),
                GuardrailCategory.SEXUAL,
                "sexual-explicit-content",
                0.9,
                "sex-001"));
        patterns.add(new PatternEntry(
                Pattern.compile("\\bgenerate\\s+(nude|naked|nsfw)\\b", Pattern.CASE_INSENSITIVE),
                GuardrailCategory.SEXUAL,
                "sexual-generate-nsfw",
                0.9,
                "sex-002"));

        return Collections.unmodifiableList(patterns);
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

    private static PatternEntry profanityPattern(String word, double riskScore) {
        return new PatternEntry(
                Pattern.compile("\\b" + word + "\\b", Pattern.CASE_INSENSITIVE),
                GuardrailCategory.PROFANITY,
                "profanity-" + word,
                riskScore,
                "prof-" + word);
    }

    /** Shared by the typed and the map path, so a keyword cannot mean two things by storage location. */
    private static PatternEntry competitorPattern(String keyword) {
        return new PatternEntry(
                Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE),
                GuardrailCategory.COMPETITOR_MENTION,
                "competitor-" + keyword.toLowerCase(),
                0.8,
                "comp-" + keyword.toLowerCase());
    }

    private static PatternEntry topicPattern(String topic) {
        return new PatternEntry(
                Pattern.compile("\\b" + Pattern.quote(topic) + "\\b", Pattern.CASE_INSENSITIVE),
                GuardrailCategory.TOPIC_RESTRICTION,
                "topic-" + topic.toLowerCase(),
                0.7,
                "topic-" + topic.toLowerCase());
    }
}