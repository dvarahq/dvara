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
 * One thing a control found, and where.
 *
 * <p>{@code confidence} is the detector's own number, carried through rather than assumed: the PII
 * layers report a score and some of them are probabilistic, and a guardrail detection carries its
 * risk score. A constant here would put a claim of certainty in an audit record on behalf of a
 * detector that never made one.</p>
 */
public record Detection(String type, String label, TextSpan span, double confidence) {
    public Detection {
        if (type == null || span == null) {
            throw new IllegalArgumentException("type and span are required");
        }
        label = label == null ? type : label;
    }
}
