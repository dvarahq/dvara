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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies edits to a document. Shared so both planes cannot drift: this is the one place a span
 * becomes text.
 *
 * <p>Right to left, so earlier offsets stay valid. For each edit: the segment holding the start keeps
 * its text up to that offset and takes the replacement; every fully covered segment between is
 * emptied; the segment holding the end keeps its text from that offset onward. A placeholder is
 * therefore never cut in half, and every segment outside the span keeps its exact text and its own
 * identity.</p>
 *
 * <p>No edit can cross a group boundary, because no group is ever concatenated to another.</p>
 */
public final class EditApplication {

    private EditApplication() {
    }

    public static ResponseDocument apply(ResponseDocument document, List<TextEdit> edits) {
        if (edits.isEmpty()) {
            return document;
        }
        Map<SegmentId, String> text = new LinkedHashMap<>();
        Map<SegmentId, Integer> order = new LinkedHashMap<>();
        int n = 0;
        for (ContinuationGroup group : document.groups()) {
            for (Segment s : group.segments()) {
                text.put(s.id(), s.text());
                order.put(s.id(), n++);
            }
        }

        // Right to left: later segment first, and within a segment the later offset first. Compared
        // as two fields, not folded into one int, which would overflow on a long document.
        List<TextEdit> ordered = new ArrayList<>(edits);
        ordered.sort((a, b) -> {
            int bySegment = Integer.compare(index(order, b.span().start()), index(order, a.span().start()));
            return bySegment != 0 ? bySegment
                    : Integer.compare(b.span().start().offset(), a.span().start().offset());
        });

        for (TextEdit edit : ordered) {
            applyOne(document, text, order, edit);
        }

        List<ContinuationGroup> groups = new ArrayList<>();
        for (ContinuationGroup group : document.groups()) {
            List<Segment> segments = new ArrayList<>();
            for (Segment s : group.segments()) {
                segments.add(new Segment(s.id(), text.get(s.id())));
            }
            groups.add(new ContinuationGroup(group.id(), segments));
        }
        return new ResponseDocument(groups);
    }

    private static void applyOne(ResponseDocument document, Map<SegmentId, String> text,
                                 Map<SegmentId, Integer> order, TextEdit edit) {
        SegmentPosition from = edit.span().start();
        SegmentPosition to = edit.span().endExclusive();
        if (edit.span().withinOneSegment()) {
            String current = text.get(from.segment());
            text.put(from.segment(), current.substring(0, Math.min(from.offset(), current.length()))
                    + edit.replacement()
                    + current.substring(Math.min(to.offset(), current.length())));
            return;
        }
        int first = order.get(from.segment());
        int last = order.get(to.segment());
        for (ContinuationGroup group : document.groups()) {
            for (Segment s : group.segments()) {
                int at = order.get(s.id());
                if (at < first || at > last) {
                    continue;
                }
                String current = text.get(s.id());
                if (at == first) {
                    text.put(s.id(), current.substring(0, Math.min(from.offset(), current.length()))
                            + edit.replacement());
                } else if (at == last) {
                    text.put(s.id(), current.substring(Math.min(to.offset(), current.length())));
                } else {
                    text.put(s.id(), "");
                }
            }
        }
    }

    private static int index(Map<SegmentId, Integer> order, SegmentPosition p) {
        Integer at = order.get(p.segment());
        return at == null ? 0 : at;
    }
}
