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
package com.dvarahq.providers.bedrock;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * Builds Amazon Event Stream frames to the wire format, for tests. Header values are
 * typed by Java class: Boolean, Byte, Short, Integer, Long, byte[], String, java.time.Instant
 * (timestamp), java.util.UUID — the ten value types, in that order.
 */
final class EventStreamFrames {

    static final String EVENT_STREAM = "application/vnd.amazon.eventstream";

    private EventStreamFrames() { }

    static byte[] event(String eventType, String json) {
        return frame(Map.of(":message-type", "event", ":event-type", eventType,
                ":content-type", "application/json"), json.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] exception(String exceptionType, String json) {
        return frame(Map.of(":message-type", "exception", ":exception-type", exceptionType,
                ":content-type", "application/json"), json.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] error(String code, String message) {
        return frame(Map.of(":message-type", "error", ":error-code", code, ":error-message", message), new byte[0]);
    }

    static byte[] concat(byte[]... frames) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] f : frames) out.writeBytes(f);
        return out.toByteArray();
    }

    static byte[] frame(Map<String, ?> headers, byte[] payload) {
        return frameRaw(headers(headers), payload);
    }

    /** A frame around already-encoded (possibly malformed) header bytes, correctly signed. */
    static byte[] frameRaw(byte[] encodedHeaders, byte[] payload) {
        int total = 12 + encodedHeaders.length + payload.length + 4;
        ByteBuffer b = ByteBuffer.allocate(total);
        b.putInt(total).putInt(encodedHeaders.length);
        b.putInt((int) crc(b.array(), 0, 8));
        b.put(encodedHeaders).put(payload);
        b.putInt((int) crc(b.array(), 0, total - 4));
        return b.array();
    }

    static byte[] headers(Map<String, ?> headers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, ?> e : new LinkedHashMap<>(headers).entrySet()) {
            byte[] name = e.getKey().getBytes(StandardCharsets.UTF_8);
            out.write(name.length);
            out.writeBytes(name);
            Object v = e.getValue();
            if (v instanceof Boolean bool) {
                out.write(bool ? 0 : 1);
            } else if (v instanceof Byte by) {
                out.write(2); out.write(by);
            } else if (v instanceof Short sh) {
                out.write(3); out.writeBytes(ByteBuffer.allocate(2).putShort(sh).array());
            } else if (v instanceof Integer in) {
                out.write(4); out.writeBytes(ByteBuffer.allocate(4).putInt(in).array());
            } else if (v instanceof Long lo) {
                out.write(5); out.writeBytes(ByteBuffer.allocate(8).putLong(lo).array());
            } else if (v instanceof byte[] bytes) {
                out.write(6); out.writeBytes(ByteBuffer.allocate(2).putShort((short) bytes.length).array()); out.writeBytes(bytes);
            } else if (v instanceof String s) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                out.write(7); out.writeBytes(ByteBuffer.allocate(2).putShort((short) bytes.length).array()); out.writeBytes(bytes);
            } else if (v instanceof java.time.Instant t) {
                out.write(8); out.writeBytes(ByteBuffer.allocate(8).putLong(t.toEpochMilli()).array());
            } else if (v instanceof java.util.UUID u) {
                out.write(9); out.writeBytes(ByteBuffer.allocate(16).putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()).array());
            } else {
                throw new IllegalArgumentException("no header type for " + v.getClass());
            }
        }
        return out.toByteArray();
    }

    static long crc(byte[] bytes, int off, int len) {
        CRC32 c = new CRC32();
        c.update(bytes, off, len);
        return c.getValue();
    }

    /** Hands out one byte per read, so a decoder that assumes whole frames arrive at once fails. */
    static java.io.InputStream oneByteAtATime(byte[] bytes) {
        return new java.io.InputStream() {
            private int at;
            @Override public int read() { return at < bytes.length ? bytes[at++] & 0xFF : -1; }
            @Override public int read(byte[] b, int off, int len) {
                if (len == 0) return 0;
                int v = read();
                if (v < 0) return -1;
                b[off] = (byte) v;
                return 1;
            }
        };
    }
}
