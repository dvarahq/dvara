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
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.guardrail.GuardrailCategory;
import com.dvarahq.core.guardrail.GuardrailDetection;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.pii.RegexPiiDetector;
import com.dvarahq.pii.PiiPatternRegistry;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiEntity;
import com.dvarahq.core.pii.PiiEntityType;
import com.dvarahq.core.pii.PiiScanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GuardedSseIteratorTest {

    private PiiDetector piiDetector;
    private GuardrailDetector guardrailDetector;
    private List<AuditEvent> auditEvents;
    private AuditWriter auditWriter;

    @BeforeEach
    void setUp() {
        piiDetector = mock(PiiDetector.class);
        guardrailDetector = mock(GuardrailDetector.class);
        auditEvents = new ArrayList<>();
        auditWriter = auditEvents::add;

        // A MOCKED interface does not inherit its default methods — Mockito answers null. The engine
        // calls scanDocument (one invocation per detector per response), so without this every stub of
        // scan() below would be bypassed and every test would see no detections. Delegating here keeps
        // each test's own `when(piiDetector.scan(...))` meaningful.
        when(piiDetector.scanDocument(any(), any())).thenAnswer(inv -> {
            com.dvarahq.core.enforcement.ResponseDocument doc = inv.getArgument(0);
            return doc.groups().stream()
                    .map(g -> piiDetector.scan(g.text(), inv.getArgument(1)))
                    .toList();
        });
        when(guardrailDetector.scanDocument(any(), any())).thenAnswer(inv -> {
            com.dvarahq.core.enforcement.ResponseDocument doc = inv.getArgument(0);
            return doc.groups().stream()
                    .map(g -> guardrailDetector.scan(g.text(), inv.getArgument(1)))
                    .toList();
        });

        // Default: no detections
        when(piiDetector.scan(any(String.class), any())).thenReturn(PiiScanResult.EMPTY);
        when(guardrailDetector.scan(any(String.class), any())).thenReturn(GuardrailScanResult.EMPTY);
    }

    @Test
    void passthrough_whenNoPiiOrViolations() {
        var config = config(PiiAction.LOG, GuardrailAction.LOG);
        var upstream = chunksOf("Hello", " world", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        String fullText = result.stream()
                .map(SseChunk::getDelta)
                .filter(d -> d != null)
                .reduce("", String::concat);
        assertThat(fullText).isEqualTo("Hello world");
        assertThat(result.getLast().isDone()).isTrue();
    }

    @Test
    void redactsPii_whenRedactMode() {
        when(piiDetector.scan(any(String.class), any())).thenReturn(
                new PiiScanResult(List.of(
                        new PiiEntity(PiiEntityType.EMAIL, "a@b.com", 0, 7, "email", 1.0)
                ), "a@b.com test"));
        // REDACT produces an irreversible placeholder, so the stub returns one.
        when(piiDetector.redact(any(String.class), any())).thenReturn("[REDACTED_EMAIL] test");

        var config = config(PiiAction.REDACT, GuardrailAction.LOG);
        // Use large window so everything flushes at end
        var upstream = chunksOf("a@b.com test", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        String fullText = result.stream()
                .map(SseChunk::getDelta)
                .filter(d -> d != null)
                .reduce("", String::concat);
        assertThat(fullText).contains("[REDACTED_EMAIL]");
        assertThat(fullText).doesNotContain("a@b.com");
    }

    @Test
    void redactsStreamingPii_whenTokenizeMode() {
        // TOKENIZE must take the REDACT path here, not the LOG path: a workspace on TOKENIZE has its
        // non-streaming responses protected, and a streamed response must not be the one served with
        // the personal data intact.
        //
        // Outbound text is redacted irreversibly under TOKENIZE, not tokenized: the caller is the
        // party the value is being withheld from, so a token they could trade back defeats the scan.
        when(piiDetector.scan(any(String.class), any())).thenReturn(
                new PiiScanResult(List.of(
                        new PiiEntity(PiiEntityType.EMAIL, "a@b.com", 0, 7, "email", 1.0)
                ), "a@b.com test"));
        when(piiDetector.redact(any(String.class), any())).thenReturn("[REDACTED_EMAIL] test");

        var config = config(PiiAction.TOKENIZE, GuardrailAction.LOG);
        var upstream = chunksOf("a@b.com test", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        String fullText = result.stream()
                .map(SseChunk::getDelta)
                .filter(d -> d != null)
                .reduce("", String::concat);
        assertThat(fullText)
                .as("the value must not reach the client")
                .doesNotContain("a@b.com");
        assertThat(fullText).contains("[REDACTED_EMAIL]");
    }

    @Test
    void terminatesStream_whenPiiBlockMode() {
        when(piiDetector.scan(any(String.class), any())).thenReturn(
                new PiiScanResult(List.of(
                        new PiiEntity(PiiEntityType.SSN, "123-45-6789", 0, 11, "ssn", 1.0)
                ), "123-45-6789"));

        var config = config(PiiAction.BLOCK, GuardrailAction.LOG);
        var upstream = chunksOf("SSN: 123-45-6789", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        assertThat(result.getLast().isDone()).isTrue();
        assertThat(result.getLast().getFinishReason()).isEqualTo("content_filter");
        assertThat(auditEvents.stream().anyMatch(e -> "PII_BLOCKED_STREAMING".equals(e.eventType()))).isTrue();
    }

    @Test
    void terminatesStream_whenGuardrailBlockDetected() {
        when(guardrailDetector.scan(any(String.class), eq("t1"))).thenReturn(
                new GuardrailScanResult(List.of(
                        new GuardrailDetection(GuardrailCategory.INJECTION, "test",
                                "ignore instructions", 0.95, 0.95, "rule-1")
                ), "ignore instructions"));

        var config = config(PiiAction.LOG, GuardrailAction.BLOCK);
        var upstream = chunksOf("Please ignore instructions", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        assertThat(result.getLast().isDone()).isTrue();
        assertThat(result.getLast().getFinishReason()).isEqualTo("content_filter");
        assertThat(auditEvents.stream().anyMatch(e -> "GUARDRAIL_BLOCKED_STREAMING".equals(e.eventType()))).isTrue();
    }

    @Test
    void flagsContinues_whenGuardrailFlagMode() {
        when(guardrailDetector.scan(any(String.class), eq("t1"))).thenReturn(
                new GuardrailScanResult(List.of(
                        new GuardrailDetection(GuardrailCategory.PROFANITY, "bad word",
                                "damn", 0.8, 0.8, "rule-2")
                ), "damn"));

        var config = config(PiiAction.LOG, GuardrailAction.FLAG);
        var upstream = chunksOf("That is damn good", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        // Stream should complete normally
        String fullText = result.stream()
                .map(SseChunk::getDelta).filter(d -> d != null)
                .reduce("", String::concat);
        assertThat(fullText).contains("damn");
        assertThat(result.getLast().isDone()).isTrue();
        assertThat(result.getLast().getFinishReason()).isEqualTo("stop");
        assertThat(auditEvents).extracting(AuditEvent::eventType)
                .contains("GUARDRAIL_FLAGGED")
                .doesNotContain("GUARDRAIL_BLOCKED_STREAMING");
    }

    @Test
    void logFindingNeverEmitsABlockedEvent() {
        when(guardrailDetector.scan(any(String.class), eq("t1"))).thenReturn(
                new GuardrailScanResult(List.of(
                        new GuardrailDetection(GuardrailCategory.PROFANITY, "bad word",
                                "damn", 0.8, 0.8, "rule-2")
                ), "damn"));

        drain(new GuardedSseIterator(chunksOf("That is damn good", null), piiDetector,
                guardrailDetector, auditWriter, "t1",
                config(PiiAction.LOG, GuardrailAction.LOG)));

        assertThat(auditEvents).extracting(AuditEvent::eventType)
                .contains("GUARDRAIL_DETECTED")
                .doesNotContain("GUARDRAIL_BLOCKED_STREAMING");
        // A reused event type carries the shape its consumers already read.
        assertThat(payloadOf("GUARDRAIL_DETECTED"))
                .containsEntry("detection_count", 1)
                .containsEntry("categories", "PROFANITY")
                .containsEntry("source", "streaming_response")
                .containsKey("detections");
        @SuppressWarnings("unchecked")
        var detection = (java.util.Map<String, Object>)
                ((List<Object>) payloadOf("GUARDRAIL_DETECTED").get("detections")).getFirst();
        assertThat(detection).containsEntry("category", "PROFANITY")
                .containsEntry("label", "bad word").containsEntry("rule_id", "rule-2")
                .containsKey("risk_score");
    }

    @Test
    void handlesEmptyDeltaChunks() {
        var config = config(PiiAction.LOG, GuardrailAction.LOG);
        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta(null).done(false).build(),
                SseChunk.builder().id("2").model("m").delta("Hello").done(false).build(),
                SseChunk.builder().id("3").model("m").delta("").done(false).build(),
                SseChunk.builder().id("4").model("m").delta(null).finishReason("stop").done(true).build()
        ).iterator();

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        // Should not crash, should produce output with "Hello"
        assertThat(result).isNotEmpty();
        assertThat(result.getLast().isDone()).isTrue();
    }

    @Test
    void flushesRemainingBuffer_onFinalChunk() {
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, true, GuardrailAction.LOG, 0.7, 512, 64);
        // Buffer won't hit scanWindowSize (512), so everything flushes on done
        var upstream = chunksOf("Short text", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        String fullText = result.stream()
                .map(SseChunk::getDelta).filter(d -> d != null)
                .reduce("", String::concat);
        assertThat(fullText).isEqualTo("Short text");
    }

    @Test
    void auditsSummary_atStreamEnd() {
        when(piiDetector.scan(any(String.class), any())).thenReturn(
                new PiiScanResult(List.of(
                        new PiiEntity(PiiEntityType.EMAIL, "a@b.com", 0, 7, "email", 1.0)
                ), "a@b.com"));

        var config = config(PiiAction.LOG, GuardrailAction.LOG);
        var upstream = chunksOf("Contact a@b.com please", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        drain(guarded);

        assertThat(auditEvents.stream()
                .anyMatch(e -> "STREAMING_ENFORCEMENT_SUMMARY".equals(e.eventType()))).isTrue();
    }

    @Test
    void cleanResponseStillWritesTheRequiredSummary() {
        var iterator = new GuardedSseIterator(chunksOf("hello", null), piiDetector,
                guardrailDetector, auditWriter, "t1",
                config(PiiAction.LOG, GuardrailAction.LOG));

        drain(iterator);

        assertThat(auditEvents).extracting(AuditEvent::eventType)
                .containsExactly("STREAMING_ENFORCEMENT_SUMMARY");
    }

    @Test
    void emptyStream_handledGracefully() {
        var config = config(PiiAction.LOG, GuardrailAction.LOG);
        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta(null).finishReason("stop").done(true).build()
        ).iterator();

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        assertThat(result).isNotEmpty();
        assertThat(result.getLast().isDone()).isTrue();
    }

    // ---- PII spanning chunk boundaries (overlap margin) ----

    @Test
    void detectsPiiSpanningChunkBoundary_viaOverlapMargin() {
        // scanWindowSize=20, overlapMargin=10: first scan emits 10 chars, retains 10
        // "Contact user" (12 chars) → under 20, buffered
        // "@example.com end" (16 chars) → total 28, triggers scan
        // Overlap retains last 10 chars from first window, so "user@example" spans boundary
        when(piiDetector.scan(any(String.class), any())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            if (text.contains("user@example.com")) {
                return new PiiScanResult(List.of(
                        new PiiEntity(PiiEntityType.EMAIL, "user@example.com",
                                text.indexOf("user@example.com"),
                                text.indexOf("user@example.com") + 16, "email", 1.0)
                ), text);
            }
            return PiiScanResult.EMPTY;
        });
        when(piiDetector.redact(any(String.class), any())).thenReturn("Contact {{PII}} end");

        var config = new StreamingEnforcementConfig(true, PiiAction.REDACT, true, GuardrailAction.LOG, 0.7, 40, 16);
        // With scanWindow=40 and overlap=16: "Contact user" (12 chars) buffered,
        // "@example.com end" (16 chars) → total 28, still under 40.
        // Both flush at done. Overlap ensures "user@example.com" spans correctly.
        var upstream = chunksOf("Contact user", "@example.com end", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        // Should have detected and redacted the email
        assertThat(result).isNotEmpty();
        assertThat(result.getLast().isDone()).isTrue();
    }

    // ---- Scan window triggering mid-stream ----

    @Test
    void scanWindowTriggersMiddStream_emitsMultipleChunks() {
        // scanWindowSize=32, overlapMargin=8: emit at 24-char intervals
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, true, GuardrailAction.LOG, 0.7, 32, 8);
        // 3 chunks of 15 chars each = 45 total. After 32 chars, scan triggers mid-stream.
        //
        // The segments carry spaces, as any realistic token stream does; this test is about
        // mid-stream emission.
        var upstream = chunksOf("AAAA AAAA AAAAA", "BBBB BBBB BBBBB", "CCCC CCCC CCCCC", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        // Should emit multiple chunks (at least 2: one from mid-stream scan, one from flush)
        long contentChunks = result.stream().filter(c -> c.getDelta() != null).count();
        assertThat(contentChunks).isGreaterThanOrEqualTo(2);

        // Total text should be preserved
        String fullText = result.stream()
                .map(SseChunk::getDelta).filter(d -> d != null)
                .reduce("", String::concat);
        assertThat(fullText).hasSize(45);
        assertThat(result.getLast().isDone()).isTrue();
    }

    // ---- Upstream exception handling ----

    @Test
    void upstreamException_terminatesWithError() {
        var config = config(PiiAction.LOG, GuardrailAction.LOG);
        Iterator<SseChunk> upstream = new Iterator<>() {
            private int count = 0;
            @Override public boolean hasNext() { return count < 3; }
            @Override public SseChunk next() {
                count++;
                if (count == 2) throw new RuntimeException("Connection reset");
                return SseChunk.builder().id("c").model("m").delta("Hello").done(false).build();
            }
        };

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        // Should flush buffered content and terminate
        assertThat(result.getLast().isDone()).isTrue();
        // Buffered content is flushed with "stop"; empty buffer would produce "content_filter"
        assertThat(result.stream().anyMatch(c -> c.getDelta() != null && c.getDelta().contains("Hello"))).isTrue();
    }

    // ---- Passthrough when both PII and guardrail disabled ----

    @Test
    void passthrough_whenBothDisabled() {
        var config = new StreamingEnforcementConfig(false, PiiAction.LOG, false, GuardrailAction.LOG, 0.7, 64, 16);
        var upstream = chunksOf("Hello world with PII user@test.com", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        String fullText = result.stream()
                .map(SseChunk::getDelta).filter(d -> d != null)
                .reduce("", String::concat);
        // PII should pass through unmodified since both are disabled
        assertThat(fullText).contains("user@test.com");
        assertThat(auditEvents).isEmpty();
    }

    // ---- Multiple PII entities in one window ----

    @Test
    void multiplePiiEntities_allCountedInAudit() {
        when(piiDetector.scan(any(String.class), any())).thenReturn(
                new PiiScanResult(List.of(
                        new PiiEntity(PiiEntityType.EMAIL, "a@b.com", 0, 7, "email", 1.0),
                        new PiiEntity(PiiEntityType.SSN, "123-45-6789", 12, 23, "ssn", 1.0)
                ), "a@b.com and 123-45-6789"));

        var config = config(PiiAction.LOG, GuardrailAction.LOG);
        var upstream = chunksOf("a@b.com and 123-45-6789", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        drain(guarded);

        // Summary audit should report 2 entities
        assertThat(auditEvents.stream()
                .filter(e -> "STREAMING_ENFORCEMENT_SUMMARY".equals(e.eventType()))
                .findFirst()
                .map(e -> e.payload().get("pii_entity_count"))
                .orElse(null)).isEqualTo(2);
    }

    // ---- PII REDACT + Guardrail FLAG simultaneously ----

    @Test
    void piiRedact_andGuardrailFlag_bothApplied() {
        when(piiDetector.scan(any(String.class), any())).thenReturn(
                new PiiScanResult(List.of(
                        new PiiEntity(PiiEntityType.EMAIL, "a@b.com", 0, 7, "email", 1.0)
                ), "a@b.com bad content"));
        when(piiDetector.redact(any(String.class), any())).thenReturn("{{PII}} bad content");
        when(guardrailDetector.scan(any(String.class), any())).thenReturn(
                new GuardrailScanResult(List.of(
                        new GuardrailDetection(GuardrailCategory.PROFANITY, "bad",
                                "bad content", 0.8, 0.8, "rule-1")
                ), "bad content"));

        var config = new StreamingEnforcementConfig(true, PiiAction.REDACT, true, GuardrailAction.FLAG, 0.7, 256, 16);
        var upstream = chunksOf("a@b.com bad content", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        // PII should be redacted
        String fullText = result.stream()
                .map(SseChunk::getDelta).filter(d -> d != null)
                .reduce("", String::concat);
        // The engine builds the placeholder from the entity type and returns edits rather than
        // calling detector.redact(), so this asserts behaviour rather than what a mock was told to
        // return. Same string the non-streaming path produces.
        assertThat(fullText).contains("[REDACTED_EMAIL]");
        assertThat(fullText).doesNotContain("a@b.com");

        // Stream should continue (FLAG, not BLOCK)
        assertThat(result.getLast().getFinishReason()).isEqualTo("stop");

        // Both counters recorded in summary
        assertThat(auditEvents.stream()
                .anyMatch(e -> "STREAMING_ENFORCEMENT_SUMMARY".equals(e.eventType()))).isTrue();
    }

    @Test
    void refusedResponseWritesEveryControlsAuditIntent() {
        when(piiDetector.scan(any(String.class), any())).thenReturn(
                new PiiScanResult(List.of(new PiiEntity(PiiEntityType.EMAIL, "a@b.com",
                        0, 7, "email", 1.0)), "a@b.com"));
        when(guardrailDetector.scan(any(String.class), any())).thenReturn(
                new GuardrailScanResult(List.of(new GuardrailDetection(
                        GuardrailCategory.INJECTION, "injection", "a@b.com", 0.95, 0.95, "r1")),
                        "a@b.com"));
        var config = new StreamingEnforcementConfig(true, PiiAction.BLOCK, true,
                GuardrailAction.BLOCK, 0.7, 256, 16);

        drain(new GuardedSseIterator(chunksOf("a@b.com", null), piiDetector, guardrailDetector,
                auditWriter, "t1", config));

        assertThat(auditEvents).extracting(AuditEvent::eventType)
                .containsExactlyInAnyOrder("PII_BLOCKED_STREAMING", "GUARDRAIL_BLOCKED_STREAMING",
                        "STREAMING_ENFORCEMENT_SUMMARY");
        // Both refusal events carry `message` and `source`; the shape is part of the audit contract.
        assertThat(payloadOf("PII_BLOCKED_STREAMING"))
                .containsEntry("message", "PII detected in streaming response")
                .containsEntry("source", "streaming_response");
        assertThat(payloadOf("GUARDRAIL_BLOCKED_STREAMING"))
                .containsEntry("message", "Guardrail violation in streaming response")
                .containsEntry("source", "streaming_response")
                .containsEntry("detection_count", 1)
                .containsEntry("categories", "INJECTION");
    }

    @Test
    void immediateCancellationClosesUpstreamBeforeAsyncScan() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        CountDownLatch scanned = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(inv -> {
            assertThat(closed).isTrue();
            scanned.countDown();
            return List.of(PiiScanResult.EMPTY);
        }).when(piiDetector).scanDocument(any(), any());
        Iterator<SseChunk> delegate = chunksOf("already delivered");
        class CloseableChunks implements Iterator<SseChunk>, AutoCloseable {
            @Override public boolean hasNext() { return delegate.hasNext(); }
            @Override public SseChunk next() { return delegate.next(); }
            @Override public void close() { closed.set(true); }
        }
        var upstream = new CloseableChunks();
        var config = config(PiiAction.LOG, GuardrailAction.LOG);
        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                event -> { }, "t1", config);

        assertThat(guarded.next().getDelta()).isEqualTo("already delivered");
        guarded.close();

        assertThat(scanned.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(closed).isTrue();
    }

    // ---- Very large single chunk ----

    @Test
    void veryLargeChunk_scannedCorrectly() {
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, true, GuardrailAction.LOG, 0.7, 64, 16);
        // Single chunk of 500 chars, much larger than scanWindowSize, spaced as realistic text is.
        // This test is about a large chunk being scanned and emitted correctly.
        String largeText = ("A".repeat(9) + " ").repeat(50);
        var upstream = chunksOf(largeText, null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        String fullText = result.stream()
                .map(SseChunk::getDelta).filter(d -> d != null)
                .reduce("", String::concat);
        assertThat(fullText).hasSize(largeText.length());
        // ONE content chunk, not several: the whole chunk is scanned and delivered as one emission.
        long contentChunks = result.stream().filter(c -> c.getDelta() != null).count();
        assertThat(contentChunks).isEqualTo(1);
    }

    // ---- Done chunk with delta text ----

    @Test
    void doneChunkWithDelta_scannedAndEmitted() {
        var config = config(PiiAction.LOG, GuardrailAction.LOG);
        // Some providers emit the final chunk with both delta text AND done=true
        Iterator<SseChunk> upstream = List.of(
                SseChunk.builder().id("1").model("m").delta("Hello").done(false).build(),
                SseChunk.builder().id("2").model("m").delta(" world!").finishReason("stop").done(true).build()
        ).iterator();

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        List<SseChunk> result = drain(guarded);

        String fullText = result.stream()
                .map(SseChunk::getDelta).filter(d -> d != null)
                .reduce("", String::concat);
        assertThat(fullText).isEqualTo("Hello world!");
        assertThat(result.getLast().isDone()).isTrue();
    }

    // --- Helpers ---

    @Test
    void aValueLongerThanAnyRetentionIsStillRedactedWhole() {
        // The value must be far longer than any retention the guard could hold, or any implementation
        // would protect it. A workspace pattern is an arbitrary regex, so the value can be any length
        // at all, which is why the guard holds the whole response rather than choosing a place to cut.
        var realDetector = new RegexPiiDetector(new PiiPatternRegistry());
        var config = new StreamingEnforcementConfig(true, PiiAction.REDACT, true, GuardrailAction.LOG, 0.7, 32, 8);
        var longLocal = "a".repeat(3000);   // far past any retention
        var upstream = chunksOf(longLocal, "@treasury.example.com", null);

        var guarded = new GuardedSseIterator(upstream, realDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText)
                .as("no fragment of the address may be emitted, whatever its length")
                .doesNotContain(longLocal)
                .doesNotContain("treasury.example.com");
    }

    @Test
    void aCustomPatternLongerThanAnyRetentionIsAlsoEnforced() {
        // A workspace regex that matches 2,000 characters. With a fixed retention the head would be
        // emitted before the pattern had finished arriving, so the detector would never see a
        // complete match and the workspace's rule could be walked past.
        String marker = "SECRET:";
        String body = "x".repeat(2000);
        when(piiDetector.scan(any(String.class), any())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            int at = text.indexOf(marker);
            return (at < 0 || text.length() < at + marker.length() + 2000)
                    ? PiiScanResult.EMPTY
                    : new PiiScanResult(List.of(new PiiEntity(PiiEntityType.CUSTOM,
                            text.substring(at, at + marker.length() + 2000), at,
                            at + marker.length() + 2000, "workspace_secret", 1.0)), text);
        });
        when(piiDetector.redact(any(String.class), any())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).replaceAll("SECRET:x+", "[REDACTED_CUSTOM]"));

        var config = new StreamingEnforcementConfig(true, PiiAction.REDACT, false, GuardrailAction.LOG, 0.7, 32, 8);
        var upstream = chunksOf(marker + body.substring(0, 900), body.substring(900) + " done", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).doesNotContain(marker).contains("[REDACTED_CUSTOM]").contains("done");
    }

    @Test
    void heldResponsePastTheMemoryBound_isRefusedRatherThanReleased() {
        var config = new StreamingEnforcementConfig(true, PiiAction.REDACT, false, GuardrailAction.LOG,
                0.7, 32, 8, false, GuardrailAction.LOG, java.util.Map.of(), 500);
        var upstream = chunksOf("A".repeat(400), "B".repeat(400), null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).as("nothing half-scanned is released at the bound").isEmpty();
    }

    @Test
    void logOnly_neverTerminatesOnUnbrokenOutput() {
        // LOG observes; the text goes out unchanged whatever the scan finds. Refusing an ordinary
        // stream — base64, minified JSON, a long identifier — to guard a disclosure that was never
        // going to be prevented is harm for nothing, and this is the DEFAULT PII posture.
        var realDetector = new RegexPiiDetector(new PiiPatternRegistry());
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, true, GuardrailAction.LOG, 0.7, 32, 8);
        String unbroken = "A".repeat(1000);
        var upstream = chunksOf(unbroken, null);

        var guarded = new GuardedSseIterator(upstream, realDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText)
                .as("nothing was being withheld, so nothing should have been cut short")
                .hasSize(1000);
    }

    @Test
    void scanningDisabled_neverTerminatesOnUnbrokenOutput() {
        var config = new StreamingEnforcementConfig(false, PiiAction.LOG, false, GuardrailAction.LOG, 0.7, 32, 8);
        String unbroken = "B".repeat(1000);
        var upstream = chunksOf(unbroken, null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).hasSize(1000);
    }

    @Test
    void unbrokenText_underLogIsEmittedRatherThanStopped() {
        // LOG withholds nothing, so there is nothing a cut at the margin could split, and terminating
        // would be harm for nothing.
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, true, GuardrailAction.LOG, 0.7, 32, 8);
        var upstream = chunksOf("A".repeat(80), "B".repeat(80), null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).hasSize(160);
    }

    @Test
    void unbrokenText_underGuardrailFlagIsAlsoEmitted() {
        // FLAG records a detection and passes the content through, exactly as LOG does; only BLOCK
        // withholds. A long unbroken FLAG stream must not be terminated to protect nothing.
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, true, GuardrailAction.FLAG, 0.7, 32, 8);
        var upstream = chunksOf("A".repeat(80), "B".repeat(80), null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).hasSize(160);
    }

    @Test
    void unbrokenText_underGuardrailBlockIsAlsoEmitted_justHeldLonger() {
        // No input is refused as unscannable, whether or not the action withholds. The emit point is
        // length minus the retention and moves as the buffer grows, so progress does not depend on
        // what the text contains.
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, true, GuardrailAction.BLOCK, 0.7, 32, 8);
        var upstream = chunksOf("A".repeat(300), "B".repeat(300), null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).hasSize(600);
    }

    @Test
    void spacedValueSplitAcrossDeltas_isNotEmittedInPieces() {
        // credit_card, phone_us and iban all match ACROSS spaces, so an emit point that moved back
        // to the last whitespace would cut "4111 1111 1111 1111" at every space as it arrived a group
        // at a time, each piece matching nothing.
        String card = "4111 1111 1111 1111";
        when(piiDetector.scan(any(String.class), any())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            int at = text.indexOf(card);
            return at < 0 ? PiiScanResult.EMPTY : new PiiScanResult(List.of(
                    new PiiEntity(PiiEntityType.CREDIT_CARD, card, at, at + card.length(),
                            "credit_card", 1.0)), text);
        });
        when(piiDetector.redact(any(String.class), any())).thenAnswer(inv ->
                ((String) inv.getArgument(0)).replace(card, "[REDACTED_CARD]"));

        // The filler matters: scanAndEmit only runs once the buffer reaches the scan window, so a
        // short exchange flushes whole at the end and exercises no boundary at all. With 30 characters
        // in front, the first emit lands with the card half-arrived and the last whitespace before the
        // margin sitting INSIDE it.
        var config = new StreamingEnforcementConfig(true, PiiAction.REDACT, false, GuardrailAction.LOG,
                0.7, 32, 8, false, GuardrailAction.LOG, java.util.Map.of(), 1024);
        var upstream = chunksOf("F".repeat(30) + "4111 1111 1111 ", "1111 thanks", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).doesNotContain(card).doesNotContain("4111 1111");
        assertThat(fullText).contains("[REDACTED_CREDIT_CARD]").contains("thanks");
    }

    @Test
    void underLogTheSameSplitValueStreamsWithoutBeingHeld() {
        // The other half of the trade: LOG withholds nothing, so there is no value being kept from
        // the caller for a cut to split, and the retention drops back to the overlap margin.
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, false, GuardrailAction.LOG,
                0.7, 32, 8, false, GuardrailAction.LOG, java.util.Map.of(), 1024);
        var upstream = chunksOf("F".repeat(30) + "4111 1111 1111 ", "1111 thanks", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).isEqualTo("F".repeat(30) + "4111 1111 1111 1111 thanks");
    }

    // ---- PII split across chunk boundaries, driven by the REAL detector ----

    // The value has to be LONGER than the overlap margin to straddle the cut. With margin 16, an
    // 11-character SSN sitting at a delta boundary always falls entirely inside the retained overlap,
    // so a test built on one proves nothing. An email address is comfortably longer than the margin.
    private static final String LONG_EMAIL = "alexander.hamilton@treasury.example.com";
    private static final String EMAIL_HEAD = "alexander.hamilton@treasury";
    private static final String EMAIL_TAIL = ".example.com";

    /**
     * An email arriving in pieces must not reach the client, in whole or in fragments.
     *
     * <p>If the scan covered only the region about to be emitted, a value straddling the cut would
     * have its prefix inside that region, where it matches nothing on its own, and the prefix would
     * be emitted before the value had ever been seen complete. The remainder would match on the next
     * pass and be redacted, which is what makes this hard to notice: the output looks redacted, and
     * the first characters of the address are already gone.</p>
     *
     * <p>Driven by the real RegexPiiDetector. A mock told to match the address would match it in
     * either arrangement and say nothing about where the cut falls.</p>
     */
    @Test
    void splitEmail_isRedactedWhole_notEmittedAsAFragment() {
        var realDetector = new RegexPiiDetector(new PiiPatternRegistry());
        var upstream = chunksOf("x".repeat(60) + EMAIL_HEAD, EMAIL_TAIL, null);

        var guarded = new GuardedSseIterator(upstream, realDetector, guardrailDetector,
                auditWriter, "t1", config(PiiAction.REDACT, GuardrailAction.LOG));

        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta)
                .filter(d -> d != null)
                .reduce("", String::concat);

        assertThat(fullText)
                .as("no fragment of the address may be emitted, not just the whole of it")
                .doesNotContain("alexander")
                .doesNotContain(LONG_EMAIL);
        assertThat(fullText).contains("[REDACTED_EMAIL]");
    }

    @Test
    void splitEmail_underBlock_neverEmitsThePrefix() {
        var realDetector = new RegexPiiDetector(new PiiPatternRegistry());
        var upstream = chunksOf("x".repeat(60) + EMAIL_HEAD, EMAIL_TAIL, null);

        var guarded = new GuardedSseIterator(upstream, realDetector, guardrailDetector,
                auditWriter, "t1", config(PiiAction.BLOCK, GuardrailAction.LOG));

        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta)
                .filter(d -> d != null)
                .reduce("", String::concat);

        assertThat(fullText)
                .as("BLOCK is the strictest setting and must not leak the prefix either")
                .doesNotContain("alexander");
    }

    // ---- the terminal chunk the guard delivers carries the upstream's usage --------------

    private Iterator<SseChunk> chunksEndingWithUsage(String... segments) {
        List<SseChunk> chunks = new ArrayList<>();
        for (String seg : segments) {
            chunks.add(SseChunk.builder().id("c").model("m").delta(seg).done(false).build());
        }
        chunks.add(SseChunk.builder().id("c").model("m").finishReason("length")
                .usage(com.dvarahq.core.model.ChatResponse.Usage.builder()
                        .promptTokens(11).completionTokens(3).totalTokens(14).build())
                .done(true).build());
        return chunks.iterator();
    }

    private void assertOneTerminalCarryingUsage(List<SseChunk> result) {
        List<SseChunk> terminals = result.stream().filter(SseChunk::isDone).toList();
        assertThat(terminals).as("exactly one done chunk, and it is the last").hasSize(1);
        assertThat(result.get(result.size() - 1).isDone()).isTrue();
        SseChunk terminal = terminals.get(0);
        assertThat(terminal.getUsage()).as("the upstream's usage rides on the delivered terminal").isNotNull();
        assertThat(terminal.getUsage().getTotalTokens()).isEqualTo(14);
        assertThat(terminal.getFinishReason()).as("the upstream's finish reason, not a synthesised stop").isEqualTo("length");
    }

    /** Immediate delivery (LOG): the guard's own terminal, not a second one behind it, carries the usage. */
    @Test
    void theDeliveredTerminalCarriesTheUsage_immediate() {
        var guarded = new GuardedSseIterator(chunksEndingWithUsage("Hello", " world"), piiDetector,
                guardrailDetector, auditWriter, "t1", config(PiiAction.LOG, GuardrailAction.LOG));
        List<SseChunk> result = drain(guarded);
        assertThat(result.stream().map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat))
                .isEqualTo("Hello world");
        assertOneTerminalCarryingUsage(result);
    }

    /** Deferred delivery (REDACT withholds): the released text's terminal carries the usage. */
    @Test
    void theDeliveredTerminalCarriesTheUsage_deferred() {
        var guarded = new GuardedSseIterator(chunksEndingWithUsage("Hello", " world"), piiDetector,
                guardrailDetector, auditWriter, "t1", config(PiiAction.REDACT, GuardrailAction.LOG));
        List<SseChunk> result = drain(guarded);
        assertThat(result.stream().map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat))
                .isEqualTo("Hello world");
        assertOneTerminalCarryingUsage(result);
    }

    private StreamingEnforcementConfig config(PiiAction piiAction, GuardrailAction guardrailAction) {
        return new StreamingEnforcementConfig(true, piiAction, true, guardrailAction, 0.7, 64, 16);
    }

    /** Creates chunks from text segments; null marks the final chunk. */
    private Iterator<SseChunk> chunksOf(String... segments) {
        List<SseChunk> chunks = new ArrayList<>();
        for (int i = 0; i < segments.length; i++) {
            boolean isLast = (i == segments.length - 1) || segments[i] == null;
            if (segments[i] == null) {
                chunks.add(SseChunk.builder().id("c").model("m")
                        .finishReason("stop").done(true).build());
                break;
            }
            chunks.add(SseChunk.builder().id("c").model("m")
                    .delta(segments[i]).done(false).build());
        }
        return chunks.iterator();
    }

    private List<SseChunk> drain(Iterator<SseChunk> iterator) {
        List<SseChunk> result = new ArrayList<>();
        int safety = 0;
        while (iterator.hasNext() && safety++ < 1000) {
            result.add(iterator.next());
        }
        return result;
    }

    @Test
    void workspaceCustomPatterns_reachTheStreamingScan() {
        // A pattern a workspace defined for its own identifiers governs requests and non-streaming
        // responses, and must govern a streamed one too. Asserted on the argument rather than on the
        // output, because a mock detector would "detect" either way: what matters is what the
        // detector was told.
        java.util.Map<String, String> patterns = java.util.Map.of("customer_id", "CUST-\\d{6}");
        var config = new StreamingEnforcementConfig(true, PiiAction.REDACT, false, GuardrailAction.LOG,
                0.7, 32, 8, false, GuardrailAction.LOG, patterns);
        when(piiDetector.scan(any(String.class), any())).thenReturn(PiiScanResult.EMPTY);

        var guarded = new GuardedSseIterator(chunksOf("ref CUST-123456 done", null), piiDetector,
                guardrailDetector, auditWriter, "t1", config);
        drain(guarded);

        var captor = org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        org.mockito.Mockito.verify(piiDetector, org.mockito.Mockito.atLeastOnce())
                .scan(any(String.class), captor.capture());
        assertThat(captor.getAllValues())
                .as("the workspace's own patterns must reach the detector")
                .allSatisfy(m -> assertThat(m).isEqualTo(patterns));
    }

    // ---- Grounding: BLOCK cannot retract what has already been delivered ----

    /** Says nothing is grounded, which is the only interesting case here. */
    private static final com.dvarahq.core.guardrail.GroundingDetector UNGROUNDED = (req, resp, sources) ->
            new com.dvarahq.core.guardrail.GroundingResult(false, 0.1,
                    List.of("an ungrounded claim"), 0.1);

    @Test
    void groundingBlock_emitsNoPrefixOfALongUngroundedAnswer() {
        // Grounding can only be judged on the WHOLE answer, so it is checked once at the end. If
        // content were emitted throughout, groundingAction=BLOCK would deliver almost the entire
        // ungrounded response and then send content_filter, which retracts nothing.
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, false, GuardrailAction.LOG,
                0.7, 32, 8, true, GuardrailAction.BLOCK);
        var upstream = chunksOf("The answer is ".repeat(60), "and that concludes it.", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config, UNGROUNDED, List.of("a source document"));
        List<SseChunk> result = drain(guarded);
        String fullText = result.stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText)
                .as("not one character of a response grounding rejected may reach the caller")
                .isEmpty();
        assertThat(result.getLast().getFinishReason()).isEqualTo("content_filter");
        // The refusal carries `message`; the observation carries the similarity and the claim
        // hashes, never the claims themselves.
        assertThat(payloadOf("HALLUCINATION_DETECTED_STREAMING"))
                .containsKeys("message", "overall_similarity", "ungrounded_claim_hashes",
                        "ungrounded_claim_count", "confidence", "grounded", "source")
                .doesNotContainKey("ungrounded_claims");
    }

    @Test
    void groundingBlock_holdsEvenWhenTheAuditWriteFails() {
        // A failing audit write must not turn a block into a delivery: that would be failing open on
        // the strength of a bookkeeping error.
        AuditWriter throwing = event -> { throw new IllegalStateException("audit sink down"); };
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, false, GuardrailAction.LOG,
                0.7, 32, 8, true, GuardrailAction.BLOCK);
        var upstream = chunksOf("The answer is ".repeat(60), "and that concludes it.", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                throwing, "t1", config, UNGROUNDED, List.of("a source document"));
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText)
                .as("an audit failure must not turn a block into a delivery")
                .isEmpty();
    }

    @Test
    void groundingLog_streamsNormally() {
        // LOG records the finding and delivers the answer, so there is nothing to hold back and the
        // response must not be delayed to the end.
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, false, GuardrailAction.LOG,
                0.7, 32, 8, true, GuardrailAction.LOG);
        var upstream = chunksOf("The answer is ".repeat(60), "and that concludes it.", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config, UNGROUNDED, List.of("a source document"));
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).contains("The answer is").contains("and that concludes it.");
        assertThat(payloadOf("HALLUCINATION_DETECTED_STREAMING"))
                .containsKeys("overall_similarity", "ungrounded_claim_hashes")
                .doesNotContainKey("ungrounded_claims")
                .doesNotContainKey("message");
    }

    @Test
    void groundingDetectorFailure_underDeferred_refuses() {
        // A detector failure under Deferred refuses: nothing has left the gateway yet, nothing can
        // vouch for the text, and refusing costs one retry.
        com.dvarahq.core.guardrail.GroundingDetector broken = (req, resp, sources) -> {
            throw new IllegalStateException("embedding service unavailable");
        };
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, false, GuardrailAction.LOG,
                0.7, 32, 8, true, GuardrailAction.BLOCK);
        var upstream = chunksOf("The answer is short.", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config, broken, List.of("a source document"));
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText)
                .as("nothing can vouch for the text, and nothing has been delivered")
                .isEmpty();
    }

    // ---- Held responses are scanned exactly once, at the end ----

    @Test
    void aHeldResponseIsScannedOnce_notOncePerChunk() {
        // Re-running the detector over the WHOLE held response on every scan window would be
        // quadratic work, and for a remote detector one network call per provider chunk carrying an
        // ever-larger payload.
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(piiDetector.scan(any(String.class), any())).thenAnswer(inv -> {
            calls.incrementAndGet();
            return PiiScanResult.EMPTY;
        });
        var config = new StreamingEnforcementConfig(true, PiiAction.REDACT, false, GuardrailAction.LOG, 0.7, 32, 8);
        var upstream = chunksOf("A".repeat(60), "B".repeat(60), "C".repeat(60), "D".repeat(60), null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        drain(guarded);

        assertThat(calls.get())
                .as("one scan of the complete answer, not one per chunk")
                .isEqualTo(1);
    }

    @Test
    void anAnchoredPatternIsJudgedOnTheWholeAnswer_notOnAChunkThatEndsEarly() {
        // A pattern anchored at the end must not match a CHUNK that happens to finish with the
        // marker and block a response whose real ending is something else. Enforcement must not
        // depend on where the provider split its output: a held response has exactly one correct
        // time to be judged.
        when(piiDetector.scan(any(String.class), any())).thenAnswer(inv -> {
            String text = inv.getArgument(0);
            return text.endsWith("SECRET")
                    ? new PiiScanResult(List.of(new PiiEntity(PiiEntityType.CUSTOM, "SECRET",
                            text.length() - 6, text.length(), "anchored", 1.0)), text)
                    : PiiScanResult.EMPTY;
        });
        var config = new StreamingEnforcementConfig(true, PiiAction.BLOCK, false, GuardrailAction.LOG, 0.7, 32, 8);
        var upstream = chunksOf("the value is SECRET", "-SAFE and nothing matches", null);

        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText)
                .as("SECRET-SAFE does not match an anchored SECRET$, so nothing should be blocked")
                .contains("SECRET-SAFE and nothing matches");
    }

    @Test
    void aFinalChunkCarryingTextIsStillBounded() {
        // A chunk arriving with done=true goes straight to flushRemaining, which must apply the
        // held-size check too: it is the one path a peer fully controls.
        var config = new StreamingEnforcementConfig(true, PiiAction.REDACT, false, GuardrailAction.LOG,
                0.7, 32, 8, false, GuardrailAction.LOG, java.util.Map.of(), 200);
        List<SseChunk> chunks = new java.util.ArrayList<>();
        chunks.add(SseChunk.builder().id("c").model("m").delta("A".repeat(500))
                .finishReason("stop").done(true).build());

        var guarded = new GuardedSseIterator(chunks.iterator(), piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).isEmpty();
    }

    @Test
    void groundingBlock_refusesAPartialAnswerWhenUpstreamThrows() {
        // Grounding judges a WHOLE answer; a partial one has not been evaluated and cannot be. A
        // provider disconnecting mid-stream is ordinary, so the upstream-failure path must consult
        // grounding rather than emitting the scanned buffer.
        Iterator<SseChunk> failing = new Iterator<>() {
            private int served;
            @Override public boolean hasNext() { return true; }
            @Override public SseChunk next() {
                if (served++ < 2) {
                    return SseChunk.builder().id("c").model("m").delta("The answer is ".repeat(10)).build();
                }
                throw new IllegalStateException("provider connection reset");
            }
        };
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, false, GuardrailAction.LOG,
                0.7, 32, 8, true, GuardrailAction.BLOCK);

        var guarded = new GuardedSseIterator(failing, piiDetector, guardrailDetector,
                auditWriter, "t1", config, UNGROUNDED, List.of("a source document"));
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText)
                .as("a partial answer grounding could not judge must not reach the caller")
                .isEmpty();
    }

    @Test
    void aTruncatedStreamStillDeliversWhenGroundingIsNotBlocking() {
        // The other side: PII and guardrails are per-span and DO apply to what arrived, so a
        // truncated answer is still worth delivering when grounding is not the deciding control.
        Iterator<SseChunk> failing = new Iterator<>() {
            private int served;
            @Override public boolean hasNext() { return true; }
            @Override public SseChunk next() {
                if (served++ < 1) {
                    return SseChunk.builder().id("c").model("m").delta("a partial answer").build();
                }
                throw new IllegalStateException("provider connection reset");
            }
        };
        var config = new StreamingEnforcementConfig(true, PiiAction.LOG, false, GuardrailAction.LOG, 0.7, 32, 8);

        var guarded = new GuardedSseIterator(failing, piiDetector, guardrailDetector,
                auditWriter, "t1", config);
        String fullText = drain(guarded).stream()
                .map(SseChunk::getDelta).filter(d -> d != null).reduce("", String::concat);

        assertThat(fullText).contains("a partial answer");
    }

    /** The payload of the first event of that type, or fails the test naming the type. */
    private java.util.Map<String, Object> payloadOf(String eventType) {
        return auditEvents.stream().filter(e -> eventType.equals(e.eventType())).findFirst()
                .map(AuditEvent::payload)
                .orElseThrow(() -> new AssertionError("no " + eventType + " event was written"));
    }

    // --- releasing the transport passes straight through, touching nothing of the guard's ---

    /** An upstream that can be released, as the provider iterators are. */
    private static final class ReleasableChunks implements Iterator<SseChunk>, AutoCloseable, com.dvarahq.core.model.ReleasableUpstream {
        private final Iterator<SseChunk> delegate;
        final java.util.concurrent.atomic.AtomicInteger released = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
        ReleasableChunks(Iterator<SseChunk> delegate) { this.delegate = delegate; }
        @Override public boolean hasNext() { return delegate.hasNext(); }
        @Override public SseChunk next() { return delegate.next(); }
        @Override public void releaseTransport() { released.incrementAndGet(); }
        @Override public void close() { closed.incrementAndGet(); }
    }

    @Test
    void releaseTransport_reachesTheUpstream_andIsNotAClose() {
        var config = config(PiiAction.LOG, GuardrailAction.LOG);
        var upstream = new ReleasableChunks(chunksOf("Hello", " world", null));
        var guarded = new GuardedSseIterator(upstream, piiDetector, guardrailDetector, auditWriter, "t1", config);

        guarded.releaseTransport();

        assertThat(upstream.released.get()).as("delegated to the transport").isEqualTo(1);
        assertThat(upstream.closed.get()).as("the upstream was not closed — that is the emit thread's").isZero();
        // the guard's own state is untouched: it still delivers, and finalizes once, on this thread
        List<SseChunk> result = drain(guarded);
        assertThat(result).isNotEmpty();
        assertThat(upstream.closed.get()).as("closed once, by the consumer's own close").isLessThanOrEqualTo(1);
    }

}
