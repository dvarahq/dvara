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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerhoeffValidatorTest {

    // 23412341234 + Verhoeff check digit 6 (verified against the canonical tables).
    private static final String VALID = "234123412346";

    @Test
    void validAadhaar_passes() {
        assertTrue(VerhoeffValidator.isValid(VALID));
    }

    @Test
    void validAadhaar_withSpaces_passes() {
        assertTrue(VerhoeffValidator.isValid("2341 2341 2346"));
        assertTrue(VerhoeffValidator.isValid("2341-2341-2346"));
    }

    @Test
    void wrongCheckDigit_fails() {
        assertFalse(VerhoeffValidator.isValid("234123412347"));
        assertFalse(VerhoeffValidator.isValid("234123412340"));
    }

    @Test
    void transposedDigits_fail() {
        // Verhoeff catches most adjacent transpositions — swap the first two digits.
        assertFalse(VerhoeffValidator.isValid("324123412346"));
    }

    @Test
    void wrongLength_fails() {
        assertFalse(VerhoeffValidator.isValid("23412341234"));    // 11
        assertFalse(VerhoeffValidator.isValid("2341234123466"));  // 13
    }

    @Test
    void leadingZeroOrOne_fails() {
        // UIDAI never issues an Aadhaar starting with 0 or 1.
        assertFalse(VerhoeffValidator.isValid("034123412346"));
        assertFalse(VerhoeffValidator.isValid("134123412346"));
    }

    @Test
    void nonDigits_fail() {
        assertFalse(VerhoeffValidator.isValid("23412341234X"));
        assertFalse(VerhoeffValidator.isValid(""));
    }
}