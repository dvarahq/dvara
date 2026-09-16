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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The local audit chain: what it records, and what it detects.
 *
 * <p>The tamper cases carry the claim: a chain that cannot detect an edit is a column of hex.
 */
class FileAuditChainTest {

    private static final String SECRET = "test-secret-not-the-shipped-default";

    @TempDir
    Path dir;

    private AuditEvent event(String type, String workspace, Map<String, Object> payload) {
        return new AuditEvent("evt-" + type + "-" + workspace, Instant.parse("2026-08-28T10:15:30.123456Z"),
                workspace, type, payload);
    }

    private Path writeThree() {
        Path file = dir.resolve("audit.log");
        FileAuditWriter writer = new FileAuditWriter(file, SECRET);
        writer.write(event("POLICY_DENIED", "acme", Map.of("policy", "no-secrets", "count", 2)));
        writer.write(event("PII_DETECTED", "acme", Map.of("types", List.of("SSN", "EMAIL"))));
        writer.write(event("GATEWAY_RESPONSE", "beta", Map.of("status", 200)));
        return file;
    }

    // --- writing -------------------------------------------------------------------------

    @Test
    void everyEventBecomesOneLine() throws IOException {
        Path file = writeThree();
        assertThat(Files.readAllLines(file)).hasSize(3);
    }

    @Test
    void aFreshChainVerifies() throws IOException {
        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(writeThree(), SECRET);
        assertThat(result.valid()).isTrue();
        assertThat(result.linesChecked())
                .as("the verdict must cover every line, not a sample")
                .isEqualTo(3);
    }

    @Test
    void theChainLinksEachLineToTheOneBefore() throws IOException {
        List<String> lines = Files.readAllLines(writeThree());
        assertThat(FileAuditChainVerifier.extract(lines.get(0), "previousHash"))
                .as("the first line has no predecessor")
                .isNull();
        for (int i = 1; i < lines.size(); i++) {
            assertThat(FileAuditChainVerifier.extract(lines.get(i), "previousHash"))
                    .isEqualTo(FileAuditChainVerifier.extract(lines.get(i - 1), "hmac"));
        }
    }

    // --- a payload that borrows the envelope's names ---------------------------------------

