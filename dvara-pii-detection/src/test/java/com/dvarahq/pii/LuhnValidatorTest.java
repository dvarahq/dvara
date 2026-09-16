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

import static org.assertj.core.api.Assertions.assertThat;

class LuhnValidatorTest {

    @Test
    void validVisaCard() {
        assertThat(LuhnValidator.isValid("4111111111111111")).isTrue();
    }

    @Test
    void validMastercardWithSpaces() {
        assertThat(LuhnValidator.isValid("5500 0000 0000 0004")).isTrue();
    }

    @Test
    void validAmexWithDashes() {
        assertThat(LuhnValidator.isValid("3782-822463-10005")).isTrue();
    }

    @Test
    void invalidChecksum() {
        assertThat(LuhnValidator.isValid("4111111111111112")).isFalse();
    }

    @Test
    void tooShort() {
        assertThat(LuhnValidator.isValid("1")).isFalse();
    }

    @Test
    void nonDigitCharactersReject() {
        assertThat(LuhnValidator.isValid("41111111111abcde")).isFalse();
    }

    @Test
    void validNpiNumber() {
        // NPI 1234567897 passes Luhn
        assertThat(LuhnValidator.isValid("1234567897")).isTrue();
    }
}