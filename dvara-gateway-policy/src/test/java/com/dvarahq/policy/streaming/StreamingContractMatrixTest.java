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
package com.dvarahq.policy.streaming;

import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GroundingResult;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiEntityType;
import com.dvarahq.core.pii.PiiScanResult;
import com.dvarahq.pii.PiiPatternRegistry;
import com.dvarahq.pii.RegexPiiDetector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LLM plane's cells of the streaming enforcement contract's test matrix, one JUnit
 * case per cell, named by the cell id so the manifest can be produced mechanically from the test
 * report rather than by reading prose. Groups A, C, E, F, G and H; group B is A2A-only and group D
 * is the resolver's own test.
 *
 * <p>Every case asserts what its own group specifies and nothing else. The fixture is one sentence
 * holding one email address, chunked three ways: {@code ONE} (whole), {@code MANY} (the address
 * arrives whole inside one of several chunks) and {@code SPLIT} (the address straddles a chunk
 * boundary). Termination is {@code EOF}, {@code ERROR} (the upstream throws after its chunks) or
 * {@code CANCEL} (the client closes the guard after {@value #CANCEL_AT} delivered characters — under
 * a withholding action nothing is delivered before EOF, so the cancel is a close before the first
 * read, which is the same offset).</p>
 */
class StreamingContractMatrixTest {

    static final String TEXT = "Contact alice@example.com for the treasury report.";
    static final String EMAIL = "alice@example.com";
    static final String REDACTED = "Contact [REDACTED_EMAIL] for the treasury report.";
    static final int CANCEL_AT = 8; // "Contact " — the same logical offset for every chunking

    static final GuardrailDetector NO_GUARDRAIL = new GuardrailDetector() {
        @Override public GuardrailScanResult scan(String text, String workspaceId) {
            return GuardrailScanResult.EMPTY;
        }
        @Override public List<GuardrailScanResult> scanRequest(com.dvarahq.core.model.ChatRequest request,
                                                              String workspaceId) {
            return List.of();
        }
        @Override public List<GuardrailScanResult> scanResponse(com.dvarahq.core.model.ChatResponse response,
                                                               String workspaceId) {
            return List.of();
        }
    };

    /** The three methods the streaming path never calls, so every double here can share them. */
    abstract static class PiiDouble implements PiiDetector {
        @Override public List<PiiScanResult> scanRequest(com.dvarahq.core.model.ChatRequest request,
                                                         Map<String, String> customPatterns) {
            return List.of();
        }
        @Override public List<PiiScanResult> scanResponse(com.dvarahq.core.model.ChatResponse response,
                                                          Map<String, String> customPatterns) {
            return List.of();
        }
        @Override public String redact(String text, List<PiiEntity> entities) {
            return Redactions.apply(text, entities);
        }
    }

    // ------------------------------------------------------------------ A · core cross (36 LLM cells)

    static Stream<Arguments> aCells() {
        List<Arguments> cells = new ArrayList<>();
        for (PiiAction action : List.of(PiiAction.LOG, PiiAction.BLOCK, PiiAction.REDACT, PiiAction.TOKENIZE)) {
            for (String chunking : List.of("ONE", "MANY", "SPLIT")) {
                for (String termination : List.of("EOF", "ERROR", "CANCEL")) {
                    cells.add(Arguments.of("A/LLM/" + action + "/" + chunking + "/" + termination,
                            action, chunking, termination));
                }
            }
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("aCells")
    void a(String cell, PiiAction action, String chunking, String termination) throws Exception {
        Run run = run(config(action), builtin(), chunks(chunking), termination, deferred(action));

        boolean withholds = action != PiiAction.LOG;
        if (withholds) {
            assertThat(run.delivered).as(cell + ": no fragment of a detected value is delivered")
                    .doesNotContain("alice@").doesNotContain("example.com").doesNotContain(EMAIL);
        }
        // Delivered text is identical across ONE/MANY/SPLIT for the same termination: it is a pure
        // function of (action, termination), so each cell asserts the same expected string.
        switch (termination) {
            case "EOF", "ERROR" -> assertThat(run.delivered).as(cell).isEqualTo(switch (action) {
                case LOG -> TEXT;
                case BLOCK -> "";
                case REDACT, TOKENIZE -> REDACTED;
            });
            case "CANCEL" -> {
                if (withholds) {
                    assertThat(run.delivered).as(cell + ": Deferred cancel discards").isEmpty();
                } else {
                    assertThat(run.delivered).as(cell + ": identical up to the cancel offset")
                            .startsWith(TEXT.substring(0, CANCEL_AT));
                }
            }
            default -> throw new IllegalStateException(termination);
        }
        if (action == PiiAction.BLOCK && !termination.equals("CANCEL")) {
            assertThat(run.chunks.getLast().getFinishReason()).isEqualTo("content_filter");
        }

        // Audit: the expected intents for the action, plus one summary, no duplicates.
        List<String> expectedIntents = switch (termination) {
            case "CANCEL" -> withholds ? List.of()
                    : run.delivered.contains(EMAIL) ? List.of("PII_OUTPUT_LEAK") : List.of();
            default -> action == PiiAction.BLOCK ? List.of("PII_BLOCKED_STREAMING") : List.of("PII_OUTPUT_LEAK");
        };
        assertThat(run.eventTypes()).as(cell + ": one event per semantic intent plus one summary")
                .containsExactlyInAnyOrderElementsOf(withSummary(expectedIntents));
        if (action == PiiAction.TOKENIZE && expectedIntents.contains("PII_OUTPUT_LEAK")) {
            // Invariant 11: outbound TOKENIZE is reported as what happened to the text.
            assertThat(run.payload("PII_OUTPUT_LEAK")).containsEntry("configured_action", "TOKENIZE");
            assertThat(run.delivered).doesNotContain("{{PII_");
        }
        if (termination.equals("ERROR")) {
            assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("truncated", true);
        }
    }

    // ------------------------------------------------------------------ C · failure injection (8 LLM cells)

    static Stream<Arguments> cCells() {
        List<Arguments> cells = new ArrayList<>();
        for (String failure : List.of("DETECTOR", "AUDIT")) {
            for (String mode : List.of("DEFERRED", "IMMEDIATE")) {
                for (String termination : List.of("EOF", "ERROR")) {
                    cells.add(Arguments.of("C/" + failure + "/" + mode + "/LLM/" + termination,
                            failure, mode, termination));
                }
            }
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cCells")
    void c(String cell, String failure, String mode, String termination) throws Exception {
        boolean deferred = mode.equals("DEFERRED");
        PiiAction action = deferred ? PiiAction.REDACT : PiiAction.LOG;
        PiiDetector detector = failure.equals("DETECTOR") ? failing() : builtin();
        AuditWriter writer = failure.equals("AUDIT")
                ? event -> { throw new IllegalStateException("audit sink down"); }
                : null;

        Run run = run(config(action), detector, chunks("SPLIT"), termination, deferred, writer);

        if (failure.equals("DETECTOR")) {
            if (deferred) {
                assertThat(run.delivered).as(cell + ": Deferred + detector failure refuses").isEmpty();
                assertThat(run.chunks.getLast().getFinishReason()).isEqualTo("content_filter");
            } else {
                assertThat(run.delivered).as(cell + ": Immediate cannot retract").isEqualTo(TEXT);
                assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("scan_failed", true);
            }
        } else {
            // The audit sink threw on every write. The disposition must be exactly what it is
            // without the failure, and nothing may propagate out of the guard.
            assertThat(run.delivered).as(cell + ": audit failure never changes the decision")
                    .isEqualTo(deferred ? REDACTED : TEXT);
        }
    }

    // ------------------------------------------------------------------ E · bounds (4 LLM cells)

    static Stream<Arguments> eCells() {
        return Stream.of("DEFERRED", "IMMEDIATE").flatMap(mode -> Stream.of("AT", "OVER")
                .map(point -> Arguments.of("E/LLM/" + mode + "/HELD_CHARS/" + point, mode, point)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("eCells")
    void e(String cell, String mode, String point) throws Exception {
        boolean deferred = mode.equals("DEFERRED");
        PiiAction action = deferred ? PiiAction.REDACT : PiiAction.LOG;
        int bound = 40;
        String text = point.equals("AT") ? "x".repeat(bound) : "x".repeat(bound + 1);
        Counting counting = new Counting(builtin());
        var config = new StreamingEnforcementConfig(true, action, false, GuardrailAction.LOG, 0.7,
                32, 8, false, GuardrailAction.LOG, Map.of(), bound);

        Run run = run(config, counting, List.of(text.substring(0, 10), text.substring(10)), "EOF", deferred);

        if (point.equals("AT")) {
            assertThat(run.delivered).as(cell + ": at the bound delivers unchanged").isEqualTo(text);
            assertThat(counting.calls.get()).as(cell + ": one detector invocation").isEqualTo(1);
            assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).doesNotContainKey("scan_incomplete");
        } else if (deferred) {
            assertThat(run.delivered).as(cell + ": Deferred overflow refuses").isEmpty();
            assertThat(counting.calls.get()).as(cell + ": zero detector invocations").isZero();
        } else {
            assertThat(run.delivered).as(cell + ": Immediate overflow keeps delivering").isEqualTo(text);
            assertThat(counting.calls.get()).as(cell + ": one detector invocation, no rescan").isEqualTo(1);
            assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("scan_incomplete", true);
        }
    }

    // ------------------------------------------------------------------ F · detector variety (6 cells)

    static Stream<Arguments> fCells() {
        return Stream.of("BUILTIN", "CUSTOM", "EXTERNAL").flatMap(d -> Stream.of("DEFERRED", "IMMEDIATE")
                .map(mode -> Arguments.of("F/" + d + "/" + mode, d, mode)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fCells")
    void f(String cell, String detectorKind, String mode) throws Exception {
        boolean deferred = mode.equals("DEFERRED");
        PiiAction action = deferred ? PiiAction.REDACT : PiiAction.LOG;
        PiiDetector detector;
        List<String> chunks;
        List<String> values;
        Map<String, String> custom = Map.of();
        switch (detectorKind) {
            case "BUILTIN" -> { detector = builtin(); chunks = chunks("SPLIT"); values = List.of(EMAIL); }
            case "CUSTOM" -> {
                // The two hardest shapes for a streaming scan: longer than any retention window,
                // and matching across whitespace.
                custom = Map.of("secret", "SECRET:[\\s\\S]{2000}", "card", "\\d{4} \\d{4} \\d{4} \\d{4}");
                String secret = "SECRET:" + "s".repeat(2000);
                String card = "4111 1111 1111 1111";
                detector = builtin();
                chunks = List.of("start " + secret.substring(0, 900), secret.substring(900) + " mid " + card.substring(0, 7),
                        card.substring(7) + " end");
                values = List.of(secret, card);
            }
            case "EXTERNAL" -> { detector = external(); chunks = chunks("SPLIT"); values = List.of(EMAIL); }
            default -> throw new IllegalStateException(detectorKind);
        }
        var config = new StreamingEnforcementConfig(true, action, false, GuardrailAction.LOG, 0.7,
                32, 8, false, GuardrailAction.LOG, custom, StreamingEnforcementConfig.DEFAULT_MAX_HELD_CHARACTERS);

        Run run = run(config, detector, chunks, "EOF", deferred);

        for (String value : values) {
            if (deferred) {
                assertThat(run.delivered).as(cell + ": the whole value is removed").doesNotContain(value);
                assertThat(run.delivered).as(cell + ": no fragment survives")
                        .doesNotContain(value.substring(0, value.length() / 2))
                        .doesNotContain(value.substring(value.length() / 2));
            } else {
                assertThat(run.delivered).as(cell + ": LOG delivers unchanged").contains(value);
            }
        }
        assertThat(run.eventTypes()).as(cell + ": detected once however it was chunked")
                .containsExactlyInAnyOrder("PII_OUTPUT_LEAK", "STREAMING_ENFORCEMENT_SUMMARY");
    }

    // ------------------------------------------------------------------ G · invocation counts (4 LLM cells)

    static Stream<Arguments> gCells() {
        return Stream.of("DEFERRED", "IMMEDIATE").flatMap(mode -> Stream.of("SINGLE_NODE", "MANY_NODES")
                .map(shape -> Arguments.of("G/LLM/" + mode + "/" + shape, mode, shape)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("gCells")
    void g(String cell, String mode, String shape) throws Exception {
        boolean deferred = mode.equals("DEFERRED");
        Counting counting = new Counting(builtin());
        List<String> chunks = shape.equals("SINGLE_NODE") ? chunks("ONE") : chunks("SPLIT");

        Run run = run(config(deferred ? PiiAction.REDACT : PiiAction.LOG), counting, chunks, "EOF", deferred);

        assertThat(counting.calls.get()).as(cell + ": exactly one invocation of the enabled detector").isEqualTo(1);
        assertThat(run.eventTypes()).as(cell + ": one event per intent plus one summary")
                .containsExactlyInAnyOrder("PII_OUTPUT_LEAK", "STREAMING_ENFORCEMENT_SUMMARY");
    }

    // ------------------------------------------------------------------ H · grounding (3 cells)

    static Stream<Arguments> hCells() {
        return Stream.of("EOF", "ERROR", "CANCEL").map(t -> Arguments.of("H/LLM/GROUNDING_BLOCK/" + t, t));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hCells")
    void h(String cell, String termination) throws Exception {
        GroundingDetector ungrounded = (request, response, sources) ->
                new GroundingResult(false, 0.1, List.of("an ungrounded claim"), 0.1);
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, false, GuardrailAction.LOG, 0.7,
                32, 8, true, GuardrailAction.BLOCK);
        List<AuditEvent> events = new CopyOnWriteArrayList<>();
        var guard = new GuardedSseIterator(upstream(List.of("The answer is ", "forty-two."), termination),
                builtin(), NO_GUARDRAIL, events::add, "t1", config, ungrounded, List.of("a source document"));

        Run run = consume(guard, termination, true, events);

        assertThat(run.delivered).as(cell + ": not one character of an ungrounded answer").isEmpty();
        switch (termination) {
            case "EOF" -> assertThat(run.eventTypes()).containsExactlyInAnyOrder(
                    "HALLUCINATION_DETECTED_STREAMING", "STREAMING_ENFORCEMENT_SUMMARY");
            case "ERROR" -> {
                assertThat(run.chunks.getLast().getFinishReason()).as(cell + ": a partial answer is refused")
                        .isEqualTo("content_filter");
                assertThat(run.eventTypes()).contains("STREAM_INCOMPLETE_UNGROUNDED");
            }
            case "CANCEL" -> assertThat(run.eventTypes()).as(cell + ": nothing delivered, summary only")
                    .containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
            default -> throw new IllegalStateException(termination);
        }
    }

    // ------------------------------------------------------------------ fixtures

    // ------------------------------------------------------------------ I · tool-call arguments (40 cells)

    static final String ARGS = "{\"to\":\"alice@example.com\",\"subject\":\"Treasury report\"}";
    static final String REDACTED_ARGS = "{\"to\":\"[REDACTED_EMAIL]\",\"subject\":\"Treasury report\"}";
    static final ChatResponse.Usage USAGE =
            ChatResponse.Usage.builder().promptTokens(11).completionTokens(7).totalTokens(18).build();

    static Stream<Arguments> iCells() {
        List<Arguments> cells = new ArrayList<>();
        for (PiiAction action : List.of(PiiAction.LOG, PiiAction.BLOCK, PiiAction.REDACT, PiiAction.TOKENIZE)) {
            for (String chunking : List.of("ONE", "SPLIT")) {
                cells.add(Arguments.of("I/LLM/" + action + "/" + chunking, action.name(), chunking));
            }
        }
        for (String shape : List.of("INTERLEAVED", "SPLIT_VALUES", "TEXT_VS_ARGS", "NUMERIC", "MALFORMED", "ESCAPED")) {
            for (String action : List.of("LOG", "REDACT")) {
                cells.add(Arguments.of("I/LLM/" + shape + "/" + action, action, shape));
            }
        }
        for (String mode : List.of("DEFERRED", "IMMEDIATE")) {
            cells.add(Arguments.of("I/LLM/HELD_CHARS/" + mode, mode.equals("DEFERRED") ? "REDACT" : "LOG", "HELD_CHARS"));
        }
        for (String shape : List.of("ERROR", "CANCEL", "DETECTOR_FAILURE", "CANCEL_HELD")) {
            for (String action : List.of("LOG", "REDACT")) {
                cells.add(Arguments.of("I/LLM/" + shape + "/" + action, action, shape));
            }
        }
        for (String bound : List.of("CALL_COUNT", "METADATA_CHARS", "VALUE_BUDGET", "METADATA_REPEAT", "METADATA_GROWTH")) {
            for (String mode : List.of("DEFERRED", "IMMEDIATE")) {
                cells.add(Arguments.of("I/LLM/" + bound + "/" + mode, mode.equals("DEFERRED") ? "REDACT" : "LOG", bound));
            }
        }
        return cells.stream();
    }

    /**
     * Tool-call arguments are governed content. Immediate delivers every fragment as it
     * arrived and scans the assembled call once at the end; Deferred emits nothing before that scan
     * and then one fragment per call, valid JSON, with the value removed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("iCells")
    void i(String cell, String actionName, String shape) throws Exception {
        PiiAction action = PiiAction.valueOf(actionName);
        boolean deferred = deferred(action);
        switch (shape) {
            case "ONE", "SPLIT" -> {
                List<SseChunk> chunks = toolCallChunks(shape.equals("ONE")
                        ? List.of(ARGS)
                        : List.of("{\"to\":\"alice@", "example.com\",\"subj", "ect\":\"Treasury report\"}"));
                Run run = runChunks(config(action), builtin(), chunks, deferred);
                switch (action) {
                    case LOG -> {
                        assertThat(run.toolFragments()).as(cell + ": fragments relayed exactly as received")
                                .isEqualTo(fragmentsOf(chunks));
                        assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(
                                withSummary(List.of("PII_OUTPUT_LEAK")));
                    }
                    case BLOCK -> {
                        assertThat(run.toolFragments()).as(cell + ": nothing of the call is delivered").isEmpty();
                        assertThat(run.chunks.getLast().getFinishReason()).isEqualTo("content_filter");
                        assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(
                                withSummary(List.of("PII_BLOCKED_STREAMING")));
                    }
                    case REDACT, TOKENIZE -> {
                        assertThat(run.toolFragments()).as(cell + ": one complete fragment, the value removed")
                                .containsExactly(new ToolCallDelta(0, "call_1", "send_email", REDACTED_ARGS));
                        assertThat(run.chunks).hasSize(1);
                        assertThat(run.chunks.getFirst().getFinishReason()).isEqualTo("tool_calls");
                        assertThat(run.chunks.getFirst().getUsage()).as(cell + ": the upstream usage survives")
                                .isEqualTo(USAGE);
                        assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(
                                withSummary(List.of("PII_OUTPUT_LEAK")));
                        assertThat(run.payload("PII_OUTPUT_LEAK")).containsEntry("configured_action", actionName);
                    }
                }
                if (action != PiiAction.LOG) {
                    assertThat(run.toolArguments(0)).doesNotContain("alice@").doesNotContain("example.com");
                }
            }
            case "INTERLEAVED" -> {
                // Two calls, fragments interleaved; each half of the address alone is not an address.
                List<SseChunk> chunks = List.of(
                        chunk(List.of(ToolCallDelta.open(0, "call_1", "first", "{\"a\":\"alice@"))),
                        chunk(List.of(ToolCallDelta.open(1, "call_2", "second", "{\"b\":\"example"))),
                        chunk(List.of(ToolCallDelta.arguments(0, "\"}"))),
                        chunk(List.of(ToolCallDelta.arguments(1, ".com\"}"))),
                        terminal());
                Run run = runChunks(config(action), builtin(), chunks, deferred);
                assertThat(run.eventTypes()).as(cell + ": calls are never joined").containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
                assertThat(run.toolArguments(0)).isEqualTo("{\"a\":\"alice@\"}");
                assertThat(run.toolArguments(1)).isEqualTo("{\"b\":\"example.com\"}");
                if (deferred) {
                    assertThat(run.toolFragments()).extracting(ToolCallDelta::name).containsExactly("first", "second");
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                }
            }
            case "SPLIT_VALUES" -> {
                // One call, two values: a detector sees each value alone, so the halves never meet.
                List<SseChunk> chunks = toolCallChunks(List.of("{\"a\":\"alice@\",", "\"b\":\"example.com\"}"));
                Run run = runChunks(config(action), builtin(), chunks, deferred);
                assertThat(run.eventTypes()).as(cell + ": values of one call are never joined")
                        .containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
                assertThat(run.toolArguments(0)).isEqualTo("{\"a\":\"alice@\",\"b\":\"example.com\"}");
            }
            case "TEXT_VS_ARGS" -> {
                List<SseChunk> chunks = List.of(
                        SseChunk.builder().id("c").model("m").delta("Contact alice@").done(false).build(),
                        chunk(List.of(ToolCallDelta.open(0, "call_1", "send", "{\"d\":\"example.com\"}"))),
                        terminal());
                Run run = runChunks(config(action), builtin(), chunks, deferred);
                assertThat(run.eventTypes()).as(cell + ": text and arguments are never joined")
                        .containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
                assertThat(run.delivered).isEqualTo("Contact alice@");
                assertThat(run.toolArguments(0)).isEqualTo("{\"d\":\"example.com\"}");
            }
            case "NUMERIC" -> {
                List<SseChunk> chunks = toolCallChunks(List.of("{\"card\":4111111111111111,\"qty\":2}"));
                Run run = runChunks(config(action), builtin(), chunks, deferred);
                assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(withSummary(List.of("PII_OUTPUT_LEAK")));
                if (deferred) {
                    assertThat(run.toolArguments(0)).as(cell + ": a number with a finding becomes a quoted placeholder")
                            .isEqualTo("{\"card\":\"[REDACTED_CREDIT_CARD]\",\"qty\":2}");
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(run.toolArguments(0));
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                }
            }
            case "MALFORMED" -> {
                // Unterminated, and the address is escaped: the raw bytes hold no "@" for a detector.
                List<SseChunk> chunks = toolCallChunks(List.of("{\"to\":\"alice\\u0040example.com\""));
                Counting counting = new Counting(builtin());
                Run run = runChunks(config(action), counting, chunks, deferred);
                if (deferred) {
                    assertThat(run.toolFragments()).as(cell + ": unreadable arguments cannot be enforced; refused").isEmpty();
                    assertThat(run.chunks.getLast().getFinishReason()).isEqualTo("content_filter");
                    assertThat(counting.calls.get()).as(cell + ": refused without asking the detector").isZero();
                    assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(
                            withSummary(List.of("STREAM_TOOL_ARGUMENTS_UNENFORCEABLE")));
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                    assertThat(run.eventTypes()).as(cell + ": the raw text hides the value; nothing is found")
                            .containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
                    assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).as(cell + ": and the record says so")
                            .containsEntry("scan_incomplete", true);
                }
            }
            case "ESCAPED" -> {
                // Valid JSON whose address is written with an escape: the reader decodes it.
                List<SseChunk> chunks = toolCallChunks(List.of("{\"to\":\"alice\\u0040example.com\"}"));
                Run run = runChunks(config(action), builtin(), chunks, deferred);
                assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(withSummary(List.of("PII_OUTPUT_LEAK")));
                if (deferred) {
                    assertThat(run.toolArguments(0)).isEqualTo("{\"to\":\"[REDACTED_EMAIL]\"}");
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                }
            }
            case "HELD_CHARS" -> {
                int bound = 40;
                Counting counting = new Counting(builtin());
                var config = new StreamingEnforcementConfig(true, action, false, GuardrailAction.LOG, 0.7,
                        32, 8, false, GuardrailAction.LOG, Map.of(), bound);
                String args = "{\"note\":\"" + "y".repeat(bound) + "\"}"; // alone already over the bound
                List<SseChunk> chunks = List.of(
                        SseChunk.builder().id("c").model("m").delta("x".repeat(10)).done(false).build(),
                        chunk(List.of(ToolCallDelta.open(0, "call_1", "note", args))),
                        terminal());
                Run run = runChunks(config, counting, chunks, deferred);
                if (deferred) {
                    assertThat(run.delivered).as(cell + ": arguments count toward the bound; refused").isEmpty();
                    assertThat(run.toolFragments()).isEmpty();
                    assertThat(counting.calls.get()).isZero();
                    assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(
                            withSummary(List.of("STREAM_TOO_LARGE_TO_SCAN")));
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                    assertThat(counting.calls.get()).isEqualTo(1);
                    assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("scan_incomplete", true);
                }
            }
            case "ERROR" -> {
                // The call arrived whole, then the upstream failed before its terminal.
                List<SseChunk> chunks = List.of(chunk(List.of(ToolCallDelta.open(0, "call_1", "send_email", ARGS))));
                Run run = runChunks(config(action), builtin(), chunks, "ERROR", deferred);
                if (deferred) {
                    assertThat(run.toolFragments()).as(cell + ": the observed call is enforced and delivered")
                            .containsExactly(new ToolCallDelta(0, "call_1", "send_email", REDACTED_ARGS));
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                }
                assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(withSummary(List.of("PII_OUTPUT_LEAK")));
                assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("truncated", true);
            }
            case "CANCEL" -> {
                // Text up to the cancel offset, then a call the client never waits for.
                List<SseChunk> chunks = List.of(
                        SseChunk.builder().id("c").model("m").delta(TEXT.substring(0, CANCEL_AT)).done(false).build(),
                        chunk(List.of(ToolCallDelta.open(0, "call_1", "send_email", ARGS))));
                Run run = runChunks(config(action), builtin(), chunks, "CANCEL", deferred);
                assertThat(run.toolFragments()).as(cell + ": nothing of the call reaches the caller").isEmpty();
                if (deferred) {
                    assertThat(run.delivered).isEmpty();
                    assertThat(run.eventTypes()).containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
                } else {
                    assertThat(run.delivered).isEqualTo(TEXT.substring(0, CANCEL_AT));
                    assertThat(run.eventTypes()).as(cell + ": only what was delivered is audited")
                            .containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
                }
            }
            case "CANCEL_HELD" -> {
                // The call has been read and assembled — a metadata-only chunk behind it is what the
                // client is handed, in both modes — and then the client hangs up.
                List<SseChunk> chunks = List.of(
                        chunk(List.of(ToolCallDelta.open(0, "call_1", "send_email", ARGS))),
                        SseChunk.builder().id("c").model("m").done(false).build());
                List<AuditEvent> events = new CopyOnWriteArrayList<>();
                var guard = new GuardedSseIterator(upstreamOf(chunks, "CANCEL"), builtin(), NO_GUARDRAIL,
                        events::add, "t1", config(action));
                List<SseChunk> delivered = new ArrayList<>();
                while (guard.hasNext()) {
                    SseChunk c = guard.next();
                    delivered.add(c);
                    if (c.getToolCalls() == null) {
                        break; // the metadata chunk: the call is assembled behind it
                    }
                }
                guard.close();
                long deadline = System.nanoTime() + 5_000_000_000L;
                while (events.stream().noneMatch(e -> "STREAMING_ENFORCEMENT_SUMMARY".equals(e.eventType()))
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                // Whatever the guard still queues after the cancel is read too, so a held call surfacing
                // late would be seen. Immediate queues its terminal just after the summary, on another
                // thread, so this polls for it briefly rather than trusting the summary as the signal.
                long drainUntil = System.nanoTime() + 300_000_000L;
                boolean terminalSeen = false;
                while (!terminalSeen && System.nanoTime() < drainUntil) {
                    if (guard.hasNext()) {
                        SseChunk c = guard.next();
                        delivered.add(c);
                        terminalSeen = c.isDone();
                    } else {
                        Thread.sleep(5);
                    }
                }
                if (!deferred) {
                    assertThat(terminalSeen).as(cell + ": Immediate still closes the stream with a terminal").isTrue();
                }
                Run run = new Run("", delivered, List.copyOf(events));
                if (deferred) {
                    assertThat(run.toolFragments()).as(cell + ": the held call is discarded, never delivered").isEmpty();
                    assertThat(run.eventTypes()).containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
                } else {
                    assertThat(run.toolFragments()).as(cell + ": Immediate had already relayed it").isEqualTo(fragmentsOf(chunks));
                    assertThat(run.eventTypes()).as(cell + ": what was delivered is audited")
                            .containsExactlyInAnyOrderElementsOf(withSummary(List.of("PII_OUTPUT_LEAK")));
                }
                assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("truncated", true);
            }
            case "CALL_COUNT" -> {
                // One more call than the response may carry, all but the first empty: a handful of
                // characters, so only a call count could see it. (The first carries an argument so the
                // Immediate scan has something to examine; an empty document invokes nothing.)
                List<SseChunk> chunks = new ArrayList<>();
                for (int k = 0; k <= GuardedSseIterator.MAX_TOOL_CALLS; k++) {
                    chunks.add(chunk(List.of(ToolCallDelta.open(k, "c" + k, "f", k == 0 ? "{\"q\":\"hi\"}" : null))));
                }
                chunks.add(terminal());
                Counting counting = new Counting(builtin());
                Run run = runChunks(config(action), counting, chunks, deferred);
                if (deferred) {
                    assertThat(run.toolFragments()).as(cell + ": refused, nothing delivered").isEmpty();
                    assertThat(counting.calls.get()).isZero();
                    assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(
                            withSummary(List.of("STREAM_TOO_LARGE_TO_SCAN")));
                } else {
                    assertThat(run.toolFragments()).as(cell + ": every call relayed as received").isEqualTo(fragmentsOf(chunks));
                    assertThat(counting.calls.get()).isEqualTo(1);
                    assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("scan_incomplete", true);
                }
            }
            case "METADATA_CHARS" -> {
                // A call whose name alone is longer than the held bound; its arguments are empty.
                int bound = 40;
                var config = new StreamingEnforcementConfig(true, action, false, GuardrailAction.LOG, 0.7,
                        32, 8, false, GuardrailAction.LOG, Map.of(), bound);
                List<SseChunk> chunks = List.of(
                        chunk(List.of(ToolCallDelta.open(0, "call_1", "n".repeat(bound + 1), null))),
                        terminal());
                Counting counting = new Counting(builtin());
                Run run = runChunks(config, counting, chunks, deferred);
                if (deferred) {
                    assertThat(run.toolFragments()).as(cell + ": the name counts; refused").isEmpty();
                    assertThat(counting.calls.get()).isZero();
                    assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(
                            withSummary(List.of("STREAM_TOO_LARGE_TO_SCAN")));
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                    assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("scan_incomplete", true);
                }
            }
            case "VALUE_BUDGET" -> {
                // Call 0 spends the whole value budget; call 1 is a valid call whose escaped address only
                // a structured read could see. Under Deferred call 0 is valid and the budget is simply
                // used up; under Immediate call 0 is MALFORMED, so a budget that refunded a failed read
                // would let call 1 be read — and the address be found.
                StringBuilder ones = new StringBuilder("[1");
                for (int k = 1; k < GuardedSseIterator.MAX_ARGUMENT_VALUES; k++) {
                    ones.append(",1");
                }
                String first = deferred ? ones + "]" : ones.toString();
                List<SseChunk> chunks = List.of(
                        chunk(List.of(ToolCallDelta.open(0, "call_1", "first", first))),
                        chunk(List.of(ToolCallDelta.open(1, "call_2", "second", "{\"to\":\"alice\\u0040example.com\"}"))),
                        terminal());
                Counting counting = new Counting(builtin());
                Run run = runChunks(config(action), counting, chunks, deferred);
                if (deferred) {
                    assertThat(run.toolFragments()).as(cell + ": the second call is over budget; refused").isEmpty();
                    assertThat(counting.calls.get()).isZero();
                    assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(
                            withSummary(List.of("STREAM_TOOL_ARGUMENTS_UNENFORCEABLE")));
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                    assertThat(run.eventTypes()).as(cell + ": a failed read spent its values; the second call is opaque")
                            .containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
                    assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("scan_incomplete", true);
                }
            }
            case "METADATA_REPEAT" -> {
                // A name three-quarters of the bound, repeated on every fragment, then shortened, then
                // long again: charged once, for its peak, so nothing overflows.
                int bound = 40;
                var config = new StreamingEnforcementConfig(true, action, false, GuardrailAction.LOG, 0.7,
                        32, 8, false, GuardrailAction.LOG, Map.of(), bound);
                String longName = "n".repeat(30);
                List<SseChunk> chunks = List.of(
                        chunk(List.of(ToolCallDelta.open(0, "c1", longName, null))),
                        chunk(List.of(ToolCallDelta.open(0, "c1", longName, null))),
                        chunk(List.of(ToolCallDelta.open(0, "c1", longName, null))),
                        chunk(List.of(ToolCallDelta.open(0, "c1", "n".repeat(10), null))),
                        chunk(List.of(ToolCallDelta.open(0, "c1", longName, null))),
                        terminal());
                Run run = runChunks(config, builtin(), chunks, deferred);
                assertThat(run.eventTypes()).as(cell + ": no overflow").containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
                assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).doesNotContainKey("scan_incomplete");
                if (deferred) {
                    assertThat(run.toolFragments()).containsExactly(new ToolCallDelta(0, "c1", longName, ""));
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                }
            }
            case "METADATA_GROWTH" -> {
                // A name at three-quarters of the bound, then one past it: the growth beyond the peak is
                // charged, and it is what overflows.
                int bound = 40;
                var config = new StreamingEnforcementConfig(true, action, false, GuardrailAction.LOG, 0.7,
                        32, 8, false, GuardrailAction.LOG, Map.of(), bound);
                List<SseChunk> chunks = List.of(
                        chunk(List.of(ToolCallDelta.open(0, "c1", "n".repeat(30), null))),
                        chunk(List.of(ToolCallDelta.open(0, "c1", "n".repeat(45), null))),
                        terminal());
                Counting counting = new Counting(builtin());
                Run run = runChunks(config, counting, chunks, deferred);
                if (deferred) {
                    assertThat(run.toolFragments()).as(cell + ": growth past the peak is charged; refused").isEmpty();
                    assertThat(counting.calls.get()).isZero();
                    assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(
                            withSummary(List.of("STREAM_TOO_LARGE_TO_SCAN")));
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                    assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("scan_incomplete", true);
                }
            }
            case "DETECTOR_FAILURE" -> {
                List<SseChunk> chunks = toolCallChunks(List.of(ARGS));
                Run run = runChunks(config(action), failing(), chunks, "EOF", deferred);
                if (deferred) {
                    assertThat(run.toolFragments()).as(cell + ": a detector that cannot run refuses under Deferred").isEmpty();
                    assertThat(run.chunks.getLast().getFinishReason()).isEqualTo("content_filter");
                    assertThat(run.eventTypes()).containsExactlyInAnyOrderElementsOf(withSummary(List.of("STREAM_ERROR")));
                } else {
                    assertThat(run.toolFragments()).isEqualTo(fragmentsOf(chunks));
                    assertThat(run.payload("STREAMING_ENFORCEMENT_SUMMARY")).containsEntry("scan_failed", true);
                }
            }
            default -> throw new IllegalStateException(shape);
        }
    }

    /** One call, id {@code call_1} / {@code send_email}, its arguments in the given fragments, then the terminal. */
    static List<SseChunk> toolCallChunks(List<String> fragments) {
        List<SseChunk> chunks = new ArrayList<>();
        for (int i = 0; i < fragments.size(); i++) {
            chunks.add(chunk(List.of(i == 0
                    ? ToolCallDelta.open(0, "call_1", "send_email", fragments.get(i))
                    : ToolCallDelta.arguments(0, fragments.get(i)))));
        }
        chunks.add(terminal());
        return chunks;
    }

    static SseChunk chunk(List<ToolCallDelta> fragments) {
        return SseChunk.builder().id("c").model("m").toolCalls(fragments).done(false).build();
    }

    /** What an OpenAI-shaped upstream ends a tool-call stream with: the finish reason, and the usage. */
    static SseChunk terminal() {
        return SseChunk.builder().id("c").model("m").finishReason("tool_calls").usage(USAGE).done(true).build();
    }

    static List<ToolCallDelta> fragmentsOf(List<SseChunk> chunks) {
        return chunks.stream().filter(c -> c.getToolCalls() != null)
                .flatMap(c -> c.getToolCalls().stream()).toList();
    }

    static List<String> chunks(String chunking) {
        return switch (chunking) {
            case "ONE" -> List.of(TEXT);
            case "MANY" -> List.of("Contact ", EMAIL, " for the treasury report.");
            case "SPLIT" -> List.of("Contact alice@exa", "mple.com for the", " treasury report.");
            default -> throw new IllegalStateException(chunking);
        };
    }

    static StreamingEnforcementConfig config(PiiAction action) {
        return new StreamingEnforcementConfig(true, action, false, GuardrailAction.LOG, 0.7, 32, 8);
    }

    static boolean deferred(PiiAction action) {
        return action != PiiAction.LOG;
    }

    static PiiDetector builtin() {
        return new RegexPiiDetector(new PiiPatternRegistry());
    }

    /** A double standing in for an external detector: implements the document call directly. */
    static PiiDetector external() {
        return new PiiDouble() {
            @Override public PiiScanResult scan(String text, Map<String, String> customPatterns) {
                int at = text.indexOf(EMAIL);
                if (at < 0) {
                    return PiiScanResult.EMPTY;
                }
                return new PiiScanResult(List.of(new PiiEntity(PiiEntityType.EMAIL, EMAIL, at,
                        at + EMAIL.length(), "email", 0.99)), text);
            }
            @Override public List<PiiScanResult> scanDocument(ResponseDocument document,
                                                            Map<String, String> customPatterns) {
                return document.groups().stream().map(g -> scan(g.text(), customPatterns)).toList();
            }
        };
    }

    static PiiDetector failing() {
        return new PiiDouble() {
            @Override public PiiScanResult scan(String text, Map<String, String> customPatterns) {
                throw new IllegalStateException("detector unavailable");
            }
            @Override public List<PiiScanResult> scanDocument(ResponseDocument document,
                                                            Map<String, String> customPatterns) {
                throw new IllegalStateException("detector unavailable");
            }
        };
    }

    static final class Counting extends PiiDouble {
        final AtomicInteger calls = new AtomicInteger();
        private final PiiDetector delegate;
        Counting(PiiDetector delegate) { this.delegate = delegate; }
        @Override public PiiScanResult scan(String text, Map<String, String> customPatterns) {
            return delegate.scan(text, customPatterns);
        }
        @Override public List<PiiScanResult> scanDocument(ResponseDocument document,
                                                        Map<String, String> customPatterns) {
            calls.incrementAndGet();
            return delegate.scanDocument(document, customPatterns);
        }
        @Override public String redact(String text, List<PiiEntity> entities) {
            return delegate.redact(text, entities);
        }
    }

    /** Right-to-left placeholder substitution, the same shape the built-in detector uses. */
    static final class Redactions {
        static String apply(String text, List<PiiEntity> entities) {
            StringBuilder out = new StringBuilder(text);
            entities.stream().sorted((a, b) -> Integer.compare(b.start(), a.start()))
                    .forEach(e -> out.replace(e.start(), e.end(), "[REDACTED_" + e.type().name() + "]"));
            return out.toString();
        }
    }

    /** The upstream: its chunks, then EOF, an exception, or (for CANCEL) nothing more is ever read. */
    static Iterator<SseChunk> upstream(List<String> deltas, String termination) {
        List<SseChunk> chunks = new ArrayList<>();
        for (String d : deltas) {
            chunks.add(SseChunk.builder().id("c").model("m").delta(d).done(false).build());
        }
        if (termination.equals("EOF")) {
            chunks.add(SseChunk.builder().id("c").model("m").finishReason("stop").done(true).build());
        }
        return upstreamOf(chunks, termination);
    }

    /** The same upstream over ready-made chunks; the caller supplies any terminal it wants on EOF. */
    static Iterator<SseChunk> upstreamOf(List<SseChunk> chunks, String termination) {
        Iterator<SseChunk> base = chunks.iterator();
        return new Iterator<>() {
            @Override public boolean hasNext() {
                if (base.hasNext()) {
                    return true;
                }
                if (termination.equals("ERROR")) {
                    throw new IllegalStateException("upstream connection reset");
                }
                if (termination.equals("CANCEL")) {
                    throw new AssertionError("the upstream must not be read past the cancel");
                }
                return false;
            }
            @Override public SseChunk next() {
                if (!base.hasNext()) {
                    throw new NoSuchElementException();
                }
                return base.next();
            }
        };
    }

    record Run(String delivered, List<SseChunk> chunks, List<AuditEvent> events) {
        List<String> eventTypes() { return events.stream().map(AuditEvent::eventType).toList(); }
        /** Every tool-call fragment delivered, in order. */
        List<ToolCallDelta> toolFragments() {
            return chunks.stream().filter(c -> c.getToolCalls() != null)
                    .flatMap(c -> c.getToolCalls().stream()).toList();
        }
        String toolArguments(int index) {
            StringBuilder sb = new StringBuilder();
            toolFragments().stream().filter(f -> f.index() == index && f.argumentsFragment() != null)
                    .forEach(f -> sb.append(f.argumentsFragment()));
            return sb.toString();
        }
        Map<String, Object> payload(String type) {
            return events.stream().filter(e -> type.equals(e.eventType())).findFirst()
                    .map(AuditEvent::payload)
                    .orElseThrow(() -> new AssertionError("no " + type + " event: " + eventTypes()));
        }
    }

    static Run run(StreamingEnforcementConfig config, PiiDetector detector, List<String> deltas,
                   String termination, boolean deferred) throws Exception {
        return run(config, detector, deltas, termination, deferred, null);
    }

    static Run run(StreamingEnforcementConfig config, PiiDetector detector, List<String> deltas,
                   String termination, boolean deferred, AuditWriter failingWriter) throws Exception {
        List<AuditEvent> events = new CopyOnWriteArrayList<>();
        AuditWriter writer = failingWriter != null ? failingWriter : events::add;
        var guard = new GuardedSseIterator(upstream(deltas, termination), detector, NO_GUARDRAIL,
                writer, "t1", config);
        return consume(guard, termination, deferred, events);
    }

    static Run runChunks(StreamingEnforcementConfig config, PiiDetector detector, List<SseChunk> chunks,
                         boolean deferred) throws Exception {
        return runChunks(config, detector, chunks, "EOF", deferred);
    }

    static Run runChunks(StreamingEnforcementConfig config, PiiDetector detector, List<SseChunk> chunks,
                         String termination, boolean deferred) throws Exception {
        List<AuditEvent> events = new CopyOnWriteArrayList<>();
        var guard = new GuardedSseIterator(upstreamOf(chunks, termination), detector, NO_GUARDRAIL,
                events::add, "t1", config);
        return consume(guard, termination, deferred, events);
    }

    /**
     * Drives the guard the way a controller does. Under CANCEL the client stops after
     * {@link #CANCEL_AT} delivered characters and closes; under a withholding action nothing has
     * been delivered before EOF, so that is a close before the first read.
     */
    static Run consume(GuardedSseIterator guard, String termination, boolean deferred,
                       List<AuditEvent> events) throws Exception {
        List<SseChunk> chunks = new ArrayList<>();
        StringBuilder delivered = new StringBuilder();
        if (termination.equals("CANCEL")) {
            if (!deferred) {
                while (delivered.length() < CANCEL_AT && guard.hasNext()) {
                    SseChunk chunk = guard.next();
                    chunks.add(chunk);
                    if (chunk.getDelta() != null) {
                        delivered.append(chunk.getDelta());
                    }
                }
            }
            guard.close();
            // Immediate finalizes off-thread; the summary is the last thing it writes.
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (events.stream().noneMatch(e -> "STREAMING_ENFORCEMENT_SUMMARY".equals(e.eventType()))
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            return new Run(delivered.toString(), chunks, List.copyOf(events));
        }
        int safety = 0;
        while (guard.hasNext() && safety++ < 10_000) {
            SseChunk chunk = guard.next();
            chunks.add(chunk);
            if (chunk.getDelta() != null) {
                delivered.append(chunk.getDelta());
            }
        }
        guard.close();
        return new Run(delivered.toString(), chunks, List.copyOf(events));
    }

    static List<String> withSummary(List<String> intents) {
        List<String> all = new ArrayList<>(intents);
        all.add("STREAMING_ENFORCEMENT_SUMMARY");
        return all;
    }

    /**
     * Writes {@code target/streaming-cells/StreamingContractMatrixTest.tsv}: one line per case, {@code method, index, cell id},
     * produced from the same streams surefire iterates. It is the enumeration any external report of
     * these cells must agree with, so a cell renamed or reordered here cannot be reported under the
     * wrong id.
     */
    @AfterAll
    static void writeCellIndex() throws IOException {
        Path out = Path.of("target", "streaming-cells", "StreamingContractMatrixTest.tsv");
        Files.createDirectories(out.getParent());
        StringBuilder sb = new StringBuilder();
        List<Object> sources = List.of(
                "a", aCells(),
                "c", cCells(),
                "e", eCells(),
                "f", fCells(),
                "g", gCells(),
                "h", hCells(),
                "i", iCells());
        for (int k = 0; k < sources.size(); k += 2) {
            String method = (String) sources.get(k);
            @SuppressWarnings("unchecked")
            List<Arguments> cases = ((Stream<Arguments>) sources.get(k + 1)).toList();
            for (int i = 0; i < cases.size(); i++) {
                sb.append(method).append('\t').append(i + 1).append('\t').append(cases.get(i).get()[0]).append('\n');
            }
        }
        Files.writeString(out, sb.toString());
    }
}
