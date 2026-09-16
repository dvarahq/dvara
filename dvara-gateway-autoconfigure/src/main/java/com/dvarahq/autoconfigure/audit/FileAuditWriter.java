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
package com.dvarahq.autoconfigure.audit;

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.audit.HmacSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * An HMAC-chained audit log written to a local file: the audit sink for a build with no other
 * {@code AuditWriter} registered.
 *
 * <h2>The chain, and what it is worth</h2>
 *
 * <p>One JSON object per line. Each line carries the HMAC of {@link HmacSigner#canonicalize} over
 * the event and the previous line's HMAC, so changing or removing any earlier event breaks every
 * HMAC after it. {@link FileAuditChainVerifier} checks that.
 *
 * <p><b>This is tamper-evident, not tamper-proof.</b> Anyone holding the HMAC secret can rewrite
 * the file and re-chain it undetectably; the guarantee is against a reader or an intruder who does
 * not have it, not against the operator. Proof to a third party needs asymmetric signing, which
 * this does not claim. Two consequences:
 * <ul>
 *   <li>The shipped default secret makes the chain worthless, because everyone has it. The
 *       auto-configuration refuses to start on it.</li>
 *   <li>Nothing here protects the file itself. Deleting it loses the record; the chain only tells
 *       a later reader that what remains is internally consistent, and where it stops.</li>
 * </ul>
 *
 * <h2>Restart continuity</h2>
 *
 * <p>On construction the last line is read and its HMAC becomes the next {@code previousHash}, so
 * a restart continues one chain rather than starting a second. Only the tail of the file is read,
 * backwards, because the file is never rotated here and can be large. A missing or empty file is
 * the genesis case ({@code previousHash} null, sequence 1). A file whose last line cannot be parsed
 * is refused rather than re-chained from nothing, since appending a fresh chain onto a damaged one
 * produces a file that verifies as two chains and reads as one.
 *
 * <h2>Durability</h2>
 *
 * <p>Flushed on every event: an event still in a buffer is not evidence. It is not {@code fsync}'d,
 * so a machine-level crash can lose the tail; the chain then verifies up to the last durable line
 * and stops, which is detectable. Writes are serialized on this object, because a chain is a
 * sequence.
 *
 * <h2>Not in scope</h2>
 *
 * <p>No rotation, no retention, no shipping anywhere. A file that grows without bound is the
 * operator's to rotate, and rotating it splits the chain, so keep the segments.
 */
public class FileAuditWriter implements AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(FileAuditWriter.class);

    private final Path file;
    private final String secret;
    private final BufferedWriter out;

    private String previousHash;
    private long sequence;

    public FileAuditWriter(Path file, String secret) {
        this.file = file;
        this.secret = secret;
        Tail tail = readTail(file);
        this.previousHash = tail.hmac();
        this.sequence = tail.sequence() + 1;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Refuse rather than degrade. An audit sink that cannot open its file and carries on is
            // the NoOp with a longer class name, and the operator asked for a record.
            throw new UncheckedIOException(
                    "Cannot open the audit log at " + file + ". Set dvara.audit.file.path to a "
                            + "writable location on a volume that survives a restart, or unset it to "
                            + "disable audit writing.", e);
        }
        log.info("Audit events are being written to {} — HMAC-chained, resuming at sequence {}. "
                        + "Verify with FileAuditChainVerifier; the chain is tamper-evident, not "
                        + "tamper-proof.",
                file, sequence);
    }

    @Override
    public synchronized void write(AuditEvent event) {
        // Sign the payload exactly as the line will carry it. The line is strict JSON, which the
        // database canonical form is not, and what the verifier recomputes over is the line.
        String renderedPayload = HmacSigner.renderPayload(event.payload());
        String canonical = HmacSigner.canonicalizeRendered(
                event.eventId(),
                DateTimeFormatter.ISO_INSTANT.format(event.timestamp().truncatedTo(ChronoUnit.MICROS)),
                event.workspaceId(),
                event.eventType(),
                renderedPayload,
                previousHash);
        String hmac = HmacSigner.sign(canonical, secret);
        String line = render(event, previousHash, sequence, hmac, Instant.now(), renderedPayload);
        try {
            out.write(line);
            out.newLine();
            // Per event, deliberately: see the durability note on the class.
            out.flush();
        } catch (IOException e) {
            // Loud, and does NOT advance the chain — a failed append must not leave the next event
            // pointing at an HMAC that is on no line of the file.
            log.error("Failed to append audit event {} to {}: {}", event.eventId(), file, e.getMessage());
            return;
        }
        previousHash = hmac;
        sequence++;
    }

    /** The sequence the next event will carry. Exposed for tests and for the startup line. */
    public synchronized long nextSequence() {
        return sequence;
    }

    /**
     * One line of the log.
     *
     * <p>Rendered by hand rather than with Jackson, for the same reason {@link HmacSigner} is: the
     * payload has to serialize identically to what was signed, and a mapper's configuration is a
     * second thing that can differ between the writer and a verifier. {@code payload} therefore goes
     * through the signer's own rendering.
     */
    static String render(AuditEvent event, String previousHash, long sequence, String hmac, Instant signedAt) {
        return render(event, previousHash, sequence, hmac, signedAt, HmacSigner.renderPayload(event.payload()));
    }

    static String render(AuditEvent event, String previousHash, long sequence, String hmac, Instant signedAt,
                         String renderedPayload) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        field(sb, "seq", String.valueOf(sequence), false);
        sb.append(',');
        field(sb, "eventId", event.eventId(), true);
        sb.append(',');
        field(sb, "timestamp",
                DateTimeFormatter.ISO_INSTANT.format(event.timestamp().truncatedTo(ChronoUnit.MICROS)), true);
        sb.append(',');
        field(sb, "workspaceId", event.workspaceId(), true);
        sb.append(',');
        field(sb, "eventType", event.eventType(), true);
        sb.append(",\"payload\":").append(renderedPayload);
        sb.append(',');
        field(sb, "previousHash", previousHash, true);
        sb.append(',');
        field(sb, "hmac", hmac, true);
        sb.append(',');
        field(sb, "signedAt", DateTimeFormatter.ISO_INSTANT.format(signedAt.truncatedTo(ChronoUnit.MICROS)), true);
        return sb.append('}').toString();
    }

    private static void field(StringBuilder sb, String name, String value, boolean quoted) {
        sb.append('"').append(name).append("\":");
        if (value == null) {
            sb.append("null");
        } else if (quoted) {
            sb.append('"').append(HmacSigner.escapeJsonStrict(value)).append('"');
        } else {
            sb.append(value);
        }
    }

    /** The last line's HMAC and sequence, or the genesis values. */
    private static Tail readTail(Path file) {
        if (!Files.exists(file)) {
            return new Tail(null, 0);
        }
        String last;
        try {
            // Read from the end: the file grows for the life of the install, and one line is needed.
            last = lastNonBlankLine(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the existing audit log at " + file
                    + " to resume its chain. Move it aside to start a new chain.", e);
        }
        if (last == null) {
            return new Tail(null, 0);
        }
        String hmac = FileAuditChainVerifier.extract(last, "hmac");
        String seq = FileAuditChainVerifier.extract(last, "seq");
        if (hmac == null || seq == null) {
            throw new IllegalStateException("The last line of the audit log at " + file
                    + " carries no hmac/seq, so its chain cannot be resumed. Appending a fresh chain "
                    + "would produce a file that verifies as two chains and reads as one. Move the "
                    + "file aside to start a new chain deliberately.");
        }
        return new Tail(hmac, Long.parseLong(seq));
    }

    /** Bytes read per step while looking for the last line from the end of the file. */
    static final int TAIL_CHUNK = 8192;

    /**
     * The last line that is not blank, or null when there is none, read backwards from the end of the file.
     *
     * <p>It works on bytes and decodes only the line it returns. A newline byte never occurs inside a UTF-8
     * character of several bytes, so the line can be cut out before decoding, and a character that falls across
     * two reads is decoded whole. Nothing before the last line is decoded at all.
     */
    static String lastNonBlankLine(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            long position = channel.size();
            byte[] tail = new byte[0];
            while (true) {
                int contentEnd = tail.length;
                while (contentEnd > 0 && isBlankByte(tail[contentEnd - 1])) {
                    contentEnd--;
                }
                if (contentEnd > 0) {
                    int newline = contentEnd - 1;
                    while (newline >= 0 && tail[newline] != '\n') {
                        newline--;
                    }
                    if (newline >= 0 || position == 0) {
                        return new String(tail, newline + 1, contentEnd - newline - 1, StandardCharsets.UTF_8);
                    }
                } else if (position == 0) {
                    return null;
                }
                int step = (int) Math.min(TAIL_CHUNK, position);
                position -= step;
                ByteBuffer chunk = ByteBuffer.allocate(step);
                channel.position(position);
                int read;
                do {
                    read = channel.read(chunk);
                } while (read >= 0 && chunk.hasRemaining());
                byte[] grown = new byte[step + tail.length];
                System.arraycopy(chunk.array(), 0, grown, 0, step);
                System.arraycopy(tail, 0, grown, step, tail.length);
                tail = grown;
            }
        }
    }

    private static boolean isBlankByte(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r' || b == '\f' || b == 0x0B;
    }

    private record Tail(String hmac, long sequence) {}
}