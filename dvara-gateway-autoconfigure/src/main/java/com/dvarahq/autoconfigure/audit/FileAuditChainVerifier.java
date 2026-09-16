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

import com.dvarahq.core.audit.HmacSigner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Checks a {@link FileAuditWriter} chain.
 *
 * <p>A chain nobody can check is a column of hex that reads as evidence and is not, so this class
 * is half of the feature. {@link Result} reports what it covered as well as a verdict:
 * {@code valid} over zero lines means nothing was looked at, and {@link Result#describe()} says
 * so rather than letting the boolean be read alone.
 *
 * <p>What a failure tells you:
 * <ul>
 *   <li><b>HMAC mismatch</b>: that line's contents were altered after signing, or a different
 *       secret signed it.</li>
 *   <li><b>Broken link</b>: the line is internally consistent but does not point at its
 *       predecessor, which is what removing, reordering or inserting a line looks like.</li>
 * </ul>
 *
 * <p>It stops at the first failure and says where. Everything after a break is unanchored rather
 * than wrong, so a count of bad lines past that point would be invented precision.
 *
 * <p>Lines are not parsed into objects and re-rendered. The payload is handed to
 * {@link HmacSigner#canonicalizeRendered} as the exact text that was signed, because a
 * parse-and-re-serialize round trip would have to reproduce the original bytes, and number
 * formatting alone can break that and surface as a tampered record.
 */
public final class FileAuditChainVerifier {

    private FileAuditChainVerifier() {}

    /**
     * @param valid        whether every line checked out
     * @param linesChecked how many lines were verified; {@code valid} over 0 means nothing was read
     * @param failure      what went wrong and where, or null when valid
     */
    public record Result(boolean valid, long linesChecked, String failure) {

        /** A summary that states coverage, so a verdict cannot be read without it. */
        public String describe() {
            if (!valid) {
                return "INVALID after " + linesChecked + " good line(s): " + failure;
            }
            if (linesChecked == 0) {
                return "no audit lines found — vacuously valid, which is not the same as intact. "
                        + "Check the path is the one being written to.";
            }
            return "valid: " + linesChecked + " line(s), chain unbroken";
        }
    }

    public static Result verify(Path file, String secret) throws IOException {
        if (!Files.exists(file)) {
            return new Result(true, 0, null);
        }
        // Streamed, not materialised: the file is append-only with no rotation, so its size is
        // unbounded, and verification is a single forward pass that needs only the previous HMAC.
        try (var stream = Files.lines(file, StandardCharsets.UTF_8)) {
            var it = stream.filter(l -> !l.isBlank()).iterator();
            return verifyLines(it, secret);
        }
    }

    private static Result verifyLines(java.util.Iterator<String> lines, String secret) {
        String expectedPrevious = null;
        long checked = 0;
        int number = 0;
        while (lines.hasNext()) {
            String line = lines.next();
            number++;

            String hmac = extract(line, "hmac");
            String eventId = extract(line, "eventId");
            String timestamp = extract(line, "timestamp");
            String eventType = extract(line, "eventType");
            String payload = rawPayload(line);
            if (hmac == null || eventId == null || timestamp == null || eventType == null || payload == null) {
                return new Result(false, checked,
                        "line " + number + " is not a chain record — it is missing one of "
                                + "hmac/eventId/timestamp/eventType/payload");
            }

            String previousHash = extract(line, "previousHash");
            if (!Objects.equals(previousHash, expectedPrevious)) {
                return new Result(false, checked,
                        "line " + number + " (" + eventId + ") does not link to its predecessor: "
                                + "previousHash=" + previousHash + ", expected " + expectedPrevious
                                + ". A line has been removed, reordered or inserted.");
            }

            String canonical = HmacSigner.canonicalizeRendered(
                    eventId, timestamp, extract(line, "workspaceId"), eventType, payload, previousHash);
            if (!HmacSigner.sign(canonical, secret).equals(hmac)) {
                return new Result(false, checked,
                        "line " + number + " (" + eventId + ") fails its own HMAC: the record was "
                                + "altered after signing, or a different secret signed it.");
            }

            expectedPrevious = hmac;
            checked++;
        }
        return new Result(true, checked, null);
    }

    /** The payload object's text, exactly as written — brace-matched, string-aware. */
    static String rawPayload(String line) {
        int[] span = payloadSpan(line);
        return span == null ? null : line.substring(span[0], span[1]);
    }

    /**
     * Where the payload object sits on the line, as {@code [open, closeExclusive]}, found by brace
     * matching that skips string contents; null if there is none. The envelope's own fields lie
     * outside this span and {@link #extract} looks only there: the payload is a free-form map, and
     * a key in it named {@code hmac} or {@code previousHash} must not be read as the envelope's.
     */
    static int[] payloadSpan(String line) {
        int at = line.indexOf("\"payload\":");
        if (at < 0) {
            return null;
        }
        int open = line.indexOf('{', at);
        if (open < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}' && --depth == 0) {
                return new int[] {open, i + 1};
            }
        }
        return null;
    }

    /** The first occurrence of {@code key} that is not inside the payload object. */
    private static int indexOfEnvelopeKey(String line, String key) {
        int[] span = payloadSpan(line);
        if (span == null) {
            return line.indexOf(key);
        }
        int before = line.indexOf(key);
        if (before >= 0 && before < span[0]) {
            return before;
        }
        return line.indexOf(key, span[1]);
    }

    /**
     * Read one flat top-level field.
     *
     * <p>Deliberately not a JSON parse: this runs <em>before</em> the line is trusted, and the fields
     * wanted are flat and written by a single renderer. Returns null both for a literal {@code null}
     * and for an absent field — callers make no distinction between them.
     */
    static String extract(String line, String field) {
        String key = "\"" + field + "\":";
        int at = indexOfEnvelopeKey(line, key);
        if (at < 0) {
            return null;
        }
        int valueStart = at + key.length();
        if (valueStart >= line.length() || line.startsWith("null", valueStart)) {
            return null;
        }
        if (line.charAt(valueStart) == '"') {
            StringBuilder sb = new StringBuilder();
            boolean escaped = false;
            for (int i = valueStart + 1; i < line.length(); i++) {
                char c = line.charAt(i);
                if (escaped) {
                    if (c == 'u' && i + 4 < line.length()) {
                        // the writer escapes control characters as backslash-u plus four hex digits.
                        sb.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16));
                        i += 4;
                    } else {
                        sb.append(unescape(c));
                    }
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    return sb.toString();
                } else {
                    sb.append(c);
                }
            }
            return null;
        }
        int end = valueStart;
        while (end < line.length() && "-0123456789.eE+".indexOf(line.charAt(end)) >= 0) {
            end++;
        }
        return end > valueStart ? line.substring(valueStart, end) : null;
    }

    private static char unescape(char c) {
        return switch (c) {
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            default -> c;
        };
    }

    /**
     * Check a chain from the command line.
     *
     * <p>Usage: {@code FileAuditChainVerifier <path>}, with the secret in
     * {@code DVARA_AUDIT_HMAC_SECRET}. The secret is taken from the environment and never from an
     * argument, because arguments are visible to every other process on the host through
     * {@code ps}.
     *
     * <p>Exit codes, so the check can run from cron:
     * <ul>
     *   <li><b>0</b>: the chain verified, over at least one record.</li>
     *   <li><b>1</b>: a record failed. The reason and the line number are on stdout.</li>
     *   <li><b>2</b>: nothing was checked: no path, no secret, an unreadable file, or a file with
     *       no records in it.</li>
     * </ul>
     *
     * <p>An empty chain exits 2, not 0. It is vacuously valid, but a check that answers "fine" when
     * it read nothing hides the likeliest mistake, which is pointing at the wrong file.
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * The body of {@link #main}, returning the status instead of calling {@code System.exit}, so the
     * exit codes above are testable.
     */
    static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) {
        if (args.length != 1 || args[0].isBlank()) {
            err.println("usage: FileAuditChainVerifier <path to the audit log>");
            err.println("       the HMAC secret is read from DVARA_AUDIT_HMAC_SECRET");
            return 2;
        }
        String secret = System.getenv("DVARA_AUDIT_HMAC_SECRET");
        if (secret == null || secret.isBlank()) {
            err.println("DVARA_AUDIT_HMAC_SECRET is not set. Verifying needs the same secret that "
                    + "signed the chain; without it every line reads as tampered.");
            return 2;
        }
        return run(Path.of(args[0]), secret, out, err);
    }

    /** The same, with the secret already resolved — the form a test can drive. */
    static int run(Path file, String secret, java.io.PrintStream out, java.io.PrintStream err) {
        if (!Files.exists(file)) {
            err.println("No file at " + file + " — nothing was checked.");
            return 2;
        }
        Result result;
        try {
            result = verify(file, secret);
        } catch (IOException e) {
            err.println("Could not read " + file + ": " + e.getMessage() + " — nothing was checked.");
            return 2;
        }
        out.println(file + ": " + result.describe());
        if (!result.valid()) {
            return 1;
        }
        return result.linesChecked() == 0 ? 2 : 0;
    }
}
