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

import com.dvarahq.core.enforcement.ContinuationGroup;
import com.dvarahq.core.enforcement.ContinuationGroupId;
import com.dvarahq.core.enforcement.ControlFinding;
import com.dvarahq.core.enforcement.Disposition;
import com.dvarahq.core.enforcement.EnforcementResult;
import com.dvarahq.core.enforcement.ResponseDocument;
import com.dvarahq.core.enforcement.Segment;
import com.dvarahq.core.enforcement.SegmentId;
import com.dvarahq.core.enforcement.StreamingPosture;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.pii.PiiPatternRegistry;
import com.dvarahq.pii.RegexPiiDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The engine's contract, independent of either guard.
 *
 * <p>These are the properties the guards depend on and cannot themselves establish: that enforcement
 * is pure, that each detector is called at most once, that a span may cross two segments of a group,
 * that unrelated groups are never joined, and that outbound TOKENIZE reports what actually happened
 * to the text rather than what the workspace asked for.</p>
 */
class StreamingEnforcementEngineTest {

    private static final String EMAIL = "alice@example.com";

    private final AtomicInteger piiCalls = new AtomicInteger();

    private DefaultStreamingEnforcementEngine engine() {
        var real = new RegexPiiDetector(new PiiPatternRegistry());
        var counting = new com.dvarahq.core.pii.PiiDetector() {
            // Counts the DOCUMENT call, which is the invariant: at most one invocation of each
            // enabled detector per response. How many groups it evaluates inside that call is the
            // detector's business — the default loops, a remote one would batch.
            @Override public List<com.dvarahq.core.pii.PiiScanResult> scanDocument(
                    com.dvarahq.core.enforcement.ResponseDocument d, Map<String, String> p) {
                piiCalls.incrementAndGet();
                return com.dvarahq.core.pii.PiiDetector.super.scanDocument(d, p);
            }
            @Override public com.dvarahq.core.pii.PiiScanResult scan(String t, Map<String, String> p) {
                return real.scan(t, p);
            }
            @Override public List<com.dvarahq.core.pii.PiiScanResult> scanRequest(
                    com.dvarahq.core.model.ChatRequest r, Map<String, String> p) { return List.of(); }
            @Override public List<com.dvarahq.core.pii.PiiScanResult> scanResponse(
                    com.dvarahq.core.model.ChatResponse r, Map<String, String> p) { return List.of(); }
            @Override public String redact(String t, List<com.dvarahq.core.pii.PiiEntity> e) {
                return real.redact(t, e);
            }
        };
        return new DefaultStreamingEnforcementEngine(counting, null, null);
    }

    private static StreamingPosture posture(PiiAction action) {
        return new StreamingPosture(true, action, Map.of(), false, GuardrailAction.LOG, 0.7,
                false, GuardrailAction.LOG, List.of());
    }

    // ------------------------------------------------------------------ detection confidence

