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

import java.util.List;

/**
 * Segments that are genuinely one continued body of text, in arrival order.
 *
 * <p>A detector evaluates a group's segments as one body — that is what lets a value split across
 * events be found. Groups are <b>never</b> joined to each other, by a separator or otherwise: there is
 * no sentinel that is universally safe ({@code [\s\S]} matches NUL through {@code \S}, {@code .}
 * matches it under most engines, and an external detector may strip control characters), so the
 * structure is preserved instead of being flattened and guessed at.</p>
 */
public record ContinuationGroup(ContinuationGroupId id, List<Segment> segments) {

    public ContinuationGroup {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    /** The group's text, as a detector sees it. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            sb.append(s.text());
        }
        return sb.toString();
    }

    /**
     * The segment and in-segment offset that group-relative {@code offset} falls at. The one mapping
     * both planes use, so a span means the same thing wherever it was computed. An offset exactly on
     * a boundary belongs to the end of the earlier segment.
     */
    public SegmentPosition positionAt(int offset) {
        if (segments.isEmpty()) {
            throw new IllegalStateException("group " + id + " has no segments to position offset " + offset + " in");
        }
        int seen = 0;
        for (Segment s : segments) {
            int length = s.text().length();
            if (offset <= seen + length) {
                return new SegmentPosition(s.id(), offset - seen);
            }
            seen += length;
        }
        Segment last = segments.get(segments.size() - 1);
        return new SegmentPosition(last.id(), last.text().length());
    }

}
