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

import com.dvarahq.core.pii.PiiEntityType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;

import static org.assertj.core.api.Assertions.assertThat;

class PiiPatternRegistryTest {

    private final PiiPatternRegistry registry = new PiiPatternRegistry();

    @Test
    void builtInPatterns_hasExpectedCount() {
        // 14 base + Aadhaar + PAN
        assertThat(registry.builtInPatterns()).hasSize(16);
    }

    /**
     * The international phone pattern must not start or end inside a longer run of digits. It is
     * checked on its own here, because through the detector another type's overlapping match can
     * win the same digits.
     */
    @Test
    void phoneIntl_doesNotMatchInsideALongerNumber() {
        java.util.regex.Pattern intl = registry.builtInPatterns().stream()
                .filter(p -> p.label().equals("phone_intl")).findFirst().orElseThrow().pattern();

        assertThat(intl.matcher("tracking +12345678901234567890 queued").find()).isFalse();
        assertThat(intl.matcher("ref 9+44207946095 end").find()).isFalse();
        assertThat(intl.matcher("call +442079460958.").find()).isTrue();
        assertThat(intl.matcher("+14155551234").find()).isTrue();
    }

    @Test
    void email_matches() {
        assertMatches(PiiEntityType.EMAIL, "user@example.com");
        assertMatches(PiiEntityType.EMAIL, "first.last+tag@sub.domain.org");
    }

    @Test
    void phoneUs_matches() {
        assertMatches(PiiEntityType.PHONE_NUMBER, "(555) 123-4567");
        assertMatches(PiiEntityType.PHONE_NUMBER, "+1 555 123 4567");
    }

    @Test
    void ssn_matches() {
        assertMatches(PiiEntityType.SSN, "123-45-6789");
        assertMatches(PiiEntityType.SSN, "123 45 6789");
    }

    @Test
    void ssn_rejectsInvalidPrefixes() {
        assertNoMatch(PiiEntityType.SSN, "000-12-3456");
        assertNoMatch(PiiEntityType.SSN, "666-12-3456");
    }

    @Test
    void creditCard_matchesVisaFormat() {
        assertMatches(PiiEntityType.CREDIT_CARD, "4111 1111 1111 1111");
    }

    @Test
    void dob_matches() {
        assertMatches(PiiEntityType.DATE_OF_BIRTH, "01/15/1990");
        assertMatches(PiiEntityType.DATE_OF_BIRTH, "12-31-2000");
    }

    @Test
    void ipv4_matches() {
        assertMatches(PiiEntityType.IP_ADDRESS, "192.168.1.1");
        assertMatches(PiiEntityType.IP_ADDRESS, "10.0.0.255");
    }

    @Test
    void passport_matchesWithKeyword() {
        assertMatches(PiiEntityType.PASSPORT_NUMBER, "Passport# A12345678");
    }

    @Test
    void driversLicense_matchesWithKeyword() {
        assertMatches(PiiEntityType.DRIVERS_LICENSE, "Driver's License: D12345678");
    }

    @Test
    void iban_matches() {
        assertMatches(PiiEntityType.IBAN, "DE89 3704 0044 0532 0130 00");
    }

    @Test
    void mrn_matchesWithKeyword() {
        assertMatches(PiiEntityType.MEDICAL_RECORD_NUMBER, "MRN: 123456");
    }

    @Test
    void personName_matchesSalutation() {
        assertMatches(PiiEntityType.PERSON_NAME, "Dr. John Smith");
        assertMatches(PiiEntityType.PERSON_NAME, "Mrs. Jane Doe");
    }

    @Test
    void mergeWithCustom_addsCustomPatterns() {
        var merged = registry.mergeWithCustom(Map.of("custom_id", "CUST-\\d{6}"));
        assertThat(merged).hasSize(17); // 16 built-in + 1 custom
        var custom = merged.stream().filter(p -> p.label().equals("custom_id")).findFirst();
        assertThat(custom).isPresent();
        assertThat(custom.get().type()).isEqualTo(PiiEntityType.CUSTOM);
    }

    @Test
    void mergeWithCustom_nullReturnsBuiltIn() {
        assertThat(registry.mergeWithCustom(null)).isSameAs(registry.builtInPatterns());
    }

    private void assertMatches(PiiEntityType expectedType, String text) {
        boolean found = false;
        for (var entry : registry.builtInPatterns()) {
            if (entry.type() == expectedType) {
                Matcher m = entry.pattern().matcher(text);
                if (m.find()) {
                    found = true;
                    break;
                }
            }
        }
        assertThat(found).as("Expected %s to match '%s'", expectedType, text).isTrue();
    }

    private void assertNoMatch(PiiEntityType expectedType, String text) {
        boolean found = false;
        for (var entry : registry.builtInPatterns()) {
            if (entry.type() == expectedType) {
                Matcher m = entry.pattern().matcher(text);
                if (m.find()) {
                    found = true;
                    break;
                }
            }
        }
        assertThat(found).as("Expected %s NOT to match '%s'", expectedType, text).isFalse();
    }
}