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

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The prechecks fold case exactly as {@code Pattern.CASE_INSENSITIVE} does without
 * {@code UNICODE_CASE}, ASCII only, and count only the digits {@code \d} matches.
 */
class RegexPrecheckTest {

    @Test
    void caseIsFoldedForAsciiLettersOnly() {
        assertThat(ScanText.of("IGNORE Previous").asciiLower()).isEqualTo("ignore previous");
        // U+0130 and U+212A lower-case to "i" and "k" under Unicode rules. The regex flag does not fold
        // them, so neither may the precheck: it would admit what the pattern rejects, harmless, but the
        // point is to fold the same way.
        assertThat(ScanText.of("\u0130\u212a").asciiLower()).isEqualTo("\u0130\u212a");
        String unchanged = "already lower";
        assertThat(ScanText.of(unchanged).asciiLower()).isSameAs(unchanged);
    }

    @Test
    void aWordIsFoundInAnyAsciiCase_asTheCaseInsensitivePatternFindsIt() {
        RegexPrecheck precheck = RegexPrecheck.containsAnyIgnoreCase("ignore", "disregard");
        Pattern pattern = Pattern.compile("ignore|disregard", Pattern.CASE_INSENSITIVE);
        for (String text : new String[]{"IgNoRe me", "DISREGARD", "nothing here", "\u0130gnore"}) {
            assertThat(precheck.admits(ScanText.of(text))).as(text).isEqualTo(pattern.matcher(text).find());
        }
    }

    @Test
    void aDigitIsWhatBackslashDMatches() {
        assertThat(RegexPrecheck.containsDigit().admits(ScanText.of("no digits"))).isFalse();
        assertThat(RegexPrecheck.containsDigit().admits(ScanText.of("room 7"))).isTrue();
        // Arabic-Indic digits are not \d without UNICODE_CHARACTER_CLASS.
        assertThat(Pattern.compile("\\d").matcher("\u0661\u0662").find()).isFalse();
        assertThat(RegexPrecheck.containsDigit().admits(ScanText.of("\u0661\u0662"))).isFalse();
    }

    @Test
    void anExactLiteralIsCaseSensitive() {
        RegexPrecheck precheck = RegexPrecheck.containsAny("Dr");
        assertThat(precheck.admits(ScanText.of("ask Dr. Lee"))).isTrue();
        assertThat(precheck.admits(ScanText.of("a dry day"))).isFalse();
    }

    @Test
    void aWordMustBeLowerCase_soAMistypedPrecheckFailsAtStartup() {
        assertThatThrownBy(() -> RegexPrecheck.containsAnyIgnoreCase("Ignore"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RegexPrecheck.containsAnyIgnoreCase(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
