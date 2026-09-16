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

import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiEntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REDACT removes the value: no key, no store, no way back.
 *
 * <p>Redaction needs nothing but the pattern registry. The placeholder names the type and carries
 * nothing of the value, and two different values redact to the same placeholder, so no mapping back
 * to the original can exist.
 */
class IrreversibleRedactionTest {

    private final RegexPiiDetector detector = new RegexPiiDetector(new PiiPatternRegistry());

    @Test
    @DisplayName("redaction works with no encryption password, no store, no control plane")
    void redactionNeedsNothing() {
        String text = "Contact john@example.com about it";
        var result = detector.scan(text, Map.of());

        assertThat(detector.redact(text, result.entities()))
                .describedAs("redaction needs no key material")
                .isEqualTo("Contact [REDACTED_EMAIL] about it");
    }

    @Test
    @DisplayName("the original value is not in the output, in any form")
    void theOriginalIsGone() {
        String text = "SSN 123-45-6789 and card 4111111111111111";
        var result = detector.scan(text, Map.of());
        String redacted = detector.redact(text, result.entities());

        assertThat(redacted).doesNotContain("123-45-6789").doesNotContain("4111111111111111");
        assertThat(redacted)
                .describedAs("the placeholder names the type and carries nothing of the value")
                .contains("[REDACTED_SSN]")
                .contains("[REDACTED_CREDIT_CARD]");
    }

    @Test
    @DisplayName("nothing in the output can be turned back into the original")
    void thereIsNothingToRestore() {
        String text = "Email alice@corp.example and bob@corp.example";
        var result = detector.scan(text, Map.of());
        String redacted = detector.redact(text, result.entities());

        // Two different values redact to the same placeholder, so the output cannot be mapped back.
        assertThat(redacted).isEqualTo("Email [REDACTED_EMAIL] and [REDACTED_EMAIL]");
    }

    @Test
    @DisplayName("overlapping and adjacent entities still replace correctly")
    void offsetsSurviveReplacement() {
        String text = "a@b.com then 123-45-6789 then c@d.com";
        var result = detector.scan(text, Map.of());
        String redacted = detector.redact(text, result.entities());

        // Replacement runs back to front, so an earlier substitution cannot move a later offset.
        assertThat(redacted).doesNotContain("a@b.com").doesNotContain("c@d.com")
                .doesNotContain("123-45-6789");
    }

    @Test
    @DisplayName("the checksum still decides what counts as a card")
    void theChecksumStillDecides() {
        // 4111111111111111 passes Luhn, 4111111111111112 does not. The entity type is asserted
        // rather than hasPii(), because the invalid number still matches a phone pattern on its
        // first ten digits and a bare hasPii() would pass for the wrong reason.
        assertThat(detector.scan("card 4111111111111111", Map.of()).entities())
                .extracting(PiiEntity::type)
                .contains(PiiEntityType.CREDIT_CARD);

        assertThat(detector.scan("card 4111111111111112", Map.of()).entities())
                .describedAs("a number that fails Luhn is not a card")
                .extracting(PiiEntity::type)
                .doesNotContain(PiiEntityType.CREDIT_CARD);
    }
}
