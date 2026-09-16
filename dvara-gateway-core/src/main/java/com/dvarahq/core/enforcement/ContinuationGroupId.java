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
 * What makes two segments one continued body of text.
 *
 * <p>The key is <b>(owner kind, most-specific owner id)</b>. A message groups on its {@code messageId},
 * an artifact on its {@code artifactId}.</p>
 *
 * <p><b>{@code taskId} and {@code contextId} are namespace, not ownership.</b> They are deliberately
 * broader — a task contains many messages and artifacts — so grouping on either would join unrelated
 * bodies of text and invent values at the junction. They may QUALIFY an owner, so two artifacts
 * sharing an {@code artifactId} under different tasks are different owners, and they never group by
 * themselves.</p>
 *
 * @param kind      what sort of thing owns the text
 * @param ownerId   the most-specific id, or {@code null} when the protocol gave none
 * @param namespace qualifying task/context id, or {@code ""} — disambiguates, never groups
 */
public record ContinuationGroupId(Kind kind, String ownerId, String namespace) {

    /**
     * What sort of thing owns the text. {@code TOOL_ARGUMENT} is a value inside a streamed tool call's
     * JSON arguments: governed like any other content, but never part of the prose a reader
     * sees, so it is kept out of the text grounding is judged on.
     */
    public enum Kind { MESSAGE, ARTIFACT, STATUS, TEXT_STREAM, STRUCTURAL, TOOL_ARGUMENT }

    public ContinuationGroupId {
        namespace = namespace == null ? "" : namespace;
    }

    /** A plain LLM response: one owner, no structure. */
    public static ContinuationGroupId textStream() {
        return new ContinuationGroupId(Kind.TEXT_STREAM, "stream", "");
    }

    /**
     * The fallback when no owner-specific id exists: consecutive segments sharing a structural path.
     *
     * <p>The path deliberately excludes the event index — including it would make grouping across
     * events impossible, which is the defect this whole distinction exists to avoid. A broad
     * {@code taskId} present without a {@code messageId} or {@code artifactId} does not substitute;
     * the fallback applies.</p>
     */
    public static ContinuationGroupId structural(String jsonPath, String namespace) {
        return new ContinuationGroupId(Kind.STRUCTURAL, jsonPath, namespace);
    }

    /**
     * One value inside the arguments of the streamed tool call at {@code callIndex}.
     *
     * <p>The call is the owner and the value's number in document order is the namespace, so two values
     * of one call are two groups: a detector sees each on its own and can neither join them nor join
     * either to the assistant's text. Arguments that are not valid JSON have no values to number; they
     * form a single group under the empty name.</p>
     */
    public static ContinuationGroupId toolArgument(int callIndex, String valueName) {
        return new ContinuationGroupId(Kind.TOOL_ARGUMENT, "tool-call/" + callIndex, valueName);
    }
}