    /** The payload is a free-form map; a key in it named like an envelope field must not be read as one. */
    private static Map<String, Object> decoyPayload() {
        Map<String, Object> p = new java.util.LinkedHashMap<>();
        p.put("hmac", "0000000000000000000000000000000000000000000000000000000000000000");
        p.put("previousHash", "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        p.put("signedAt", "1999-01-01T00:00:00Z");
        p.put("seq", 999);
        p.put("eventType", "NOT_THE_ENVELOPE");
        return p;
    }

    private Path writeDecoys() {
        Path file = dir.resolve("audit.log");
        FileAuditWriter writer = new FileAuditWriter(file, SECRET);
        writer.write(event("WEBHOOK_DELIVERED", "acme", decoyPayload()));
        writer.write(event("WEBHOOK_DELIVERED", "acme", decoyPayload()));
        return file;
    }

    /**
     * previousHash, hmac and signedAt are written after the payload, so a first-match lookup would
     * find the payload's copy. The envelope fields are read from outside the payload span.
     */
    @Test
    void aPayloadKeyNamedLikeAnEnvelopeFieldIsNotReadAsOne() throws IOException {
        Path file = writeDecoys();
        List<String> lines = Files.readAllLines(file);
        assertThat(FileAuditChainVerifier.extract(lines.get(0), "hmac")).doesNotStartWith("0000");
        assertThat(FileAuditChainVerifier.extract(lines.get(0), "previousHash")).isNotEqualTo(decoyPayload().get("previousHash"));
        assertThat(FileAuditChainVerifier.extract(lines.get(0), "seq")).isEqualTo("1");
        assertThat(FileAuditChainVerifier.extract(lines.get(0), "eventType")).isEqualTo("WEBHOOK_DELIVERED");
        assertThat(FileAuditChainVerifier.extract(lines.get(1), "previousHash"))
                .as("the second line links to the first line's real hmac, not to the payload's")
                .isEqualTo(FileAuditChainVerifier.extract(lines.get(0), "hmac"));
        assertThat(FileAuditChainVerifier.verify(file, SECRET).valid()).isTrue();
    }

    /** The writer resumes a chain by extracting the last line's hmac and seq the same way. */
    @Test
    void resumingAfterADecoyLineLinksToTheRealHmac() throws IOException {
        Path file = writeDecoys();
        new FileAuditWriter(file, SECRET).write(event("GATEWAY_RESPONSE", "acme", Map.of("status", 200)));
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(3);
        assertThat(FileAuditChainVerifier.extract(lines.get(2), "previousHash"))
                .isEqualTo(FileAuditChainVerifier.extract(lines.get(1), "hmac"));
        assertThat(FileAuditChainVerifier.extract(lines.get(2), "seq")).isEqualTo("3");
        assertThat(FileAuditChainVerifier.verify(file, SECRET).valid()).isTrue();
    }

    /** Tampering with the real hmac on such a line is still caught. */
    @Test
    void editingTheRealHmacOnADecoyLineIsDetected() throws IOException {
        Path file = writeDecoys();
        List<String> lines = Files.readAllLines(file);
        String real = FileAuditChainVerifier.extract(lines.get(0), "hmac");
        int at = lines.get(0).lastIndexOf(real);
        String edited = lines.get(0).substring(0, at) + real.substring(0, 60) + "dead" + lines.get(0).substring(at + real.length());
        Files.write(file, List.of(edited, lines.get(1)), StandardCharsets.UTF_8);
        assertThat(FileAuditChainVerifier.verify(file, SECRET).valid()).isFalse();
    }

    // --- the part that makes it evidence -------------------------------------------------

    @Test
    void editingAPayloadIsDetected() throws IOException {
        Path file = writeThree();
        tamper(file, 1, s -> s.replace("\"no-secrets\"", "\"something-else\""));

        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(file, SECRET);
        assertThat(result.valid()).isFalse();
        assertThat(result.failure()).contains("line 1").contains("fails its own HMAC");
        assertThat(result.linesChecked())
                .as("it stops at the break rather than guessing about what follows")
                .isZero();
    }

    @Test
    void deletingALineIsDetected() throws IOException {
        Path file = writeThree();
        List<String> lines = Files.readAllLines(file);
        Files.write(file, List.of(lines.get(0), lines.get(2)), StandardCharsets.UTF_8);

        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(file, SECRET);
        assertThat(result.valid()).isFalse();
        assertThat(result.failure())
                .as("a removed line leaves a gap in the links, not a bad signature")
                .contains("does not link to its predecessor");
    }

    @Test
    void reorderingLinesIsDetected() throws IOException {
        Path file = writeThree();
        List<String> lines = Files.readAllLines(file);
        Files.write(file, List.of(lines.get(1), lines.get(0), lines.get(2)), StandardCharsets.UTF_8);

        assertThat(FileAuditChainVerifier.verify(file, SECRET).valid()).isFalse();
    }

    @Test
    void appendingAForgedLineIsDetected() throws IOException {
        Path file = writeThree();
        // Signed correctly, but with a secret the verifier does not hold.
        FileAuditWriter forger = new FileAuditWriter(file, "a-different-secret");
        forger.write(event("GATEWAY_RESPONSE", "attacker", Map.of("status", 200)));

        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(file, SECRET);
        assertThat(result.valid()).isFalse();
        assertThat(result.linesChecked())
                .as("the three genuine lines still verify; the forgery is the fourth")
                .isEqualTo(3);
        assertThat(result.failure()).contains("line 4");
    }

    @Test
    void theWrongSecretVerifiesNothing() throws IOException {
        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(writeThree(), "wrong");
        assertThat(result.valid()).isFalse();
        assertThat(result.linesChecked()).isZero();
    }

    /**
     * The control for every test above.
     *
     * <p>Without it, a verifier that returned {@code false} unconditionally would pass all six.
     */
    @Test
    void anUntamperedChainIsNotReportedBroken() throws IOException {
        assertThat(FileAuditChainVerifier.verify(writeThree(), SECRET).valid()).isTrue();
    }

    // --- the line count is part of the answer --------------------------------------------

    @Test
    void anEmptyChainSaysItLookedAtNothing() throws IOException {
        FileAuditChainVerifier.Result result =
                FileAuditChainVerifier.verify(dir.resolve("never-written.log"), SECRET);
        assertThat(result.valid()).isTrue();
        assertThat(result.linesChecked()).isZero();
        assertThat(result.describe())
                .as("valid over zero lines must not read as an intact chain")
                .contains("vacuously valid");
    }

    // --- restart -------------------------------------------------------------------------

    @Test
    void aRestartContinuesTheSameChain() throws IOException {
        Path file = writeThree();

        FileAuditWriter reopened = new FileAuditWriter(file, SECRET);
        assertThat(reopened.nextSequence()).isEqualTo(4);
        reopened.write(event("GATEWAY_RESPONSE", "acme", Map.of("status", 204)));

        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(file, SECRET);
        assertThat(result.valid())
                .as("a restart must extend the chain, not begin a second one inside the same file")
                .isTrue();
        assertThat(result.linesChecked()).isEqualTo(4);
    }

    @Test
    void aDamagedTailIsRefusedRatherThanSilentlyRechained() throws IOException {
        Path file = writeThree();
        List<String> lines = Files.readAllLines(file);
        lines.set(2, "{\"seq\":3,\"truncated\":true");
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new FileAuditWriter(file, SECRET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("two chains");
    }

    @Test
    void onlyTheEndOfTheFileIsReadToResume() throws IOException {
        // The file is never rotated here, so a restart must not read all of it to find its last line. Bytes near
        // the start that are not UTF-8 are never decoded when the file is read from its end, so they cannot
        // stop a restart; reading from the start fails on them.
        Path file = writeThree();
        byte[] damaged = Files.readAllBytes(file);
        damaged[1] = (byte) 0xFF;
        damaged[2] = (byte) 0xFE;
        Files.write(file, damaged);

        assertThat(new FileAuditWriter(file, SECRET).nextSequence()).isEqualTo(4);
    }

    @Test
    void theLastLineIsFoundPastTrailingBlankLinesAndWindowsLineEnds() throws IOException {
        Path file = writeThree();
        Files.writeString(file, Files.readString(file).replace("\n", "\r\n") + "\r\n   \n\n");

        assertThat(new FileAuditWriter(file, SECRET).nextSequence()).isEqualTo(4);
    }

    @Test
    void aLastLineLongerThanOneReadIsReadWhole() throws IOException {
        Path file = dir.resolve("audit.log");
        FileAuditWriter writer = new FileAuditWriter(file, SECRET);
        writer.write(event("GATEWAY_RESPONSE", "acme", Map.of("status", 200)));
        writer.write(event("PII_DETECTED", "acme", Map.of("note", "é".repeat(FileAuditWriter.TAIL_CHUNK) + "x")));

        FileAuditWriter reopened = new FileAuditWriter(file, SECRET);
        assertThat(reopened.nextSequence()).isEqualTo(3);
        reopened.write(event("GATEWAY_RESPONSE", "beta", Map.of("status", 204)));
        assertThat(FileAuditChainVerifier.verify(file, SECRET).valid())
                .as("the restart linked to the long line's HMAC, so the chain still verifies")
                .isTrue();
    }

    @Test
    void aFileOfBlankLinesStartsANewChain() throws IOException {
        Path file = dir.resolve("audit.log");
        Files.writeString(file, "\n  \n\r\n");

        assertThat(new FileAuditWriter(file, SECRET).nextSequence()).isEqualTo(1);
    }

    // --- payload fidelity ----------------------------------------------------------------

    /**
     * The reason {@code canonicalizeRendered} exists.
     *
     * <p>The verifier hands the payload text back to the signer instead of parsing and re-rendering
     * it. These values are the ones a round trip would most likely alter — a number that could come
     * back as a double, a nested map whose key order could change, a string needing escaping.
     */
    @Test
    void awkwardPayloadsStillVerify() throws IOException {
        Path file = dir.resolve("awkward.log");
        FileAuditWriter writer = new FileAuditWriter(file, SECRET);
        writer.write(event("A", "w", Map.of("n", 1, "d", 1.5, "b", true)));
        writer.write(event("B", "w", Map.of("nested", Map.of("z", 1, "a", 2), "list", List.of(1, "two"))));
        writer.write(event("C", "w", Map.of("quote", "he said \"hi\"", "newline", "a\nb", "tab", "a\tb")));
        writer.write(new AuditEvent("evt-null-ws", Instant.parse("2026-08-28T10:15:30.123456Z"),
                null, "NO_WORKSPACE", Map.of()));

        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(file, SECRET);
        assertThat(result.describe()).isEqualTo("valid: 4 line(s), chain unbroken");
    }

    private void tamper(Path file, int lineNumber, java.util.function.UnaryOperator<String> edit)
            throws IOException {
        List<String> lines = Files.readAllLines(file);
        lines.set(lineNumber - 1, edit.apply(lines.get(lineNumber - 1)));
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    // --- every line is JSON to any reader, and the chain still verifies --------------

    private static String everyControlCharacter() {
        StringBuilder sb = new StringBuilder("model");
        for (char c = 0; c < 0x20; c++) sb.append(c);
        return sb.append('"').append('\\').append((char) 0x7F).append("end").toString();
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String line) throws IOException {
        // Jackson's default rejects an unescaped control character, which is what a SIEM does too.
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(line);
    }

    @Test
    void everyControlCharacterYieldsALineAJsonParserAccepts() throws IOException {
        String s = everyControlCharacter();
        Path file = dir.resolve("audit.log");
        new FileAuditWriter(file, SECRET).write(event("GATEWAY_RESPONSE", "acme",
                Map.of("model", s, "nested", Map.of("k\u0008ey", List.of(s, 1)))));
        String line = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);

        com.fasterxml.jackson.databind.JsonNode json = parse(line);
        assertThat(json.get("payload").get("model").asText()).as("round-trips through a real parser").isEqualTo(s);
        assertThat(json.get("payload").get("nested").get("k\u0008ey").get(0).asText()).isEqualTo(s);
        assertThat(line).contains("\\u0008").doesNotContain("\u0008");
        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(file, SECRET);
        assertThat(result.valid()).as(result.failure()).isTrue();
    }

    @Test
    void controlCharactersInTheEnvelopeFieldsVerify() throws IOException {
        Path file = dir.resolve("audit.log");
        new FileAuditWriter(file, SECRET).write(new AuditEvent("evt\u0001", Instant.parse("2026-08-28T10:15:30.123456Z"),
                "acme\u0008x", "TYPE\u000C", Map.of("k", "v")));
        String line = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
        assertThat(parse(line).get("workspaceId").asText()).isEqualTo("acme\u0008x");
        assertThat(FileAuditChainVerifier.extract(line, "workspaceId")).isEqualTo("acme\u0008x");
        assertThat(FileAuditChainVerifier.verify(file, SECRET).valid()).isTrue();
    }

    @Test
    void aQuoteAndABackslashInTheWorkspaceIdStillVerify() throws IOException {
        Path file = dir.resolve("audit.log");
        new FileAuditWriter(file, SECRET).write(event("X", "acme\"quoted\\slashed", Map.of("k", "v")));
        String line = Files.readAllLines(file, StandardCharsets.UTF_8).get(0);
        assertThat(parse(line).get("workspaceId").asText()).isEqualTo("acme\"quoted\\slashed");
        assertThat(FileAuditChainVerifier.verify(file, SECRET).valid()).isTrue();
    }

    /**
     * A line carrying raw control characters is not JSON, but the writer resumes its chain after it
     * and appends strict lines, and the verifier, which recomputes over each line's own bytes,
     * accepts both as one chain.
     */
    @Test
    void aLegacyLineFollowedByAStrictLineVerifiesAsOneChain() throws IOException {
        String ts = "2026-08-28T10:15:30.123456Z";
        String rawPayload = "{\"k\":\"a\u0008b\"}";   // the backspace is written raw, not escaped
        String hmac = com.dvarahq.core.audit.HmacSigner.sign(
                com.dvarahq.core.audit.HmacSigner.canonicalizeRendered("evt-legacy", ts, "acme", "LEGACY", rawPayload, null),
                SECRET);
        String legacy = "{\"seq\":1,\"eventId\":\"evt-legacy\",\"timestamp\":\"" + ts + "\",\"workspaceId\":\"acme\","
                + "\"eventType\":\"LEGACY\",\"payload\":" + rawPayload + ",\"previousHash\":null,"
                + "\"hmac\":\"" + hmac + "\",\"signedAt\":\"" + ts + "\"}";
        Path file = dir.resolve("audit.log");
        Files.writeString(file, legacy + "\n", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> parse(legacy)).as("the legacy line is genuinely not JSON").isInstanceOf(IOException.class);

        new FileAuditWriter(file, SECRET).write(event("STRICT", "acme", Map.of("k", "a\u0008b")));

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        assertThat(parse(lines.get(1)).get("payload").get("k").asText()).isEqualTo("a\u0008b");
        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(file, SECRET);
        assertThat(result.valid()).as(result.failure()).isTrue();
        assertThat(result.linesChecked()).isEqualTo(2);
    }

    // --- the command line, which is what makes the chain checkable at all ----------------

    /**
     * The exit code is the whole point of a command-line entry: this belongs in a cron job, and a
     * cron job reads the status rather than the sentence. {@code run} returns it instead of calling
     * {@code System.exit} so these cases are reachable at all.
     */
    private record Run(int status, String out, String err) {}

    private Run run(Path file) {
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        int status = FileAuditChainVerifier.run(file, SECRET,
                new java.io.PrintStream(out, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anIntactChainExitsZeroAndSaysWhatItCovered() {
        Run run = run(writeThree());
        assertThat(run.status()).isZero();
        assertThat(run.out()).contains("valid: 3 line(s)");
    }

    @Test
    void aTamperedChainExitsOneAndNamesTheLine() throws IOException {
        Path file = writeThree();
        tamper(file, 2, line -> line.replace("\"SSN\"", "\"NOTHING\""));

        Run run = run(file);
        assertThat(run.status()).isEqualTo(1);
        assertThat(run.out()).contains("INVALID").contains("line 2");
    }

    @Test
    void anEmptyChainExitsTwoBecauseNothingWasChecked() throws IOException {
        // Vacuously valid must not read as "fine" to a script. The likeliest cause is the wrong
        // path, not an install that has never written an audit event.
        Path file = dir.resolve("empty.log");
        Files.writeString(file, "");

        Run run = run(file);
        assertThat(run.status()).isEqualTo(2);
        assertThat(run.out()).contains("not the same as intact");
    }

    @Test
    void aMissingFileExitsTwoRatherThanReportingAGoodChain() {
        Run run = run(dir.resolve("nothing-here.log"));
        assertThat(run.status()).isEqualTo(2);
        assertThat(run.err()).contains("nothing was checked");
    }

    @Test
    void noPathExitsTwoWithUsage() {
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        int status = FileAuditChainVerifier.run(new String[] {},
                new java.io.PrintStream(out, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(status).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("usage:");
    }

    @Test
    void aLongChainVerifiesEveryLine() throws IOException {
        // The file is append-only and never rotated, so its size is unbounded. This checks that a
        // long chain verifies line for line; it does not measure memory.
        Path file = dir.resolve("long.log");
        FileAuditWriter writer = new FileAuditWriter(file, SECRET);
        for (int i = 0; i < 5_000; i++) {
            writer.write(new AuditEvent("evt-" + i, Instant.parse("2026-08-28T10:15:30.123456Z"),
                    "acme", "GATEWAY_RESPONSE", Map.of("i", i)));
        }

        FileAuditChainVerifier.Result result = FileAuditChainVerifier.verify(file, SECRET);
        assertThat(result.valid()).isTrue();
        assertThat(result.linesChecked()).isEqualTo(5_000);
    }
}
