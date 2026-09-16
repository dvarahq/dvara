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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ContentPatternRegistryTest {

    private final ContentPatternRegistry registry = new ContentPatternRegistry();

    @Test
    void builtInPatterns_hasExpectedCategories() {
        var patterns = registry.getPatterns();
        assertThat(patterns).isNotEmpty();
        assertThat(patterns).anyMatch(p -> p.category() == GuardrailCategory.PROFANITY);
        assertThat(patterns).anyMatch(p -> p.category() == GuardrailCategory.VIOLENCE);
        assertThat(patterns).anyMatch(p -> p.category() == GuardrailCategory.SEXUAL);
    }

    @Test
    void profanity_matchesBadWords() {
        assertMatchesCategory(GuardrailCategory.PROFANITY, "you damn fool");
    }

    @Test
    void profanity_noFalsePositive_benignText() {
        assertNoMatch(GuardrailCategory.PROFANITY, "What is the weather today?");
    }

    @Test
    void violence_matchesViolentContent() {
        assertMatchesCategory(GuardrailCategory.VIOLENCE, "I want to kill them all");
    }

    @Test
    void mergeWithWorkspaceConfig_addsCompetitorKeywords() {
        Map<String, Object> workspaceMeta = Map.of(
                "guardrail.content.competitor.keywords", "CompetitorA,CompetitorB");
        var merged = registry.mergeWithWorkspaceConfig(workspaceMeta);
        assertThat(merged.size()).isGreaterThan(registry.getPatterns().size());
        assertThat(merged).anyMatch(p -> p.category() == GuardrailCategory.COMPETITOR_MENTION);
    }

    @Test
    void mergeWithWorkspaceConfig_addsTopicRestrictions() {
        Map<String, Object> workspaceMeta = Map.of(
                "guardrail.content.topic-restrictions", "politics,religion");
        var merged = registry.mergeWithWorkspaceConfig(workspaceMeta);
        assertThat(merged).anyMatch(p -> p.category() == GuardrailCategory.TOPIC_RESTRICTION);
    }

    @Test
    void mergeWithWorkspaceConfig_addsCustomDenylist() {
        Map<String, Object> workspaceMeta = Map.of(
                "guardrail.content.custom-denylist", Map.of("banned-word", "forbidden\\s+topic"));
        var merged = registry.mergeWithWorkspaceConfig(workspaceMeta);
        assertThat(merged).anyMatch(p -> p.category() == GuardrailCategory.CUSTOM);
    }

    private void assertMatchesCategory(GuardrailCategory category, String text) {
        boolean matched = registry.getPatterns().stream()
                .filter(p -> p.category() == category)
                .anyMatch(p -> p.pattern().matcher(text).find());
        assertThat(matched).as("Expected %s match for: %s", category, text).isTrue();
    }

    private void assertNoMatch(GuardrailCategory category, String text) {
        boolean matched = registry.getPatterns().stream()
                .filter(p -> p.category() == category)
                .anyMatch(p -> p.pattern().matcher(text).find());
        assertThat(matched).as("Expected no %s match for: %s", category, text).isFalse();
    }

    // ---------------------------------------------------------------------------------------------
    // One workspace's rules are compiled once, not on every scan: a scan runs twice per request and
    // once per continuation group on a stream.
    // ---------------------------------------------------------------------------------------------

    @Test
    void theSameRulesAreCompiledOnce() {
        ContentPatternRegistry registry = new ContentPatternRegistry();
        Map<String, Object> metadata = Map.of(
                "guardrail.content.competitor.keywords", "acme, globex",
                "guardrail.content.custom-denylist", Map.of("codename", "bluebird"));

        var first = registry.mergeWithWorkspaceConfig(metadata);
        var second = registry.mergeWithWorkspaceConfig(metadata);

        assertThat(second).isSameAs(first);
    }

    @Test
    void anEditedRuleIsRecompiledRatherThanWaitingForATtl() {
        ContentPatternRegistry registry = new ContentPatternRegistry();

        var before = registry.mergeWithWorkspaceConfig(
                Map.of("guardrail.content.competitor.keywords", "acme"));
        var after = registry.mergeWithWorkspaceConfig(
                Map.of("guardrail.content.competitor.keywords", "acme, globex"));

        assertThat(after).isNotSameAs(before).hasSizeGreaterThan(before.size());
        assertThat(after).anyMatch(e -> "competitor-globex".equals(e.label()));
    }

    @Test
    void typedRowsAndTheEquivalentMapShareNothingByAccident() {
        // A keyword and a topic of the same name compile to different categories, so they must not
        // produce the same cache key.
        ContentPatternRegistry registry = new ContentPatternRegistry();

        var asKeyword = registry.mergeWithWorkspaceConfig(Map.of(), List.of("acme"), List.of(), Map.of());
        var asTopic = registry.mergeWithWorkspaceConfig(Map.of(), List.of(), List.of("acme"), Map.of());

        assertThat(asKeyword).anyMatch(e -> e.category() == GuardrailCategory.COMPETITOR_MENTION);
        assertThat(asTopic).anyMatch(e -> e.category() == GuardrailCategory.TOPIC_RESTRICTION);
        assertThat(asTopic).noneMatch(e -> e.category() == GuardrailCategory.COMPETITOR_MENTION);
    }

    @Test
    void noRulesAtAllReturnsTheSharedBuiltInList() {
        ContentPatternRegistry registry = new ContentPatternRegistry();

        assertThat(registry.mergeWithWorkspaceConfig(Map.of("unrelated.setting", "x")))
                .isSameAs(registry.getPatterns());
    }

    @Test
    void aDenylistValueThatIsNotAStringDoesNotBlowUpTheScan() {
        // A number here must be stringified before Pattern.compile, or every scan for that workspace
        // would fail with a ClassCastException.
        ContentPatternRegistry registry = new ContentPatternRegistry();

        var patterns = registry.mergeWithWorkspaceConfig(
                Map.of("guardrail.content.custom-denylist", Map.of("numeric", 42)));

        assertThat(patterns).anyMatch(e -> "numeric".equals(e.label()));
    }

    // ---------------------------------------------------------------------------------------------
    // Profanity is banded, so the risk threshold has something to separate.
    // ---------------------------------------------------------------------------------------------

    @Test
    void mildProfanityScoresBelowTheDefaultThreshold() {
        // 0.7 is the default risk-score threshold, so these are recorded and not refused.
        assertThat(scoreOf("prof-damn")).isLessThan(0.7);
        assertThat(scoreOf("prof-crap")).isLessThan(0.7);
        assertThat(scoreOf("prof-piss")).isLessThan(0.7);
        assertThat(scoreOf("prof-dick")).isLessThan(0.7);
    }

    @Test
    void strongProfanityScoresAtOrAboveTheDefaultThreshold() {
        assertThat(scoreOf("prof-fuck")).isGreaterThanOrEqualTo(0.7);
        assertThat(scoreOf("prof-shit")).isGreaterThanOrEqualTo(0.7);
        assertThat(scoreOf("prof-bitch")).isGreaterThanOrEqualTo(0.7);
        assertThat(scoreOf("prof-asshole")).isGreaterThanOrEqualTo(0.7);
        assertThat(scoreOf("prof-bastard")).isGreaterThanOrEqualTo(0.7);
    }

    @Test
    void everyProfanityTermIsStillDetected() {
        // Banding changes what a workspace on BLOCK refuses, not what the scan finds.
        for (String word : List.of("fuck", "shit", "damn", "bitch", "asshole", "bastard", "crap",
                "dick", "piss")) {
            assertThat(registry.getPatterns())
                    .as("term %s must still be detected", word)
                    .anyMatch(p -> ("prof-" + word).equals(p.ruleId()));
        }
    }

    private double scoreOf(String ruleId) {
        return registry.getPatterns().stream()
                .filter(p -> ruleId.equals(p.ruleId()))
                .findFirst().orElseThrow(() -> new AssertionError("no pattern with rule id " + ruleId))
                .riskScore();
    }
}