    @Test
    @DisplayName("a PII detection carries the detector's own confidence, not a constant")
    void piiDetectionCarriesTheDetectorsConfidence() {
        com.dvarahq.core.pii.PiiDetector unsure = new com.dvarahq.core.pii.PiiDetector() {
            @Override public com.dvarahq.core.pii.PiiScanResult scan(String text, Map<String, String> patterns) {
                return new com.dvarahq.core.pii.PiiScanResult(List.of(new com.dvarahq.core.pii.PiiEntity(
                        com.dvarahq.core.pii.PiiEntityType.PERSON_NAME, "Robin", 6, 11, "person", 0.42)), text);
            }
            @Override public List<com.dvarahq.core.pii.PiiScanResult> scanRequest(
                    com.dvarahq.core.model.ChatRequest r, Map<String, String> p) { return List.of(); }
            @Override public List<com.dvarahq.core.pii.PiiScanResult> scanResponse(
                    com.dvarahq.core.model.ChatResponse r, Map<String, String> p) { return List.of(); }
            @Override public String redact(String text, List<com.dvarahq.core.pii.PiiEntity> entities) {
                return text;
            }
        };
        var engine = new DefaultStreamingEnforcementEngine(unsure, null, null);

        var result = engine.enforce(com.dvarahq.core.enforcement.ResponseDocument.ofText("Hello Robin"),
                posture(PiiAction.LOG), "t1");

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().getFirst().detections())
                .singleElement()
                .extracting(com.dvarahq.core.enforcement.Detection::confidence)
                .isEqualTo(0.42);
    }

    // ------------------------------------------------------------------ tool-call arguments

    @Test
    @DisplayName("grounding is judged on the prose alone; a tool call's arguments are not part of the answer")
    void groundingSeesOnlyAssistantText() {
        java.util.concurrent.atomic.AtomicReference<com.dvarahq.core.model.ChatResponse> judged =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.dvarahq.core.guardrail.GroundingDetector spy = (request, response, sources) -> {
            judged.set(response);
            return com.dvarahq.core.guardrail.GroundingResult.GROUNDED;
        };
        var engine = new DefaultStreamingEnforcementEngine(new RegexPiiDetector(new PiiPatternRegistry()), null, spy);
        var doc = new com.dvarahq.core.enforcement.ResponseDocument(List.of(
                com.dvarahq.core.enforcement.ResponseDocument.ofText("Your order shipped yesterday.").groups().getFirst(),
                new com.dvarahq.core.enforcement.ContinuationGroup(
                        com.dvarahq.core.enforcement.ContinuationGroupId.toolArgument(0, "/order_id"),
                        List.of(new com.dvarahq.core.enforcement.Segment(
                                new com.dvarahq.core.enforcement.SegmentId(0, "/tool-call/0/order_id", 0), "4471")))));
        var posture = new StreamingPosture(true, PiiAction.LOG, Map.of(), false, GuardrailAction.LOG, 0.7,
                true, GuardrailAction.BLOCK, List.of("Order 4471 shipped on Tuesday."));

        engine.enforce(doc, posture, "t1");

        assertThat(judged.get()).isNotNull();
        assertThat(judged.get().toString()).contains("Your order shipped yesterday.").doesNotContain("4471");
        assertThat(doc.assistantText()).isEqualTo("Your order shipped yesterday.");
        assertThat(doc.flatten()).isEqualTo("Your order shipped yesterday.4471");
    }

    @Test
    @DisplayName("a bare tool call has no prose, so grounding is not asked about it")
    void groundingIsNotInvokedWithoutProse() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        com.dvarahq.core.guardrail.GroundingDetector spy = (request, response, sources) -> {
            calls.incrementAndGet();
            return new com.dvarahq.core.guardrail.GroundingResult(false, 0.0, List.of("blank"), 0.0);
        };
        var engine = new DefaultStreamingEnforcementEngine(new RegexPiiDetector(new PiiPatternRegistry()), null, spy);
        var doc = new com.dvarahq.core.enforcement.ResponseDocument(List.of(
                new com.dvarahq.core.enforcement.ContinuationGroup(
                        com.dvarahq.core.enforcement.ContinuationGroupId.toolArgument(0, "/order_id"),
                        List.of(new com.dvarahq.core.enforcement.Segment(
                                new com.dvarahq.core.enforcement.SegmentId(0, "/tool-call/0/order_id", 0), "4471")))));
        var posture = new StreamingPosture(true, PiiAction.LOG, Map.of(), false, GuardrailAction.LOG, 0.7,
                true, GuardrailAction.BLOCK, List.of("Order 4471 shipped on Tuesday."));

        EnforcementResult result = engine.enforce(doc, posture, "t1");

        assertThat(calls.get()).isZero();
        assertThat(result.refused()).isFalse();
    }

    // ------------------------------------------------------------------ G · call counts

    @Test
    @DisplayName("one detector call for a document of many segments in one group")
    void oneCallPerGroupedDocument() {
        engine().enforce(document(List.of("write to ali", "ce@example.com now")), posture(PiiAction.REDACT), "t1");

        assertThat(piiCalls.get())
                .as("segments of one group are one body, evaluated in one pass")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("enforcement is pure — running it twice changes nothing and costs nothing extra")
    void enforcementIsPure() {
        var e = engine();
        ResponseDocument doc = document(List.of("mail " + EMAIL));

        EnforcementResult first = e.enforce(doc, posture(PiiAction.REDACT), "t1");
        EnforcementResult second = e.enforce(doc, posture(PiiAction.REDACT), "t1");

        assertThat(first.document().flatten()).isEqualTo(second.document().flatten());
        assertThat(doc.flatten())
                .as("the input document is never mutated")
                .contains(EMAIL);
        assertThat(first.auditIntents())
                .as("intents are returned, never written")
                .hasSize(1);
    }

    // ------------------------------------------------------------------ spans

    @Test
    @DisplayName("a value split across two segments is found and removed from both")
    void splitValueAcrossSegments() {
        EnforcementResult result = engine()
                .enforce(document(List.of("write to ali", "ce@example.com now")),
                        posture(PiiAction.REDACT), "t1");

        assertThat(result.disposition()).isEqualTo(Disposition.TRANSFORMED);
        assertThat(result.document().flatten())
                .doesNotContain(EMAIL).doesNotContain("ali").contains("[REDACTED_EMAIL]")
                .contains("write to ").contains(" now");
    }

    @Test
    @DisplayName("unrelated groups are never joined, so no value is invented at the junction")
    void unrelatedGroupsAreNotJoined() {
        // Two owners whose texts would form an address only if concatenated. They share a namespace,
        // which must not group them: taskId is context, not ownership.
        ResponseDocument doc = new ResponseDocument(List.of(
                group("msg-1", "task-9", List.of("write to alice@exa")),
                group("art-1", "task-9", List.of("mple.com is unrelated"))));

        EnforcementResult result = engine().enforce(doc, posture(PiiAction.REDACT), "t1");

        assertThat(result.disposition())
                .as("neither group contains an address on its own")
                .isEqualTo(Disposition.ALLOWED);
        assertThat(result.document().flatten()).contains("write to alice@exa").contains("mple.com is unrelated");
        assertThat(piiCalls.get())
                .as("two groups, still ONE invocation of the detector")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ reporting

    @Test
    @DisplayName("outbound TOKENIZE reports TRANSFORMED, and keeps the configured action")
    void tokenizeReportsTransformed() {
        EnforcementResult result = engine()
                .enforce(document(List.of("mail " + EMAIL)), posture(PiiAction.TOKENIZE), "t1");

        assertThat(result.disposition()).isEqualTo(Disposition.TRANSFORMED);
        ControlFinding finding = result.findings().getFirst();
        assertThat(finding.configuredAction())
                .as("what the workspace asked for is preserved verbatim")
                .isEqualTo("TOKENIZE");
        assertThat(finding.outcome())
                .as("what happened to the text — nothing on the response path is recoverable")
                .isEqualTo(ControlFinding.Outcome.TRANSFORMED);
        assertThat(result.document().flatten()).contains("[REDACTED_EMAIL]").doesNotContain("{{PII_");
    }

    @Test
    @DisplayName("BLOCK is a value, not an exception, and transforms nothing")
    void blockIsAValue() {
        EnforcementResult result = engine()
                .enforce(document(List.of("mail " + EMAIL)), posture(PiiAction.BLOCK), "t1");

        assertThat(result.refused()).isTrue();
        assertThat(result.edits()).isEmpty();
        assertThat(result.auditIntents()).extracting(i -> i.eventType())
                .containsExactly("PII_BLOCKED_STREAMING");
    }

    @Test
    @DisplayName("LOG observes without changing the text")
    void logObserves() {
        EnforcementResult result = engine()
                .enforce(document(List.of("mail " + EMAIL)), posture(PiiAction.LOG), "t1");

        assertThat(result.disposition()).isEqualTo(Disposition.ALLOWED);
        assertThat(result.document().flatten()).contains(EMAIL);
        assertThat(result.findings().getFirst().outcome()).isEqualTo(ControlFinding.Outcome.OBSERVED);
        assertThat(result.auditIntents()).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private static ResponseDocument document(List<String> segments) {
        return new ResponseDocument(List.of(group("msg-1", "", segments)));
    }

    private static ContinuationGroup group(String ownerId, String namespace, List<String> texts) {
        List<Segment> segments = new java.util.ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            segments.add(new Segment(new SegmentId(i, "/result/" + ownerId, 0), texts.get(i)));
        }
        return new ContinuationGroup(
                new ContinuationGroupId(ContinuationGroupId.Kind.MESSAGE, ownerId, namespace), segments);
    }
}
