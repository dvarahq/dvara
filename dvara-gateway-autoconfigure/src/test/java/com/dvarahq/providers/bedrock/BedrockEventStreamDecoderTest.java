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

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.dvarahq.providers.bedrock.EventStreamFrames.concat;
import static com.dvarahq.providers.bedrock.EventStreamFrames.event;
import static com.dvarahq.providers.bedrock.EventStreamFrames.frame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Amazon Event Stream decoder against the wire format. */
class BedrockEventStreamDecoderTest {

    private static final String DELTA = "{\"delta\":{\"text\":\"Hello\"},\"contentBlockIndex\":0}";

    private static BedrockEventStreamDecoder decoder(byte[] bytes) {
        return new BedrockEventStreamDecoder(new ByteArrayInputStream(bytes));
    }

    /** One frame, hand-checked against the specification's layout: 12-byte prelude, headers, payload, CRC. */
    @Test
    void decodesAFrameLaidOutToTheSpecification() throws IOException {
        byte[] f = event("contentBlockDelta", DELTA);
        byte[] headers = EventStreamFrames.headers(Map.of(":message-type", "event", ":event-type", "contentBlockDelta",
                ":content-type", "application/json"));
        int total = 12 + headers.length + DELTA.length() + 4;
        assertThat(f).hasSize(total);
        assertThat(java.nio.ByteBuffer.wrap(f).getInt()).as("total length, big-endian").isEqualTo(total);
        assertThat(java.nio.ByteBuffer.wrap(f, 4, 4).getInt()).as("headers length").isEqualTo(headers.length);
        assertThat(Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(f, 8, 4).getInt())).as("prelude CRC over 8 bytes")
                .isEqualTo(EventStreamFrames.crc(f, 0, 8));
        assertThat(Arrays.copyOfRange(f, 12, 12 + headers.length)).isEqualTo(headers);
        // a string header on the wire: [len][name][type 7][2-byte value len][value]
        int nameLength = headers[0];
        String firstName = new String(headers, 1, nameLength, StandardCharsets.UTF_8);
        assertThat(firstName).as("[name length][name]").isIn(":message-type", ":event-type", ":content-type");
        assertThat(headers[1 + nameLength]).as("type 7 = string").isEqualTo((byte) 7);
        assertThat(java.nio.ByteBuffer.wrap(headers, 2 + nameLength, 2).getShort()).as("2-byte value length")
                .isEqualTo((short) Map.of(":message-type", "event", ":event-type", "contentBlockDelta",
                        ":content-type", "application/json").get(firstName).length());
        assertThat(Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(f, total - 4, 4).getInt())).as("message CRC over the rest")
                .isEqualTo(EventStreamFrames.crc(f, 0, total - 4));

        BedrockEventStreamDecoder.Frame frame = decoder(f).next();
        assertThat(frame.isEvent()).isTrue();
        assertThat(frame.eventType()).isEqualTo("contentBlockDelta");
        assertThat(frame.contentType()).isEqualTo("application/json");
        assertThat(new String(frame.payload(), StandardCharsets.UTF_8)).isEqualTo(DELTA);
    }

    @Test
    void severalFramesThenACleanEnd() throws IOException {
        BedrockEventStreamDecoder d = decoder(concat(event("messageStart", "{\"role\":\"assistant\"}"),
                event("contentBlockDelta", DELTA), event("messageStop", "{\"stopReason\":\"end_turn\"}")));
        assertThat(d.next().eventType()).isEqualTo("messageStart");
        assertThat(d.next().eventType()).isEqualTo("contentBlockDelta");
        assertThat(d.next().eventType()).isEqualTo("messageStop");
        assertThat(d.next()).as("end of input on a frame boundary").isNull();
        assertThat(d.next()).isNull();
    }

    /** Every header value type is stepped over at its width; the strings after them still parse. */
    @Test
    void stepsOverEveryHeaderValueType() throws IOException {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("t", Boolean.TRUE);
        headers.put("f", Boolean.FALSE);
        headers.put("b", (byte) 7);
        headers.put("s", (short) 300);
        headers.put("i", 70_000);
        headers.put("l", 5_000_000_000L);
        headers.put("bytes", new byte[]{1, 2, 3});
        headers.put(":message-type", "event");
        headers.put("ts", java.time.Instant.parse("2026-09-04T00:00:00Z"));
        headers.put("id", java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"));
        headers.put(":event-type", "metadata");
        BedrockEventStreamDecoder.Frame frame = decoder(frame(headers, "{}".getBytes(StandardCharsets.UTF_8))).next();
        assertThat(frame.messageType()).isEqualTo("event");
        assertThat(frame.eventType()).as("read correctly after ten typed headers").isEqualTo("metadata");
    }

    @Test
    void anUnknownHeaderTypeIsRefused() {
        byte[] f = frame(Map.of(":message-type", "event"), new byte[0]);
        // corrupt the type byte of the first header (offset 12 + 1 + name length), then re-sign
        int typeAt = 12 + 1 + ":message-type".length();
        f[typeAt] = 10;
        byte[] resigned = resign(f);
        assertThatThrownBy(() -> decoder(resigned).next()).isInstanceOf(IOException.class).hasMessageContaining("unknown value type");
    }

    @Test
    void aBadPreludeCrcIsRefused() {
        byte[] f = event("messageStop", "{}");
        f[9] ^= 0x55;
        assertThatThrownBy(() -> decoder(f).next()).isInstanceOf(IOException.class).hasMessageContaining("prelude CRC");
    }

    @Test
    void aBadMessageCrcIsRefused() {
        byte[] f = event("messageStop", "{}");
        f[f.length - 1] ^= 0x55;
        assertThatThrownBy(() -> decoder(f).next()).isInstanceOf(IOException.class).hasMessageContaining("message CRC");
    }

    @Test
    void aTruncatedPreludeIsRefused() {
        byte[] f = Arrays.copyOf(event("messageStop", "{}"), 7);
        assertThatThrownBy(() -> decoder(f).next()).isInstanceOf(EOFException.class).hasMessageContaining("prelude");
    }

    @Test
    void aTruncatedFrameIsRefused() {
        byte[] whole = event("contentBlockDelta", DELTA);
        byte[] f = Arrays.copyOf(whole, whole.length - 3);
        assertThatThrownBy(() -> decoder(f).next()).isInstanceOf(EOFException.class).hasMessageContaining("inside a frame");
    }

    @Test
    void invalidLengthsAreRefused() {
        byte[] f = event("messageStop", "{}");
        // headers length larger than the frame can hold, prelude re-signed so the CRC passes
        java.nio.ByteBuffer.wrap(f, 4, 4).putInt(f.length);
        java.nio.ByteBuffer.wrap(f, 8, 4).putInt((int) EventStreamFrames.crc(f, 0, 8));
        assertThatThrownBy(() -> decoder(f).next()).isInstanceOf(IOException.class).hasMessageContaining("invalid lengths");

        byte[] huge = event("messageStop", "{}");
        java.nio.ByteBuffer.wrap(huge, 0, 4).putInt(BedrockEventStreamDecoder.MAX_FRAME_BYTES + 1);
        java.nio.ByteBuffer.wrap(huge, 8, 4).putInt((int) EventStreamFrames.crc(huge, 0, 8));
        assertThatThrownBy(() -> decoder(huge).next()).isInstanceOf(IOException.class).hasMessageContaining("invalid lengths");
    }

    /** The network hands over bytes in pieces; a frame boundary is not a read boundary. */
    @Test
    void framesArriveOneByteAtATime() throws IOException {
        byte[] bytes = concat(event("contentBlockDelta", DELTA), event("messageStop", "{\"stopReason\":\"end_turn\"}"),
                event("metadata", "{\"usage\":{\"inputTokens\":10,\"outputTokens\":2,\"totalTokens\":12}}"));
        BedrockEventStreamDecoder d = new BedrockEventStreamDecoder(EventStreamFrames.oneByteAtATime(bytes));
        assertThat(d.next().eventType()).isEqualTo("contentBlockDelta");
        assertThat(d.next().eventType()).isEqualTo("messageStop");
        assertThat(new String(d.next().payload(), StandardCharsets.UTF_8)).contains("\"totalTokens\":12");
        assertThat(d.next()).isNull();
    }

    @Test
    void exceptionAndErrorFramesCarryTheirHeaders() throws IOException {
        BedrockEventStreamDecoder.Frame ex = decoder(EventStreamFrames.exception("throttlingException",
                "{\"message\":\"Too many requests\"}")).next();
        assertThat(ex.isException()).isTrue();
        assertThat(ex.exceptionType()).isEqualTo("throttlingException");
        BedrockEventStreamDecoder.Frame err = decoder(EventStreamFrames.error("InternalError", "boom")).next();
        assertThat(err.isError()).isTrue();
        assertThat(err.errorCode()).isEqualTo("InternalError");
        assertThat(err.errorMessage()).isEqualTo("boom");
        assertThat(err.payload()).isEmpty();
    }

    /** Consistent lengths and CRCs, but the header block ends after a name, or inside a length. */
    @Test
    void aHeaderBlockCutInsideAHeaderIsAnIOException_notAnUnderflow() {
        byte[] nameOnly = ":x".getBytes(StandardCharsets.UTF_8);
        byte[] afterName = new byte[1 + nameOnly.length];
        afterName[0] = (byte) nameOnly.length;
        System.arraycopy(nameOnly, 0, afterName, 1, nameOnly.length);
        assertThatThrownBy(() -> decoder(EventStreamFrames.frameRaw(afterName, new byte[0])).next())
                .isInstanceOf(IOException.class).hasMessageContaining("truncated");

        byte[] oneByteLength = new byte[1 + nameOnly.length + 2];
        oneByteLength[0] = (byte) nameOnly.length;
        System.arraycopy(nameOnly, 0, oneByteLength, 1, nameOnly.length);
        oneByteLength[1 + nameOnly.length] = 7;     // string
        oneByteLength[2 + nameOnly.length] = 0;     // one of the two length bytes
        assertThatThrownBy(() -> decoder(EventStreamFrames.frameRaw(oneByteLength, new byte[0])).next())
                .isInstanceOf(IOException.class).hasMessageContaining("truncated");

        oneByteLength[1 + nameOnly.length] = 6;     // byte array, same shape
        assertThatThrownBy(() -> decoder(EventStreamFrames.frameRaw(oneByteLength, new byte[0])).next())
                .isInstanceOf(IOException.class).hasMessageContaining("truncated");
    }

    // ---- Published vectors: exact bytes from botocore's event-stream tests (aws/aws-cli,
    // tests/unit/botocore/test_eventstream.py), so the check does not rest on our own encoder ----

    private static byte[] hex(String h) { return java.util.HexFormat.of().parseHex(h); }

    @Test
    void decodesAwsPublishedVectors_emptyMessage() throws IOException {
        BedrockEventStreamDecoder d = decoder(hex("000000100000000005c248eb7d98c8ff"));
        BedrockEventStreamDecoder.Frame f = d.next();
        assertThat(f.stringHeaders()).isEmpty();
        assertThat(f.payload()).isEmpty();
        assertThat(d.next()).isNull();
    }

    @Test
    void decodesAwsPublishedVectors_payloadNoHeaders() throws IOException {
        BedrockEventStreamDecoder.Frame f = decoder(hex("0000001d00000000fd528c5a7b27666f6f273a27626172277dc3653936")).next();
        assertThat(f.stringHeaders()).isEmpty();
        assertThat(new String(f.payload(), StandardCharsets.UTF_8)).isEqualTo("{'foo':'bar'}");
    }

    @Test
    void decodesAwsPublishedVectors_oneStringHeader() throws IOException {
        BedrockEventStreamDecoder.Frame f = decoder(hex("0000003d0000002007fd83960c636f6e74656e742d747970650700106170706c69636174696f6e2f6a736f6e7b27666f6f273a27626172277d8d9c08b1")).next();
        assertThat(f.stringHeaders()).containsExactly(Map.entry("content-type", "application/json"));
        assertThat(f.contentType()).as("the vector's header is unprefixed; Bedrock's is :content-type").isNull();
        assertThat(new String(f.payload(), StandardCharsets.UTF_8)).isEqualTo("{'foo':'bar'}");
    }

    /** All ten header types in one frame: only the string ('7' = "utf8") is kept; the rest are stepped over. */
    @Test
    void decodesAwsPublishedVectors_allHeaderTypes() throws IOException {
        BedrockEventStreamDecoder.Frame f = decoder(hex("000000620000005203b5cb9c0130000131010132020201330300030134040000000401350500000000000000050136060005627974657301370700047574663801380800000000000000080139093031323334353637383961626364656663353671")).next();
        assertThat(f.stringHeaders()).containsExactly(Map.entry("7", "utf8"));
        assertThat(f.payload()).isEmpty();
    }

    @Test
    void decodesAwsPublishedVectors_errorEvent() throws IOException {
        BedrockEventStreamDecoder.Frame f = decoder(hex("0000005200000042bf23637e0d3a6d6573736167652d747970650700056572726f720b3a6572726f722d636f6465070004636f64650e3a6572726f722d6d6573736167650700076d6573736167656b6cea3d")).next();
        assertThat(f.isError()).isTrue();
        assertThat(f.errorCode()).isEqualTo("code");
        assertThat(f.errorMessage()).isEqualTo("message");
        assertThat(f.payload()).isEmpty();
    }

    private static byte[] resign(byte[] f) {
        java.nio.ByteBuffer.wrap(f, f.length - 4, 4).putInt((int) EventStreamFrames.crc(f, 0, f.length - 4));
        return f;
    }
}
