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

/**
 * Verhoeff checksum validator for India Aadhaar numbers.
 *
 * <p>Aadhaar is a 12-digit number whose last digit is a Verhoeff check digit
 * computed over the preceding 11. The Verhoeff algorithm catches all
 * single-digit and most adjacent-transposition errors, so validating it cuts
 * the false-positive rate of "any 12 consecutive digits" dramatically.</p>
 */
final class VerhoeffValidator {

    private VerhoeffValidator() {}

    // Multiplication table (D5 dihedral group).
    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}
    };

    // Permutation table.
    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}
    };

    /**
     * Validates a candidate Aadhaar number.
     *
     * @param number a string; spaces and dashes are stripped. Must be exactly
     *               12 digits after stripping. The first digit of a real Aadhaar
     *               is never 0 or 1, which is also enforced.
     * @return true if the trailing Verhoeff check digit is valid
     */
    static boolean isValid(String number) {
        String digits = number.replaceAll("[\\s-]", "");
        if (digits.length() != 12) {
            return false;
        }
        for (char ch : digits.toCharArray()) {
            if (ch < '0' || ch > '9') {
                return false;
            }
        }
        // UIDAI never issues an Aadhaar starting with 0 or 1.
        if (digits.charAt(0) == '0' || digits.charAt(0) == '1') {
            return false;
        }

        int c = 0;
        // Process digits right-to-left; position 0 is the check digit itself.
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            c = D[c][P[i % 8][digit]];
        }
        return c == 0;
    }
}