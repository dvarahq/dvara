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
 * Decides what happens to one streamed response. Pure: computing a result writes nothing.
 *
 * <p>{@code PiiEnforcer} is deprecated and adapted onto this interface.</p>
 *
 * <h2>Called once</h2>
 *
 * <p><b>One invocation per logical response</b>, at termination. Everything before it is transport:
 * buffering, delivery, segment bookkeeping. The implementation makes <b>at most one call to each
 * enabled detector</b>, handing it the whole document so it can evaluate each group independently —
 * not once per group, per segment or per event.</p>
 *
 * <p>"At most", not "exactly": a refusal on an overflowed buffer invokes nothing, and a response with
 * no scannable text invokes nothing. A completed, within-bound response requires exactly one.</p>
 *
 * <h2>Why it returns rather than throws</h2>
 *
 * <p>A refusal is {@link Disposition#REFUSED} on the result. A guard has to queue its refusal chunk
 * before recording anything, and it cannot do that if the decision arrives as an exception.</p>
 */
public interface StreamingEnforcementEngine {

    /**
     * @param document what the response says, with its structure intact
     * @param posture  the posture resolved before the first chunk
     * @param workspaceId the workspace, for detector configuration and the audit payload
     */
    EnforcementResult enforce(ResponseDocument document, StreamingPosture posture, String workspaceId);

    /**
     * Why this engine cannot honour the posture, asked once before the first chunk is read.
     *
     * <p>An enabled control with no implementation behind it cannot be reported at enforcement time.
     * Enforcement happens at the end of the response, and under immediate delivery the text has
     * already reached the caller by then — so a refusal raised there refuses nothing and describes a
     * response that was in fact delivered. The guard therefore asks before it emits, and a control
     * that is enabled and unavailable refuses the response instead of starting it.
     *
     * <p>Empty means every enabled control has something behind it. A present value is the reason,
     * for the caller and the audit record.
     */
    default java.util.Optional<UnavailableControl> unavailableControl(StreamingPosture posture) {
        return java.util.Optional.empty();
    }

    /**
     * A control the posture asks for that this build cannot perform.
     *
     * <p>Named by the engine rather than by the guard, so the guard does not have to know which
     * controls exist. The fields are exactly what the refusal's audit record carries, because a
     * refusal nobody can attribute afterwards is worth very little.
     *
     * @param code    the audit event type and log identifier, e.g. {@code GROUNDING_UNAVAILABLE}.
     *                Not an error code on this path: nothing maps it to a status, because a refused
     *                stream has already returned 200 and is terminated with a chunk, not an error
     * @param reason  the machine-readable cause, e.g. {@code no_grounding_detector}, so a query over
     *                the audit trail does not have to match on prose
     * @param message what is missing and why, for the operator reading the record
     */
    record UnavailableControl(String code, String reason, String message) {}
}
