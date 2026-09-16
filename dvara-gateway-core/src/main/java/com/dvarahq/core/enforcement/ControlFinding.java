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
 * What one control found and what was done about it.
 *
 * <p>Plural by design: a single response can carry a PII entity, a guardrail violation and an
 * ungrounded claim at once, and one decision cannot say so.</p>
 *
 * <p><b>{@code configuredAction} and {@code outcome} are different statements</b>, and conflating them
 * puts a false claim in the audit chain. Outbound text is never tokenized — a token handed to the
 * caller is one they could trade back — so a response enforced under {@code TOKENIZE} reports
 * {@code configuredAction=TOKENIZE} with {@code outcome=TRANSFORMED}. Nothing on the response path is
 * recoverable, so nothing on it may claim to be.</p>
 */
public record ControlFinding(Control control, String configuredAction, Outcome outcome,
                             List<Detection> detections) {

    public enum Control { PII, GUARDRAIL, GROUNDING }

    /** What happened to the text — never what the workspace asked for. */
    public enum Outcome { OBSERVED, TRANSFORMED, REFUSED }

    public ControlFinding {
        detections = detections == null ? List.of() : List.copyOf(detections);
    }
}
