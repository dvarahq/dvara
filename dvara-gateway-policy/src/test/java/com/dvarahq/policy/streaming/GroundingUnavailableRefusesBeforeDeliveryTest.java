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
import com.dvarahq.core.enforcement.StreamingPosture;
import com.dvarahq.core.guardrail.GuardrailAction;
import com.dvarahq.core.guardrail.GuardrailDetector;
import com.dvarahq.core.guardrail.GuardrailScanResult;
import com.dvarahq.core.guardrail.GroundingDetector;
import com.dvarahq.core.guardrail.GroundingResult;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiAction;
import com.dvarahq.core.pii.PiiDetector;
import com.dvarahq.core.pii.PiiScanResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A workspace that asked for grounding is refused before a single byte is delivered.
 *
 * <p>Grounding is a whole-response control and can only be judged once the response is complete, so
 * a refusal that ran at the end of the stream would be a lie under <b>Immediate</b> delivery: by
 * then the caller would already hold the entire ungrounded response. Deferred delivery would hide
 * that, since with {@code BLOCK} the guard withholds everything until the end.
 *
 * <p>So the engine declares an unavailable control <i>up front</i>, and the guard acts on it before
 * reading the upstream at all. Nothing is emitted under either delivery mode, because nothing has
 * been read. The assertions below are about the <b>absence of the upstream text</b>, not about a
 * disposition value.
 */
class GroundingUnavailableRefusesBeforeDeliveryTest {

    private static final String SECRET = "the ungrounded answer";

    private PiiDetector piiDetector;
    private GuardrailDetector guardrailDetector;
    private List<AuditEvent> audit;
    private AuditWriter auditWriter;

    @BeforeEach
    void setUp() {
        piiDetector = mock(PiiDetector.class);
        guardrailDetector = mock(GuardrailDetector.class);
        when(piiDetector.scanDocument(any(), any())).thenReturn(List.of());
        when(guardrailDetector.scanDocument(any(), any())).thenReturn(List.of());
        when(piiDetector.scan(any(String.class), any())).thenReturn(PiiScanResult.EMPTY);
        when(guardrailDetector.scan(any(String.class), any())).thenReturn(GuardrailScanResult.EMPTY);
        audit = new ArrayList<>();
        auditWriter = audit::add;
    }

    // ---------------------------------------------------------------- the two delivery modes

    @Test
    @DisplayName("Immediate delivery (grounding LOG): none of the upstream text reaches the caller")
    void immediateDeliveryEmitsNothing() {
        StreamingPosture posture = grounding(GuardrailAction.LOG);
        assertThat(posture.withholds())
                .describedAs("the point of this case: nothing is being withheld, so a refusal at the "
                        + "END of the stream would arrive after the whole response had been delivered")
                .isFalse();

        List<SseChunk> out = drain(guard(posture));

        assertThat(text(out))
                .describedAs("not one fragment of the ungrounded response was relayed")
                .doesNotContain(SECRET)
                .isEmpty();
        assertThat(out).describedAs("a terminal refusal, and nothing else").hasSize(1);
        assertThat(out.getLast().isDone()).isTrue();
        assertThat(out.getLast().getFinishReason()).isEqualTo("content_filter");
    }

    @Test
    @DisplayName("Deferred delivery (grounding BLOCK): the same, nothing is emitted")
    void deferredDeliveryEmitsNothing() {
        StreamingPosture posture = grounding(GuardrailAction.BLOCK);
        assertThat(posture.withholds()).isTrue();

        List<SseChunk> out = drain(guard(posture));

        assertThat(text(out)).doesNotContain(SECRET).isEmpty();
        assertThat(out).hasSize(1);
        assertThat(out.getLast().getFinishReason()).isEqualTo("content_filter");
    }

    // ---------------------------------------------------------------- the record

    @Test
    @DisplayName("the audit event is GROUNDING_UNAVAILABLE and carries its four keys")
    void theAuditIntentNamesTheMissingCapability() {
        drain(guard(grounding(GuardrailAction.LOG)));

        assertThat(audit).extracting(AuditEvent::eventType).contains("GROUNDING_UNAVAILABLE");
        AuditEvent event = audit.stream()
                .filter(e -> "GROUNDING_UNAVAILABLE".equals(e.eventType())).findFirst().orElseThrow();

        assertThat(event.payload())
                .describedAs("exactly the four keys the event carries, neither narrowed nor widened")
                .containsOnlyKeys("workspace_id", "source", "reason", "message");
        assertThat(event.payload().get("workspace_id")).isEqualTo("t1");
        assertThat(event.payload().get("source")).isEqualTo("streaming_response");
        assertThat(event.payload().get("reason"))
                .describedAs("machine-readable, so a query over the audit trail does not match prose")
                .isEqualTo("no_grounding_detector");
        assertThat(String.valueOf(event.payload().get("message")))
                .describedAs("an operator reading this must learn the build lacks the detector, not "
                        + "that a response failed a grounding check it never ran")
                .contains("no grounding detector");
    }

