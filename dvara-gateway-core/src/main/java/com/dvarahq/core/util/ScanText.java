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

/**
 * One text about to be scanned by many regular expressions, with the cheap facts about it that
 * {@link RegexPrecheck}s ask, each worked out once however many patterns ask it.
 *
 * <p>Not thread-safe; a scan makes one for each text it scans and drops it.
 */
public final class ScanText {

    private final String text;
    private String asciiLower;
    private int hasDigit = -1;

    private ScanText(String text) {
        this.text = text;
    }

    public static ScanText of(String text) {
        return new ScanText(text);
    }

    public String text() {
        return text;
    }

    /**
     * The text with A-Z turned to a-z and every other character left alone. That is exactly the case
     * folding {@code Pattern.CASE_INSENSITIVE} does without {@code UNICODE_CASE}, so a word a pattern
     * matches in any case is in this string in lower case.
     */
    public String asciiLower() {
        if (asciiLower == null) {
            asciiLower = lowerAscii(text);
        }
        return asciiLower;
    }

    /** Whether any character is 0-9, the characters {@code \d} matches without {@code UNICODE_CHARACTER_CLASS}. */
    public boolean hasAsciiDigit() {
        if (hasDigit < 0) {
            hasDigit = 0;
            for (int i = 0, n = text.length(); i < n; i++) {
                char c = text.charAt(i);
                if (c >= '0' && c <= '9') {
                    hasDigit = 1;
                    break;
                }
            }
        }
        return hasDigit == 1;
    }

    static String lowerAscii(String s) {
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                break;
            }
            i++;
        }
        if (i == n) {
            return s;
        }
        char[] out = s.toCharArray();
        for (; i < n; i++) {
            char c = out[i];
            if (c >= 'A' && c <= 'Z') {
                out[i] = (char) (c + ('a' - 'A'));
            }
        }
        return new String(out);
    }
}
