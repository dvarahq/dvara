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

class PanValidatorTest {

    @Test
    void validPan_passes() {
        // AAAAA9999A with holder-type 'P' (individual) at the 4th char.
        assertTrue(PanValidator.isValid("ABCPE1234F"));
        // 'C' = company.
        assertTrue(PanValidator.isValid("XYZCA9876Z"));
    }

    @Test
    void validator_isCaseLenient() {
        // The validator upper-cases before checking. The registry's PAN pattern is uppercase-only,
        // so a lowercase PAN never reaches the validator through scan().
        assertTrue(PanValidator.isValid("abcpe1234f"));
    }

    @Test
    void unknownHolderTypeChar_fails() {
        // 4th char 'X' is not a recognised holder-type code.
        assertFalse(PanValidator.isValid("ABCXE1234F"));
    }

    @Test
    void wrongFormat_fails() {
        assertFalse(PanValidator.isValid("ABCP12345F")); // digit in letter slot
        assertFalse(PanValidator.isValid("ABCPE1234"));  // too short
        assertFalse(PanValidator.isValid("ABCPE12345")); // last char not a letter
        assertFalse(PanValidator.isValid("1BCPE1234F")); // first char not a letter
        assertFalse(PanValidator.isValid(null));
    }
}