    @Test
    @DisplayName("the other refusals keep their own two-key payload")
    void otherRefusalsAreUnchanged() {
        // STREAM_ERROR travels the same refuse() path and carries message + source, so the extra keys
        // must not leak into it. Reached by making enforcement itself fail while the guard is
        // withholding.
        when(piiDetector.scanDocument(any(), any()))
                .thenThrow(new IllegalStateException("detector exploded"));

        drain(new GuardedSseIterator(chunksOf(SECRET, null), engine(null), auditWriter, "t1",
                config(),
                new StreamingPosture(true, PiiAction.BLOCK, Map.of(), true, GuardrailAction.LOG, 0.7,
                        false, GuardrailAction.LOG, List.of()), null));

        AuditEvent error = audit.stream()
                .filter(e -> "STREAM_ERROR".equals(e.eventType())).findFirst().orElseThrow();
        assertThat(error.payload()).containsOnlyKeys("message", "source");
    }

    @Test
    @DisplayName("the upstream is never read, so a refusal cannot follow a partial delivery")
    void theUpstreamIsNeverRead() {
        var counting = new CountingIterator(chunksOf(SECRET, null));

        drain(new GuardedSseIterator(counting, engine(null), auditWriter, "t1",
                config(), grounding(GuardrailAction.LOG), null));

        assertThat(counting.touches)
                .describedAs("hasNext() is counted as well as next(): a provider iterator commonly "
                        + "performs the blocking read there, so a guard that called only hasNext() "
                        + "would still have gone upstream")
                .isZero();
    }

    // ---------------------------------------------------------------- the control

    @Test
    @DisplayName("with a detector present, the stream is served")
    void aRealDetectorKeepsExistingBehaviour() {
        GroundingDetector detector = mock(GroundingDetector.class);
        when(detector.check(any(), any(), any())).thenReturn(GroundingResult.GROUNDED);

        List<SseChunk> out = drain(new GuardedSseIterator(chunksOf(SECRET, null),
                engine(detector), auditWriter, "t1", config(),
                grounding(GuardrailAction.LOG), null));

        assertThat(text(out))
                .describedAs("the refusal is about the missing capability, not about grounding itself")
                .isEqualTo(SECRET);
        assertThat(audit).extracting(AuditEvent::eventType).doesNotContain("GROUNDING_UNAVAILABLE");
    }

    @Test
    @DisplayName("a workspace that did not ask for grounding is unaffected")
    void groundingOffIsUnaffected() {
        StreamingPosture posture = new StreamingPosture(
                true, PiiAction.LOG, Map.of(), true, GuardrailAction.LOG, 0.7,
                false, GuardrailAction.LOG, List.of());

        List<SseChunk> out = drain(guard(posture));

        assertThat(text(out))
                .describedAs("absence of a control nobody asked for is not a refusal")
                .isEqualTo(SECRET);
        assertThat(audit).extracting(AuditEvent::eventType).doesNotContain("GROUNDING_UNAVAILABLE");
    }

    @Test
    @DisplayName("grounding enabled but with no sources is not a refusal either")
    void groundingWithNoSourcesIsUnaffected() {
        StreamingPosture posture = new StreamingPosture(
                true, PiiAction.LOG, Map.of(), true, GuardrailAction.LOG, 0.7,
                true, GuardrailAction.LOG, List.of());
        assertThat(posture.groundingActive())
                .describedAs("no sources means there is nothing to ground against, so the control is "
                        + "not active and the missing detector does not matter")
                .isFalse();

        assertThat(text(drain(guard(posture)))).isEqualTo(SECRET);
    }

    // ---------------------------------------------------------------- helpers

    private StreamingPosture grounding(GuardrailAction action) {
        return new StreamingPosture(true, PiiAction.LOG, Map.of(), true, GuardrailAction.LOG, 0.7,
                true, action, List.of("a source document"));
    }

    private DefaultStreamingEnforcementEngine engine(GroundingDetector grounding) {
        return new DefaultStreamingEnforcementEngine(piiDetector, guardrailDetector, grounding);
    }

    private GuardedSseIterator guard(StreamingPosture posture) {
        return new GuardedSseIterator(chunksOf(SECRET, null), engine(null), auditWriter, "t1",
                config(), posture, null);
    }

    private StreamingEnforcementConfig config() {
        return new StreamingEnforcementConfig(true, PiiAction.LOG, true, GuardrailAction.LOG,
                0.7, 64, 16);
    }

    private String text(List<SseChunk> chunks) {
        return chunks.stream().map(SseChunk::getDelta).filter(d -> d != null)
                .reduce("", String::concat);
    }

    private Iterator<SseChunk> chunksOf(String... segments) {
        List<SseChunk> chunks = new ArrayList<>();
        for (String segment : segments) {
            if (segment == null) {
                chunks.add(SseChunk.builder().id("c").model("m")
                        .finishReason("stop").done(true).build());
                break;
            }
            chunks.add(SseChunk.builder().id("c").model("m").delta(segment).done(false).build());
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

    /**
     * Counts every touch of the upstream, {@code hasNext()} included — many provider iterators do
     * the blocking read there, so "never read" has to mean neither method was called.
     */
    private static final class CountingIterator implements Iterator<SseChunk> {
        private final Iterator<SseChunk> delegate;
        int touches;

        CountingIterator(Iterator<SseChunk> delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean hasNext() {
            touches++;
            return delegate.hasNext();
        }

        @Override
        public SseChunk next() {
            touches++;
            return delegate.next();
        }
    }
}
