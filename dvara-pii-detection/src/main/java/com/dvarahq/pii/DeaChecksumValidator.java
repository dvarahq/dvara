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
 * DEA (Drug Enforcement Administration) number checksum validator.
 *
 * <p>DEA number format: 2 letters + 7 digits. The check digit (7th digit) equals
 * (sum of digits at odd positions + 2 * sum of digits at even positions) mod 10.</p>
 */
final class DeaChecksumValidator {

    private DeaChecksumValidator() {}

    /**
     * Validates a DEA number's checksum.
     *
     * @param dea the DEA number string (e.g. "AB1234563")
     * @return true if the checksum is valid
     */
    static boolean isValid(String dea) {
        if (dea == null || dea.length() != 9) {
            return false;
        }

        // First two characters must be letters
        if (!Character.isLetter(dea.charAt(0)) || !Character.isLetter(dea.charAt(1))) {
            return false;
        }

        // Remaining 7 characters must be digits
        for (int i = 2; i < 9; i++) {
            if (!Character.isDigit(dea.charAt(i))) {
                return false;
            }
        }

        int oddSum = 0;
        int evenSum = 0;
        for (int i = 2; i < 8; i++) {
            int digit = dea.charAt(i) - '0';
            if ((i - 2) % 2 == 0) {
                oddSum += digit;
            } else {
                evenSum += digit;
            }
        }

        int checkDigit = (oddSum + 2 * evenSum) % 10;
        return checkDigit == (dea.charAt(8) - '0');
    }
}