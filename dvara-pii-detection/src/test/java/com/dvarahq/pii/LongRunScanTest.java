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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scan must cost time in proportion to the text, whatever the text holds.
 *
 * <p>The email pattern used to try every start position inside a run of letters or digits and, with no
 * {@code @} in the run, walk to its end and back from each one: a run of n characters cost about n²/2 steps.
 * 16 KB of {@code a} took two seconds, and the scan runs on every request with PII enabled.
 */
class LongRunScanTest {

    private static final int LENGTH = 64 * 1024;

    /** The pattern as it was, kept here only to prove the new one finds the same things. */
    private static final Pattern PREVIOUS_EMAIL =
            Pattern.compile("[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}");

    private final RegexPiiDetector detector = new RegexPiiDetector(new PiiPatternRegistry());

    @ParameterizedTest
    @ValueSource(strings = {"a", "1234567890", "a.", "a_b-c+d%", "a@", "a@b.", "x.y@"})
    void aLongRunIsScannedInLinearTime(String unit) {
        String text = unit.repeat(LENGTH / unit.length());

        long started = System.nanoTime();
        detector.scan(text, Map.of());
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).as("scanning 64 KB of \"%s\"", unit).isLessThan(1_000);
    }

    @Test
    void theEmailPatternFindsExactlyWhatItFoundBefore() {
        Pattern current = new PiiPatternRegistry().builtInPatterns().stream()
                .filter(p -> "email".equals(p.label()))
                .findFirst().orElseThrow()
                .pattern();
        // Built from short pieces, whole addresses among them, so every edge meets often: runs, separators,
        // a dot before a short or long top-level domain, two addresses joined by a local-part character.
        // Random single characters were tried first, and almost never produced the joined case, so a
        // pattern that dropped it passed.
        String[] pieces = {"a", "ab", "1", ".", "_", "-", "%", "@", " ", ",", "a@b.ab", "@b.c", ".abc"};
        Random random = new Random(20260922L);
        for (int i = 0; i < 20_000; i++) {
            StringBuilder text = new StringBuilder();
            int count = 1 + random.nextInt(10);
            for (int j = 0; j < count; j++) {
                text.append(pieces[random.nextInt(pieces.length)]);
            }
            String s = text.toString();
            assertThat(matches(current, s)).as("matches in \"%s\"", s).isEqualTo(matches(PREVIOUS_EMAIL, s));
        }
    }

    @Test
    void twoAddressesJoinedByALocalPartCharacterAreBothFound() {
        String s = "a@b.comx_y@c.com";
        Pattern current = new PiiPatternRegistry().builtInPatterns().stream()
                .filter(p -> "email".equals(p.label())).findFirst().orElseThrow().pattern();
        assertThat(matches(current, s)).isEqualTo(matches(PREVIOUS_EMAIL, s)).hasSize(2);
    }

    private static List<String> matches(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(m.start() + ":" + m.group());
        }
        return found;
    }
}
