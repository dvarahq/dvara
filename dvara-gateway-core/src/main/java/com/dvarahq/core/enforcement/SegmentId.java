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

/**
 * Identifies one run of text. Always unique; never a grouping key.
 *
 * <p>Identity and grouping are separate questions. The event index is part of the identity and
 * never of the grouping key, or segments arriving in different events could never be treated as one
 * continued body. {@link ContinuationGroupId} answers the grouping question.</p>
 *
 * @param eventIndex zero-based index of the transport event this arrived in
 * @param jsonPath   path to the containing {@code parts} array, or {@code ""} for a plain text stream
 * @param partIndex  index within that array, or {@code 0}
 */
public record SegmentId(int eventIndex, String jsonPath, int partIndex) {

    public SegmentId {
        if (eventIndex < 0 || partIndex < 0) {
            throw new IllegalArgumentException("indices must be >= 0");
        }
        jsonPath = jsonPath == null ? "" : jsonPath;
    }

    /** The LLM plane has no events or JSON structure — one response is one segment. */
    public static SegmentId ofText(int eventIndex) {
        return new SegmentId(eventIndex, "", 0);
    }
}
