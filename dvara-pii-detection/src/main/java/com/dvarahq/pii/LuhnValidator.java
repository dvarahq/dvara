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
 * Luhn/mod-10 checksum validator for credit card numbers and NPI numbers.
 */
final class LuhnValidator {

    private LuhnValidator() {}

    /**
     * Validates a number string using the Luhn algorithm.
     *
     * @param number digits-only string (spaces and dashes are stripped)
     * @return true if the number passes Luhn checksum validation
     */
    static boolean isValid(String number) {
        String digits = number.replaceAll("[\\s-]", "");
        if (digits.length() < 2) {
            return false;
        }
        for (char c : digits.toCharArray()) {
            if (c < '0' || c > '9') {
                return false;
            }
        }

        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }
}