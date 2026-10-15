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
package com.dvarahq.core.util;

import java.util.List;

/**
 * A test far cheaper than the regular expression it guards, which a text must pass before that
 * expression can match it: a word the expression cannot match without, or a digit.
 *
 * <p>Built-in detection patterns are scanned over every request's text, and most texts match none
 * of them. A full regex pass per pattern costs about 5 µs per KB; a substring search costs a small
 * fraction of that. So a pattern whose precheck fails is skipped.
 *
 * <p>A precheck may let through a text the expression then rejects. It must never stop a text the
 * expression would match, or the control silently stops detecting. So a precheck names only what the
 * expression requires on every path through it, and a pattern supplied at run time, whose shape is
 * not known, gets {@link #ALWAYS}.
 */
@FunctionalInterface
public interface RegexPrecheck {

    boolean admits(ScanText text);

    /** No precheck: the expression always runs. */
    RegexPrecheck ALWAYS = text -> true;

    /**
     * The text contains one of these words in any ASCII case, as an expression compiled with
     * {@code Pattern.CASE_INSENSITIVE} (and not {@code UNICODE_CASE}) compares them.
     *
     * @param words lower-case, each one a substring every match of the expression contains
     */
    static RegexPrecheck containsAnyIgnoreCase(String... words) {
        List<String> required = List.of(words);
        for (String word : required) {
            if (word.isEmpty() || !word.equals(ScanText.lowerAscii(word))) {
                throw new IllegalArgumentException("A precheck word must be non-empty and lower case: '" + word + "'");
            }
        }
        return text -> {
            String lower = text.asciiLower();
            for (String word : required) {
                if (lower.contains(word)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** The text contains one of these strings exactly, for an expression that is case-sensitive. */
    static RegexPrecheck containsAny(String... literals) {
        List<String> required = List.of(literals);
        for (String literal : required) {
            if (literal.isEmpty()) {
                throw new IllegalArgumentException("A precheck literal must be non-empty");
            }
        }
        return text -> {
            String s = text.text();
            for (String literal : required) {
                if (s.contains(literal)) {
                    return true;
                }
            }
            return false;
        };
    }

    /** The text contains 0-9, for an expression that cannot match without {@code \d}. */
    static RegexPrecheck containsDigit() {
        return ScanText::hasAsciiDigit;
    }
}
