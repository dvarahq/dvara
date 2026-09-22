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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every built-in guardrail pattern costs time in proportion to the text, whatever the text repeats.
 *
 * <p>A pattern that tries every start position and, from each, walks to the end of the text before failing
 * costs the square of the text. The texts below repeat the first word of a pattern with nothing after it,
 * which is the shape that exposes one.
 */
class GuardrailLongRunScanTest {

    private static final int LENGTH = 64 * 1024;
    private static final long LIMIT_MS = 1_000;

    @ParameterizedTest
    @ValueSource(strings = {"a", " ", "1", "ignore ", "disregard ", "you are now ", "pretend you are ",
            "how to ", "kill ", "system: ", "jailbreak ", "repeat ", "a b\n"})
    void everyBuiltInPatternScansALongRepetitionInLinearTime(String unit) {
        String text = unit.repeat(LENGTH / unit.length());
        List<String> slow = new ArrayList<>();
        for (var entry : new InjectionPatternRegistry().getPatterns()) {
            time(entry.ruleId(), entry.pattern(), text, slow);
        }
        for (var entry : new ContentPatternRegistry().getPatterns()) {
            time(entry.ruleId(), entry.pattern(), text, slow);
        }
        assertThat(slow).as("patterns over %d ms on 64 KB of \"%s\"", LIMIT_MS, unit).isEmpty();
    }

    private static void time(String id, Pattern pattern, String text, List<String> slow) {
        long started = System.nanoTime();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            // count every match, as a scan does
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        if (millis > LIMIT_MS) {
            slow.add(id + " " + millis + " ms");
        }
    }
}
