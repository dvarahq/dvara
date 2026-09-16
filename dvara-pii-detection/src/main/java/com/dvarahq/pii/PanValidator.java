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
 * Format validator for an India PAN (Permanent Account Number).
 *
 * <p>A PAN is {@code AAAAA9999A}: five letters, four digits, one letter. It has no checksum, so
 * this checks the shape and that the fourth character is a known holder-type code, which trims
 * false positives from any ten characters of the right shape.
 */
final class PanValidator {

    private PanValidator() {}

    /**
     * Known holder-type codes for the 4th character of a PAN:
     * P=Individual, C=Company, H=HUF, F=Firm, A=AOP, T=Trust, B=Body of
     * individuals, L=Local authority, J=Artificial juridical person,
     * G=Government.
     */
    private static final String HOLDER_TYPES = "PCHFATBLJG";

    /**
     * Validates the PAN format.
     *
     * @param pan candidate string (already matched by the PAN regex); validated
     *            here as 5 letters + 4 digits + 1 letter with a recognised
     *            holder-type 4th character.
     * @return true if the structure and holder-type code are valid
     */
    static boolean isValid(String pan) {
        if (pan == null || pan.length() != 10) {
            return false;
        }
        String p = pan.toUpperCase();
        for (int i = 0; i < 5; i++) {
            if (!Character.isLetter(p.charAt(i))) {
                return false;
            }
        }
        for (int i = 5; i < 9; i++) {
            if (!Character.isDigit(p.charAt(i))) {
                return false;
            }
        }
        if (!Character.isLetter(p.charAt(9))) {
            return false;
        }
        return HOLDER_TYPES.indexOf(p.charAt(3)) >= 0;
    }
}