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

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolCall;
import com.dvarahq.core.pii.PiiEntityType;
import com.dvarahq.core.pii.PiiScanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegexPiiDetectorTest {

    private RegexPiiDetector detector;

    @BeforeEach
    void setUp() {
        detector = new RegexPiiDetector(new PiiPatternRegistry());
    }

    @Test
    void scan_detectsEmail() {
        PiiScanResult result = detector.scan("Contact me at john@example.com for details", Map.of());
        assertThat(result.hasPii()).isTrue();
        assertThat(result.entityCount()).isEqualTo(1);
        assertThat(result.entities().getFirst().type()).isEqualTo(PiiEntityType.EMAIL);
        assertThat(result.entities().getFirst().value()).isEqualTo("john@example.com");
    }

    @Test
    void scan_detectsMultipleTypes() {
        String text = "Email: test@example.com, IP: 192.168.1.100";
        PiiScanResult result = detector.scan(text, Map.of());
        assertThat(result.entityCount()).isGreaterThanOrEqualTo(2);
        assertThat(result.entities().stream().map(e -> e.type()).toList())
                .contains(PiiEntityType.EMAIL, PiiEntityType.IP_ADDRESS);
    }

    @Test
    void scan_emptyText_returnsEmpty() {
        assertThat(detector.scan("", Map.of())).isEqualTo(PiiScanResult.EMPTY);
        assertThat(detector.scan(null, Map.of())).isEqualTo(PiiScanResult.EMPTY);
    }

    @Test
    void scan_noMatch_returnsEmptyEntities() {
        PiiScanResult result = detector.scan("Hello world, no PII here.", Map.of());
        assertThat(result.hasPii()).isFalse();
    }

    @Test
    void scan_creditCardWithLuhnValidation() {
        // 4111111111111111 passes Luhn
        PiiScanResult result = detector.scan("Card: 4111 1111 1111 1111", Map.of());
        assertThat(result.hasPii()).isTrue();
        assertThat(result.entities().getFirst().type()).isEqualTo(PiiEntityType.CREDIT_CARD);
    }

    @Test
    void scanRequest_scansAllMessages() {
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("user")
                                .content(List.of(new ContentBlock.TextBlock("Email me at user@test.com")))
                                .build(),
                        MultimodalMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentBlock.TextBlock("No PII here")))
                                .build()
                ))
                .build();

        var results = detector.scanRequest(request, Map.of());
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().entities().getFirst().type()).isEqualTo(PiiEntityType.EMAIL);
    }

    @Test
    void scanResponse_scansChoices() {
        ChatResponse response = ChatResponse.builder()
                .id("resp-1")
                .model("gpt-4o")
                .choices(List.of(
                        ChatResponse.Choice.builder()
                                .index(0)
                                .message(MultimodalMessage.builder()
                                        .role("assistant")
                                        .content(List.of(new ContentBlock.TextBlock("Call 555-123-4567")))
                                        .build())
                                .build()
                ))
                .build();

        var results = detector.scanResponse(response, Map.of());
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().entities().getFirst().type()).isEqualTo(PiiEntityType.PHONE_NUMBER);
    }

    @Test
    void redact_replacesWithAnIrreversiblePlaceholder() {
        // REDACT removes the value outright: no mapping is written and nothing can restore it.
        PiiScanResult result = detector.scan("Email: john@example.com", Map.of());
        String redacted = detector.redact("Email: john@example.com", result.entities());
        assertThat(redacted).isEqualTo("Email: [REDACTED_EMAIL]");
    }

    @Test
    void scanRequest_detectsPiiInToolCallArguments() {
        // PII in tool-call arguments is detected like PII in message text.
        ChatRequest request = ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(
                        MultimodalMessage.builder()
                                .role("assistant")
                                .content(List.of(new ContentBlock.TextBlock("")))
                                .toolCalls(List.of(ToolCall.builder()
                                        .id("call_1").name("send_email")
                                        .arguments("{\"to\":\"john@example.com\",\"body\":\"hi\"}")
                                        .build()))
                                .build()))
                .build();

        var results = detector.scanRequest(request, Map.of());
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().entities().getFirst().type()).isEqualTo(PiiEntityType.EMAIL);
        assertThat(results.getFirst().entities().getFirst().value()).isEqualTo("john@example.com");
    }

    // ---------------- India Aadhaar / PAN ----------------

    @Test
    void scan_detectsValidAadhaar() {
        PiiScanResult result = detector.scan("Aadhaar: 2341 2341 2346 on file", Map.of());
        assertThat(result.entities()).anyMatch(e -> e.type() == PiiEntityType.AADHAAR);
    }

    @Test
    void scan_rejectsInvalidAadhaarChecksum() {
        // Twelve digits of the right shape that fail the Verhoeff check digit are dropped.
        PiiScanResult result = detector.scan("Number 2341 2341 2347 here", Map.of());
        assertThat(result.entities()).noneMatch(e -> e.type() == PiiEntityType.AADHAAR);
    }

    @Test
    void scan_detectsValidPan() {
        PiiScanResult result = detector.scan("PAN is ABCPE1234F for KYC", Map.of());
        assertThat(result.entities()).anyMatch(e -> e.type() == PiiEntityType.PAN);
    }

    @Test
    void scan_rejectsMalformedPan() {
        // Matches the regex shape but the 4th char is not a valid holder-type code.
        PiiScanResult result = detector.scan("Code ABCXE1234F here", Map.of());
        assertThat(result.entities()).noneMatch(e -> e.type() == PiiEntityType.PAN);
    }

    @Test
    void redact_aadhaar_replacesWithAnIrreversiblePlaceholder() {
        // The placeholder names the type, so an Aadhaar stays distinguishable from a phone number
        // in the redacted prompt.
        String text = "Aadhaar 234123412346";
        PiiScanResult result = detector.scan(text, Map.of());
        String redacted = detector.redact(text, result.entities());
        assertThat(redacted).doesNotContain("234123412346");
        assertThat(redacted).isEqualTo("Aadhaar [REDACTED_AADHAAR]");
    }

    @Test
    void scan_contiguousValidAadhaar_labeledAadhaarNotPhone() {
        // A contiguous 12-digit Aadhaar also matches the phone shape. The Verhoeff-validated
        // AADHAAR carries the higher confidence, so it wins when the two overlap.
        PiiScanResult result = detector.scan("id 234123412346 end", Map.of());
        assertThat(result.entities()).anyMatch(e -> e.type() == PiiEntityType.AADHAAR);
        assertThat(result.entities())
                .filteredOn(e -> e.value().contains("234123412346"))
                .allMatch(e -> e.type() == PiiEntityType.AADHAAR);
    }

    @Test
    void scan_sixteenDigitNumber_notAadhaar() {
        // The Aadhaar pattern must not match twelve digits out of a longer number.
        PiiScanResult result = detector.scan("card 4111111111111111 here", Map.of());
        assertThat(result.entities()).noneMatch(e -> e.type() == PiiEntityType.AADHAAR);
    }

    @Test
    void scan_lowercasePan_notDetected_uppercaseOnlyByDesign() {
        // Real PANs are uppercase, and matching case-insensitively would raise false positives, so
        // a lowercase look-alike is not flagged.
        PiiScanResult result = detector.scan("pan abcpe1234f here", Map.of());
        assertThat(result.entities()).noneMatch(e -> e.type() == PiiEntityType.PAN);
    }

    @Test
    void scan_digitsInsideALongerNumber_areNotAPhone() {
        // Order numbers, invoice ids and timestamps are common in prompts and must not be read as
        // phone numbers.
        for (String text : List.of(
                "Order 20260914123456 shipped",
                "invoice INV-4155551234567 sent",
                "event at 1726300000000 ms",
                "tracking +12345678901234567890 queued")) {
            assertThat(detector.scan(text, Map.of()).entities())
                    .as(text)
                    .noneMatch(e -> e.type() == PiiEntityType.PHONE_NUMBER);
        }
    }

    @Test
    void scan_aVersionWithMoreThanFourParts_isNotAnIpAddress() {
        for (String text : List.of("version 2.14.1.3.7 released", "build 1.10.22.33.44.55 ready")) {
            assertThat(detector.scan(text, Map.of()).entities())
                    .as(text)
                    .noneMatch(e -> e.type() == PiiEntityType.IP_ADDRESS);
        }
    }

    @Test
    void scan_phonesAndAddressesAtTheEdgesOfText_stillMatch() {
        assertThat(types("(555) 123-4567 is my number")).contains(PiiEntityType.PHONE_NUMBER);
        assertThat(types("Call me on 555.123.4567.")).contains(PiiEntityType.PHONE_NUMBER);
        assertThat(types("Call +1 (555) 123-4567 today")).contains(PiiEntityType.PHONE_NUMBER);
        assertThat(types("host=192.168.1.1:8080")).contains(PiiEntityType.IP_ADDRESS);
        assertThat(types("from 10.0.0.1, then 10.0.0.2.")).contains(PiiEntityType.IP_ADDRESS);
    }

    private List<PiiEntityType> types(String text) {
        return detector.scan(text, Map.of()).entities().stream().map(e -> e.type()).toList();
    }
}