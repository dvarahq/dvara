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
 * Everything one enforcement pass decided. Plural, because one response can carry a PII entity, a
 * guardrail violation and an ungrounded claim at the same time.
 *
 * @param document     the transformed document; segment identity is preserved
 * @param edits        ascending, non-overlapping, addressed by {@link TextSpan} so an edit may cross
 *                     two segments of one group
 * @param findings     one per control that found something
 * @param disposition  what happened to the TEXT — never what the workspace asked for
 * @param auditIntents one per semantic intent, written by the caller, never here
 */
public record EnforcementResult(ResponseDocument document, List<TextEdit> edits,
                                List<ControlFinding> findings, Disposition disposition,
                                List<AuditIntent> auditIntents) {

    public EnforcementResult {
        edits = edits == null ? List.of() : List.copyOf(edits);
        findings = findings == null ? List.of() : List.copyOf(findings);
        auditIntents = auditIntents == null ? List.of() : List.copyOf(auditIntents);
    }

    /** Nothing found, nothing changed, nothing to record. */
    public static EnforcementResult allowed(ResponseDocument document) {
        return new EnforcementResult(document, List.of(), List.of(), Disposition.ALLOWED, List.of());
    }

    public boolean refused() {
        return disposition == Disposition.REFUSED;
    }
}
