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

/** Everything a response says, with its structure intact. */
public record ResponseDocument(List<ContinuationGroup> groups) {

    public ResponseDocument {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /** A plain LLM response: one group, one segment. */
    public static ResponseDocument ofText(String text) {
        return new ResponseDocument(List.of(new ContinuationGroup(
                ContinuationGroupId.textStream(),
                List.of(new Segment(SegmentId.ofText(0), text)))));
    }

    public boolean isEmpty() {
        return groups.stream().allMatch(g -> g.text().isEmpty());
    }

    public int characterCount() {
        return groups.stream().mapToInt(g -> g.text().length()).sum();
    }

    /** Concatenation of every group, for a caller that has no structure to preserve. */
    public String flatten() {
        StringBuilder sb = new StringBuilder();
        for (ContinuationGroup g : groups) {
            sb.append(g.text());
        }
        return sb.toString();
    }

    /**
     * The prose a reader sees: every group except tool-call arguments.
     *
     * <p>Grounding judges an answer against its sources, and a function's arguments are not part of
     * the answer — a model calling {@code lookup_order(id: 4471)} has asserted nothing to a reader. Run
     * over {@link #flatten()} it would judge the arguments too and flag an ordinary call as
     * unsupported by the sources. PII and guardrail scanning see every group, including these, through
     * {@link #groups()}.</p>
     */
    public String assistantText() {
        StringBuilder sb = new StringBuilder();
        for (ContinuationGroup g : groups) {
            if (g.id().kind() != ContinuationGroupId.Kind.TOOL_ARGUMENT) {
                sb.append(g.text());
            }
        }
        return sb.toString();
    }
}
