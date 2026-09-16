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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * A pull-based decoder for Amazon Event Stream, the binary framing Bedrock's ConverseStream
 * returns as {@code application/vnd.amazon.eventstream}. It is not SSE.
 *
 * <p>One frame: a 12-byte prelude (total length and headers length, both unsigned big-endian
 * 32-bit, then a CRC32 over those 8 bytes), the headers, the payload, and a CRC32 over everything
 * before it. Headers are {@code [name length (1)][name][value type (1)][value]}; every value type
 * is consumed at its own width, because a header the decoder cannot step over loses the boundary
 * of every header after it. Only string-typed headers are kept; those name the event.
 *
 * <p>Reads one frame at a time off the response stream, never the whole body, and reports a
 * corrupted, truncated or implausibly sized frame as an {@link IOException} rather than serving
 * whatever bytes were there.
 */
final class BedrockEventStreamDecoder {

    /** Bedrock responses are far smaller; a length past this is corruption, not a large frame. */
    static final int MAX_FRAME_BYTES = 16 * 1024 * 1024;
    private static final int PRELUDE_BYTES = 12;
    private static final int TRAILER_BYTES = 4;

    /** One decoded frame: the headers that matter, and the payload as sent. */
    record Frame(String messageType, String eventType, String exceptionType, String errorCode,
                 String errorMessage, String contentType, byte[] payload, Map<String, String> stringHeaders) {
        boolean isEvent() { return "event".equals(messageType); }
        boolean isException() { return "exception".equals(messageType); }
        boolean isError() { return "error".equals(messageType); }
    }

    private final InputStream in;

    BedrockEventStreamDecoder(InputStream in) {
        this.in = in;
    }

    /**
     * The next frame, or {@code null} at a clean end of stream — end of input exactly on a frame
     * boundary. End of input anywhere else is a truncated frame and throws.
     */
    Frame next() throws IOException {
        byte[] prelude = new byte[PRELUDE_BYTES];
        int got = readUpTo(prelude);
        if (got == 0) {
            return null;
        }
        if (got < PRELUDE_BYTES) {
            throw new EOFException("Bedrock event stream ended inside a frame prelude (" + got + " of 12 bytes)");
        }
        ByteBuffer pb = ByteBuffer.wrap(prelude);
        long totalLength = Integer.toUnsignedLong(pb.getInt());
        long headersLength = Integer.toUnsignedLong(pb.getInt());
        long preludeCrc = Integer.toUnsignedLong(pb.getInt());
        if (crc32(prelude, 0, 8) != preludeCrc) {
            throw new IOException("Bedrock event stream frame has a bad prelude CRC");
        }
        if (totalLength < PRELUDE_BYTES + TRAILER_BYTES || totalLength > MAX_FRAME_BYTES
                || headersLength > totalLength - PRELUDE_BYTES - TRAILER_BYTES) {
            throw new IOException("Bedrock event stream frame has invalid lengths (total " + totalLength
                    + ", headers " + headersLength + ")");
        }
        byte[] frame = new byte[(int) totalLength];
        System.arraycopy(prelude, 0, frame, 0, PRELUDE_BYTES);
        readFully(frame, PRELUDE_BYTES, frame.length - PRELUDE_BYTES);
        long messageCrc = Integer.toUnsignedLong(ByteBuffer.wrap(frame, frame.length - TRAILER_BYTES, TRAILER_BYTES).getInt());
        if (crc32(frame, 0, frame.length - TRAILER_BYTES) != messageCrc) {
            throw new IOException("Bedrock event stream frame has a bad message CRC");
        }
        Map<String, String> headers = parseHeaders(frame, PRELUDE_BYTES, (int) headersLength);
        int payloadStart = PRELUDE_BYTES + (int) headersLength;
        int payloadLength = frame.length - TRAILER_BYTES - payloadStart;
        byte[] payload = new byte[payloadLength];
        System.arraycopy(frame, payloadStart, payload, 0, payloadLength);
        return new Frame(headers.get(":message-type"), headers.get(":event-type"),
                headers.get(":exception-type"), headers.get(":error-code"), headers.get(":error-message"),
                headers.get(":content-type"), payload, Map.copyOf(headers));
    }

    /** String-typed headers by name; every other type is stepped over at its width. */
    private static Map<String, String> parseHeaders(byte[] frame, int start, int length) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        ByteBuffer b = ByteBuffer.wrap(frame, start, length);
        while (b.hasRemaining()) {
            int nameLength = u8(b);
            String name = new String(slice(b, nameLength), StandardCharsets.UTF_8);
            int type = u8(b);
            switch (type) {
                case 0, 1 -> { }                                   // bool true / bool false: no value bytes
                case 2 -> skip(b, 1);                              // byte
                case 3 -> skip(b, 2);                              // short
                case 4 -> skip(b, 4);                              // int
                case 5 -> skip(b, 8);                              // long
                case 6 -> skip(b, u16(b));                         // byte array, 2-byte length
                case 7 -> headers.put(name, new String(slice(b, u16(b)), StandardCharsets.UTF_8));
                case 8 -> skip(b, 8);                              // timestamp
                case 9 -> skip(b, 16);                             // uuid
                default -> throw new IOException("Bedrock event stream header '" + name + "' has unknown value type " + type);
            }
        }
        return headers;
    }

    // Every read is bounds-checked: a header block whose lengths and CRCs are consistent can
    // still end after a name, or one byte into a two-byte length, and that must surface as the
    // IOException the iterator translates — not as a BufferUnderflowException past it.
    private static int u8(ByteBuffer b) throws IOException {
        if (!b.hasRemaining()) {
            throw new IOException("Bedrock event stream headers are truncated");
        }
        return Byte.toUnsignedInt(b.get());
    }

    private static int u16(ByteBuffer b) throws IOException {
        if (b.remaining() < 2) {
            throw new IOException("Bedrock event stream headers are truncated");
        }
        return Short.toUnsignedInt(b.getShort());
    }

    private static byte[] slice(ByteBuffer b, int n) throws IOException {
        if (b.remaining() < n) {
            throw new IOException("Bedrock event stream headers are truncated");
        }
        byte[] out = new byte[n];
        b.get(out);
        return out;
    }

    private static void skip(ByteBuffer b, int n) throws IOException {
        if (b.remaining() < n) {
            throw new IOException("Bedrock event stream headers are truncated");
        }
        b.position(b.position() + n);
    }

    private static long crc32(byte[] bytes, int offset, int length) {
        CRC32 crc = new CRC32();
        crc.update(bytes, offset, length);
        return crc.getValue();
    }

    /** Reads until the buffer is full or the stream ends; returns how many bytes arrived. */
    private int readUpTo(byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) break;
            got += n;
        }
        return got;
    }

    private void readFully(byte[] buf, int offset, int length) throws IOException {
        int got = 0;
        while (got < length) {
            int n = in.read(buf, offset + got, length - got);
            if (n < 0) {
                throw new EOFException("Bedrock event stream ended inside a frame (" + (offset + got)
                        + " of " + (offset + length) + " bytes)");
            }
            got += n;
        }
    }
}
