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
package com.dvarahq.core.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HmacSignerTest {

    private static final String SECRET = "test-secret-key";

    @Test
    void sign_consistentOutputForSameInput() {
        String data = "hello world";
        String hmac1 = HmacSigner.sign(data, SECRET);
        String hmac2 = HmacSigner.sign(data, SECRET);
        assertThat(hmac1).isEqualTo(hmac2);
    }

    @Test
    void sign_differentKeyProducesDifferentHmac() {
        String data = "hello world";
        String hmac1 = HmacSigner.sign(data, SECRET);
        String hmac2 = HmacSigner.sign(data, "different-secret");
        assertThat(hmac1).isNotEqualTo(hmac2);
    }

    @Test
    void verify_validSignature() {
        String data = "test data";
        String hmac = HmacSigner.sign(data, SECRET);
        assertThat(HmacSigner.verify(data, hmac, SECRET)).isTrue();
    }

    @Test
    void verify_invalidSignature() {
        String data = "test data";
        assertThat(HmacSigner.verify(data, "invalid-hmac", SECRET)).isFalse();
    }

    @Test
    void verify_wrongKeyDetected() {
        String data = "test data";
        String hmac = HmacSigner.sign(data, SECRET);
        assertThat(HmacSigner.verify(data, hmac, "wrong-key")).isFalse();
    }

    @Test
    void canonicalize_deterministicRegardlessOfMapOrder() {
        // LinkedHashMap with insertion order A, B
        LinkedHashMap<String, Object> payload1 = new LinkedHashMap<>();
        payload1.put("model", "gpt-4");
        payload1.put("status", 200);

        // TreeMap with natural order (reversed insertion)
        TreeMap<String, Object> payload2 = new TreeMap<>();
        payload2.put("status", 200);
        payload2.put("model", "gpt-4");

        Instant timestamp = Instant.parse("2025-01-01T00:00:00Z");

        AuditEvent event1 = new AuditEvent("e1", timestamp, "tenant1", "GATEWAY_RESPONSE", payload1);
        AuditEvent event2 = new AuditEvent("e1", timestamp, "tenant1", "GATEWAY_RESPONSE", payload2);

        String canonical1 = HmacSigner.canonicalize(event1, "prev-hash");
        String canonical2 = HmacSigner.canonicalize(event2, "prev-hash");

        assertThat(canonical1).isEqualTo(canonical2);
    }

    @Test
    void canonicalize_handlesNullWorkspaceId() {
        AuditEvent event = new AuditEvent("e1", Instant.parse("2025-01-01T00:00:00Z"),
                null, "GATEWAY_RESPONSE", Map.of("model", "gpt-4"));

        String canonical = HmacSigner.canonicalize(event, "");
        assertThat(canonical).contains("||GATEWAY_RESPONSE");
    }

    @Test
    void sign_nullSecret_throwsClearError() {
        assertThatThrownBy(() -> HmacSigner.sign("data", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DVARA_AUDIT_HMAC_SECRET");
    }

    @Test
    void sign_blankSecret_throwsClearError() {
        assertThatThrownBy(() -> HmacSigner.sign("data", "  "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DVARA_AUDIT_HMAC_SECRET");
    }

    @Test
    void canonicalize_handlesEmptyPayload() {
        AuditEvent event = new AuditEvent("e1", Instant.parse("2025-01-01T00:00:00Z"),
                "tenant1", "GATEWAY_RESPONSE", Map.of());

        String canonical = HmacSigner.canonicalize(event, "prev");
        assertThat(canonical).contains("{}");
    }

    /**
     * canonicalize truncates the timestamp to microseconds. Postgres {@code TIMESTAMPTZ} stores
     * microseconds, so an {@code Instant} carrying nanoseconds would sign differently before and
     * after a database round-trip, and the chain could not be re-verified.
     *
     * <p>The nanosecond value is constructed explicitly rather than taken from the host clock, so
     * the test does not depend on the platform's clock resolution.
     */
    @Test
    void canonicalize_isPrecisionStableAcrossMicrosecondTruncation() {
        Map<String, Object> payload = Map.of("model", "gpt-4", "status", 200);
        // Same wall-clock instant, two precisions: full nanos (write-time, as a
        // Linux Instant.now() would produce) vs micros (the DB read-back value).
        Instant withNanos = Instant.parse("2026-05-28T04:31:30.123456789Z");
        Instant asStoredMicros = withNanos.truncatedTo(ChronoUnit.MICROS); // ...123456Z

        AuditEvent writeTime = new AuditEvent("e1", withNanos, "tenant1", "GATEWAY_RESPONSE", payload);
        AuditEvent readBack = new AuditEvent("e1", asStoredMicros, "tenant1", "GATEWAY_RESPONSE", payload);

        String writeCanonical = HmacSigner.canonicalize(writeTime, "prev-hash");
        String verifyCanonical = HmacSigner.canonicalize(readBack, "prev-hash");

        // Canonical strings — and therefore HMACs — must match across the round-trip.
        assertThat(writeCanonical).isEqualTo(verifyCanonical);
        assertThat(HmacSigner.sign(writeCanonical, SECRET))
                .isEqualTo(HmacSigner.sign(verifyCanonical, SECRET));
        // And the canonical form carries the microsecond value, not the nanos.
        assertThat(writeCanonical).contains("2026-05-28T04:31:30.123456Z");
        assertThat(writeCanonical).doesNotContain("123456789");
    }

    // ---------------------------------------------------------------------
    // nested payloads must canonicalize, not fall through to toString()
    // ---------------------------------------------------------------------

    /**
     * A nested map's key order must not change the signature.
     *
     * <p>JSONB stores keys in its own order and hands back a differently-ordered map, so a nested
     * map rendered in iteration order could not be re-verified after a round-trip.
     *
     * <p>The fixture needs two or more nested keys: with fewer there is no order to get wrong.
     */
    @Test
    void nestedMapKeyOrderDoesNotChangeTheSignature() {
        Instant ts = Instant.parse("2026-08-16T12:00:00Z");

        Map<String, Object> nestedA = new LinkedHashMap<>();
        nestedA.put("pii.action", "BLOCK");
        nestedA.put("pii.enabled", true);
        nestedA.put("pii.scan-responses", false);

        Map<String, Object> nestedB = new LinkedHashMap<>();
        nestedB.put("pii.scan-responses", false);
        nestedB.put("pii.enabled", true);
        nestedB.put("pii.action", "BLOCK");

        AuditEvent a = new AuditEvent("e1", ts, "t1", "WORKSPACE_PII_CONFIG_UPDATED",
                Map.of("after", nestedA));
        AuditEvent b = new AuditEvent("e1", ts, "t1", "WORKSPACE_PII_CONFIG_UPDATED",
                Map.of("after", nestedB));

        assertThat(HmacSigner.canonicalize(a, "prev"))
                .as("the same nested content in a different order must sign identically")
                .isEqualTo(HmacSigner.canonicalize(b, "prev"));
    }

    /** The nested map must be real JSON, not a quoted Java toString(). */
    @Test
    void nestedMapIsRenderedAsJsonNotAsToString() {
        Instant ts = Instant.parse("2026-08-16T12:00:00Z");
        AuditEvent e = new AuditEvent("e1", ts, "t1", "WORKSPACE_PII_CONFIG_UPDATED",
                Map.of("after", Map.of("k1", "v1", "k2", "v2")));

        String canonical = HmacSigner.canonicalize(e, "prev");

        assertThat(canonical).contains("\"after\":{\"k1\":\"v1\",\"k2\":\"v2\"}");
        assertThat(canonical)
                .as("the Java toString() rendering must not appear")
                .doesNotContain("k1=v1");
    }

    /**
     * A nested map and the literal string of its {@code toString()} must not sign identically:
     * a collision in a function whose only job is to tell contents apart.
     */
    @Test
    void aNestedMapAndItsToStringDoNotCollide() {
        Instant ts = Instant.parse("2026-08-16T12:00:00Z");
        AuditEvent asMap = new AuditEvent("e1", ts, "t1", "X", Map.of("v", Map.of("a", "1")));
        AuditEvent asText = new AuditEvent("e1", ts, "t1", "X", Map.of("v", "{a=1}"));

        assertThat(HmacSigner.canonicalize(asMap, "p"))
                .isNotEqualTo(HmacSigner.canonicalize(asText, "p"));
    }

    /** Lists keep their order — order IS content for a list, unlike a map. */
    @Test
    void listsAreRenderedElementWiseAndOrderMatters() {
        Instant ts = Instant.parse("2026-08-16T12:00:00Z");
        AuditEvent a = new AuditEvent("e1", ts, "t1", "X", Map.of("v", java.util.List.of("x", "y")));
        AuditEvent b = new AuditEvent("e1", ts, "t1", "X", Map.of("v", java.util.List.of("y", "x")));

        assertThat(HmacSigner.canonicalize(a, "p")).contains("\"v\":[\"x\",\"y\"]");
        assertThat(HmacSigner.canonicalize(a, "p")).isNotEqualTo(HmacSigner.canonicalize(b, "p"));
    }


    /**
     * The canonical form's escaping is frozen: a control character other than newline,
     * return and tab stays raw in the bytes every chain signs, so stored envelopes and a mixed
     * fleet's ingest keep agreeing. The file renderer, which must be JSON, escapes it.
     */
    @Test
    void canonicalizeKeepsItsEscapingFrozen_whileTheFileRendererIsStrict() {
        AuditEvent event = new AuditEvent("e1", java.time.Instant.parse("2026-08-28T10:15:30Z"), "acme",
                "X", java.util.Map.of("k", "a\u0008b\"c"));
        String canonical = HmacSigner.canonicalize(event, null);
        assertThat(canonical).contains("a\u0008b\\\"c").doesNotContain("\\u0008");
        assertThat(HmacSigner.renderPayload(event.payload())).isEqualTo("{\"k\":\"a\\u0008b\\\"c\"}");
        assertThat(HmacSigner.escapeJsonStrict("\u007F\u2028")).as("legal raw in JSON, left alone").isEqualTo("\u007F\u2028");
    }
}
