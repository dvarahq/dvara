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
package com.dvarahq.core.enforcement;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared edit applier, tested where it lives. Both planes hand it spans that may begin in one
 * segment and end in another; a segment outside a span must keep its exact text and its identity,
 * and a placeholder must never be cut in half.
 */
class EditApplicationTest {

    private static Segment seg(int i, String text) {
        return new Segment(SegmentId.ofText(i), text);
    }

    private static ContinuationGroup group(List<Segment> segments) {
        return new ContinuationGroup(ContinuationGroupId.textStream(), segments);
    }

    private static TextEdit edit(ContinuationGroup g, int start, int end, String replacement) {
        return new TextEdit(new TextSpan(g.positionAt(start), g.positionAt(end)), replacement);
    }

    private static List<String> texts(ResponseDocument d) {
        return d.groups().get(0).segments().stream().map(Segment::text).toList();
    }

    @Test
    void withinOneSegment() {
        ContinuationGroup g = group(List.of(seg(0, "call 555-1234 now")));
        ResponseDocument out = EditApplication.apply(new ResponseDocument(List.of(g)),
                List.of(edit(g, 5, 13, "[PHONE]")));

        assertThat(out.flatten()).isEqualTo("call [PHONE] now");
        assertThat(out.groups().get(0).segments().get(0).id()).isEqualTo(SegmentId.ofText(0));
    }

    @Test
    void acrossTwoSegments_keepsEachSegmentsIdentity() {
        ContinuationGroup g = group(List.of(seg(0, "card 4111 1111 "), seg(1, "1111 1111 ok")));
        ResponseDocument out = EditApplication.apply(new ResponseDocument(List.of(g)),
                List.of(edit(g, 5, 24, "[CARD]")));

        assertThat(texts(out)).containsExactly("card [CARD]", " ok");
        assertThat(out.groups().get(0).segments().stream().map(Segment::id).toList())
                .containsExactly(SegmentId.ofText(0), SegmentId.ofText(1));
    }

    @Test
    void acrossThreeSegments_emptiesTheMiddleOne() {
        ContinuationGroup g = group(List.of(seg(0, "ab"), seg(1, "cd"), seg(2, "ef")));
        ResponseDocument out = EditApplication.apply(new ResponseDocument(List.of(g)),
                List.of(edit(g, 1, 5, "X")));

        assertThat(texts(out)).containsExactly("aX", "", "f");
    }

    @Test
    void twoEditsInOneSegment_applyRightToLeft_soOffsetsStayValid() {
        ContinuationGroup g = group(List.of(seg(0, "aa 111 bb 222 cc")));
        ResponseDocument out = EditApplication.apply(new ResponseDocument(List.of(g)),
                List.of(edit(g, 3, 6, "[N]"), edit(g, 10, 13, "[N]")));

        assertThat(out.flatten()).isEqualTo("aa [N] bb [N] cc");
    }

    @Test
    void noEdits_returnsTheSameDocument() {
        ResponseDocument doc = ResponseDocument.ofText("unchanged");
        assertThat(EditApplication.apply(doc, List.of())).isSameAs(doc);
    }

    /**
     * Ordering has to survive thousands of segments. A long event stream can hold well over two
     * thousand of them, and a sort key of {@code index * 1,000,000 + offset} held in an int
     * overflows at segment 2,148, which would let the edit in the later segment sort first and
     * shift the crossing edit's offsets.
     */
    @Test
    void ordering_survivesThousandsOfSegments() {
        List<Segment> segments = new ArrayList<>();
        for (int i = 0; i < 2_200; i++) {
            segments.add(seg(i, "ab"));
        }
        ContinuationGroup g = group(segments);
        int crossingStart = 2_147 * 2 + 1;   // segment 2147, offset 1
        int crossingEnd = 2_148 * 2 + 1;     // segment 2148, offset 1
        int laterStart = 2_148 * 2 + 1;      // segment 2148, offset 1
        int laterEnd = 2_148 * 2 + 2;        // segment 2148, offset 2

        ResponseDocument out = EditApplication.apply(new ResponseDocument(List.of(g)), List.of(
                edit(g, crossingStart, crossingEnd, "[Y]"),
                edit(g, laterStart, laterEnd, "[Z]")));

        List<String> texts = texts(out);
        assertThat(texts.get(2_147)).isEqualTo("a[Y]");
        assertThat(texts.get(2_148)).isEqualTo("[Z]");
        assertThat(texts.get(2_146)).isEqualTo("ab");
        assertThat(texts.get(2_149)).isEqualTo("ab");
    }

    @Test
    void positionAt_onAnEmptyGroup_saysSo() {
        ContinuationGroup empty = group(List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> empty.positionAt(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no segments");
    }
}
