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


import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.TreeMap;

/**
 * HMAC-SHA256 signing utility for audit events.
 * Uses only JDK {@code javax.crypto} — no external dependencies.
 */
public final class HmacSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private HmacSigner() {}

    /**
     * Compute HMAC-SHA256 of the given data using the provided secret.
     *
     * @param data   the data to sign
     * @param secret the HMAC key
     * @return hex-encoded HMAC
     */
    public static String sign(String data, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "Audit HMAC secret is not configured. "
                    + "Set DVARA_AUDIT_HMAC_SECRET or dvara.audit.hmac-secret.");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmacBytes);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    /**
     * Verify an HMAC using constant-time comparison.
     *
     * @param data         the original data
     * @param expectedHmac the expected HMAC hex string
     * @param secret       the HMAC key
     * @return true if the HMAC matches
     */
    public static boolean verify(String data, String expectedHmac, String secret) {
        String actual = sign(data, secret);
        return MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.UTF_8),
                expectedHmac.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Create a deterministic canonical string from an audit event and the previous hash.
     * Format: {@code eventId|timestamp_iso|workspaceId_or_empty|eventType|sorted_payload_json|previousHash}
     * <p>
     * Payload keys are sorted alphabetically for determinism.
     * <p>
     * The timestamp is truncated to <b>microseconds</b> before formatting. A database timestamp
     * column holds microsecond precision, so signing over a nanosecond {@code Instant} would make
     * verification fail after a round-trip; truncating here keeps the write-time and verify-time
     * HMAC over identical bytes whatever the host clock's resolution.
     */
    public static String canonicalize(AuditEvent event, String previousHash) {
        return canonicalizeWith(event, previousHash, v -> valueToJson(v, HmacSigner::escapeJson));
    }

    /**
     * The one place the canonical string is assembled, so the envelope layout, the timestamp
     * truncation and the field order have a single definition.
     */
    private static String canonicalizeWith(AuditEvent event,
                                           String previousHash,
                                           java.util.function.Function<Object, String> renderer) {
        return canonicalizeRendered(
                event.eventId(),
                DateTimeFormatter.ISO_INSTANT.format(event.timestamp().truncatedTo(ChronoUnit.MICROS)),
                event.workspaceId(),
                event.eventType(),
                sortedPayloadJson(event.payload(), renderer),
                previousHash);
    }

    /**
     * The same canonicalization, from an <b>already-rendered</b> payload.
     *
     * <p>For verifying a chain read back from storage. Re-rendering a parsed payload can change its
     * bytes ({@code 1} against {@code 1.0}) and read as tampering, so the verifier hands back the
     * payload text that was signed. {@link #canonicalize(AuditEvent, String)} routes through here, so
     * there is one definition of the signed byte string.
     */
    public static String canonicalizeRendered(String eventId,
                                              String timestampIso,
                                              String workspaceId,
                                              String eventType,
                                              String renderedPayload,
                                              String previousHash) {
        StringBuilder sb = new StringBuilder();
        sb.append(eventId).append('|');
        sb.append(timestampIso).append('|');
        sb.append(workspaceId != null ? workspaceId : "").append('|');
        sb.append(eventType).append('|');
        sb.append(renderedPayload != null ? renderedPayload : "{}").append('|');
        sb.append(previousHash != null ? previousHash : "");
        return sb.toString();
    }

    /**
     * Canonical string for a per-pod chain segment.
     *
     * <p>Format: {@code <event canonical form>|<podInstanceId>|<chainEpoch>|<chainSequence>}.
     *
     * <p><b>The chain identity is signed, not just recorded.</b> With several chains, a row's
     * position is data rather than a global counter, and unsigned data can be edited: a row could
     * be relabelled into a different pod or epoch, or renumbered within its segment, with every
     * HMAC still verifying. Binding the triple into the signature makes the segment a row claims to
     * belong to part of what the HMAC attests.
     */
    public static String canonicalizeSegmented(AuditEvent event,
                                               String previousHash,
                                               String podInstanceId,
                                               long chainEpoch,
                                               long chainSequence) {
        return canonicalize(event, previousHash)
                + '|' + (podInstanceId != null ? podInstanceId : "")
                + '|' + chainEpoch
                + '|' + chainSequence;
    }

    private static String sortedPayloadJson(Map<String, Object> payload,
                                            java.util.function.Function<Object, String> renderer) {
        return sortedPayloadJson(payload, renderer, HmacSigner::escapeJson);
    }

    private static String sortedPayloadJson(Map<String, Object> payload,
                                            java.util.function.Function<Object, String> renderer,
                                            java.util.function.UnaryOperator<String> keyEscaper) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        TreeMap<String, Object> sorted = new TreeMap<>(payload);
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : sorted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(keyEscaper.apply(entry.getKey())).append("\":");
            sb.append(renderer.apply(entry.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * Render one payload value canonically, recursing into nested maps and lists.
     *
     * <p>Nested map keys are sorted with the same {@link TreeMap} rule as the top level, so the
     * signing side and a verifier that read the payload back from storage in a different map order
     * render identical bytes. Lists are rendered element-wise, because a list's order is content.
     * A nested map must never be rendered through {@code toString()}: that would depend on iteration
     * order and would collide with the literal string of the same text.
     */
    private static String valueToJson(Object value, java.util.function.UnaryOperator<String> esc) {
        if (value == null) return "null";
        if (value instanceof Number) return value.toString();
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (var e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), e.getValue());
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : sorted.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(esc.apply(e.getKey())).append("\":");
                sb.append(valueToJson(e.getValue(), esc));
            }
            return sb.append('}').toString();
        }
        if (value instanceof Iterable<?> it) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object o : it) {
                if (!first) sb.append(',');
                first = false;
                sb.append(valueToJson(o, esc));
            }
            return sb.append(']').toString();
        }
        return "\"" + esc.apply(value.toString()) + "\"";
    }

    /**
     * Render a payload exactly as {@link #canonicalize} renders it.
     *
     * <p>Public because a file-backed audit log has to write the payload it signed, byte for byte. A
     * writer that serialized with Jackson and signed with this would produce lines whose HMAC could
     * not be recomputed from the line — signed data and stored data have to be the same bytes, and
     * the only way to guarantee that is one renderer.
     */
    public static String renderPayload(Map<String, Object> payload) {
        // Strict escaping, so the line is JSON to any reader. The file audit writer signs THIS
        // rendering, so its writer and verifier agree on every byte. The chain canonical form keeps
        // its own escaping (escapeJson), frozen, because stored envelopes were signed with it.
        return sortedPayloadJson(payload, v -> valueToJson(v, HmacSigner::escapeJsonStrict),
                HmacSigner::escapeJsonStrict);
    }

    /**
     * The canonical form's escaping: the five JSON short forms and nothing else. <b>Frozen</b>: it
     * is part of the byte string every audit chain signs, so a change here makes stored envelopes
     * fail verification. It is not complete JSON escaping and is not meant to be read as JSON;
     * {@link #escapeJsonStrict} is.
     */
    public static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Complete JSON string escaping: quote, backslash, the short forms for newline, carriage
     * return and tab, and every other character in U+0000–U+001F as a backslash-u escape with four hex
     * digits (the Java lexer forbids writing that sequence even in a comment). U+007F, U+2028
     * and U+2029 are legal raw in JSON and stay raw. Used by the file audit log, whose lines are
     * promised to be JSON; never by the canonical form.
     */
    public static String escapeJsonStrict(String s) {
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            String replacement = switch (c) {
                case '"' -> "\\\"";
                case '\\' -> "\\\\";
                case '\n' -> "\\n";
                case '\r' -> "\\r";
                case '\t' -> "\\t";
                default -> c < 0x20 ? String.format("\\u%04X", (int) c) : null;
            };
            if (replacement == null) {
                if (sb != null) sb.append(c);
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(s.length() + 8).append(s, 0, i);
            }
            sb.append(replacement);
        }
        return sb == null ? s : sb.toString();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}