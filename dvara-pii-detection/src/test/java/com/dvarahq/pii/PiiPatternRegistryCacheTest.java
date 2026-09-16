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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.PatternSyntaxException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Custom patterns are compiled once per distinct set, not once per scan.
 *
 * <p>These tests check identity rather than timing: the same pattern set must hand back the same
 * compiled list, which only happens if nothing recompiled. A changed set must not serve the old
 * one, and two workspaces' sets must not collide.
 */
class PiiPatternRegistryCacheTest {

    private final PiiPatternRegistry registry = new PiiPatternRegistry();

    @Test
    void theSamePatternSetIsCompiledOnce() {
        Map<String, String> custom = Map.of("EMPLOYEE_ID", "\\bEMP-\\d{6}\\b");

        List<PiiPatternRegistry.PatternEntry> first = registry.mergeWithCustom(custom);
        List<PiiPatternRegistry.PatternEntry> second = registry.mergeWithCustom(custom);

        // The same list instance, so the second call did no work.
        assertThat(second).isSameAs(first);
    }

    @Test
    void anEqualSetInADifferentOrderHitsTheSameEntry() {
        // Workspace metadata round-trips through JSON and a Map, so iteration order is not stable
        // and the cache key must not depend on it.
        Map<String, String> a = new LinkedHashMap<>();
        a.put("ALPHA", "a\\d+");
        a.put("BETA", "b\\d+");
        Map<String, String> b = new LinkedHashMap<>();
        b.put("BETA", "b\\d+");
        b.put("ALPHA", "a\\d+");

        assertThat(registry.mergeWithCustom(b)).isSameAs(registry.mergeWithCustom(a));
    }

    @Test
    void anEditedPatternSetTakesEffectImmediately() {
        // The cache is keyed on the patterns themselves, not on a workspace id, so an edited set can
        // never be served the previous rules.
        List<PiiPatternRegistry.PatternEntry> before =
                registry.mergeWithCustom(Map.of("BADGE", "\\bB-\\d{4}\\b"));
        List<PiiPatternRegistry.PatternEntry> after =
                registry.mergeWithCustom(Map.of("BADGE", "\\bB-\\d{6}\\b"));

        assertThat(after).isNotSameAs(before);
        assertThat(lastLabelled(after, "BADGE")).isEqualTo("\\bB-\\d{6}\\b");
    }

    @Test
    void twoPatternSetsThatWouldCollideUnderNaiveDelimitingDoNot() {
        // {"a,b": "x"} and {"a": "b,x"} would both flatten to "a,b,x" if joined on a comma, serving
        // one workspace's patterns to another. The cache key uses control characters as delimiters.
        List<PiiPatternRegistry.PatternEntry> first = registry.mergeWithCustom(Map.of("a,b", "x"));
        List<PiiPatternRegistry.PatternEntry> second = registry.mergeWithCustom(Map.of("a", "b,x"));

        assertThat(second).isNotSameAs(first);
        assertThat(lastLabelled(first, "a,b")).isEqualTo("x");
        assertThat(lastLabelled(second, "a")).isEqualTo("b,x");
    }

    @Test
    void aMalformedPatternStillThrows() {
        // A broken pattern fails loudly rather than leaving the control running without one of its
        // rules, and only successful compilations are cached, so it cannot be served from cache later.
        assertThatThrownBy(() -> registry.mergeWithCustom(Map.of("BROKEN", "[unclosed")))
                .isInstanceOf(PatternSyntaxException.class);

        assertThatThrownBy(() -> registry.mergeWithCustom(Map.of("BROKEN", "[unclosed")))
                .as("a second call must not be served from cache either")
                .isInstanceOf(PatternSyntaxException.class);
    }

    @Test
    void noCustomPatternsStillReturnsTheSharedBuiltInList() {
        assertThat(registry.mergeWithCustom(null)).isSameAs(registry.builtInPatterns());
        assertThat(registry.mergeWithCustom(Map.of())).isSameAs(registry.builtInPatterns());
    }

    private static String lastLabelled(List<PiiPatternRegistry.PatternEntry> entries, String label) {
        return entries.stream()
                .filter(e -> label.equals(e.label()))
                .map(e -> e.pattern().pattern())
                .reduce((a, b) -> b)
                .orElseThrow();
    }
}