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
package com.dvarahq.core.model;

/**
 * One fragment of a streamed tool call.
 *
 * <p>Providers stream a function call the way they stream text: a first fragment names the call, and
 * every fragment after it carries a slice of the JSON arguments. Several calls can be in flight at once,
 * interleaved. This record is the provider-neutral form of one such fragment, on
 * {@link SseChunk#getToolCalls()}.</p>
 *
 * <p>{@code index} is the only field that is always present, and it is what joins the fragments of one
 * call back together: fragments with the same index are one call, whatever else they carry. Adapters
 * normalise it to a consecutive, zero-based position among the response's tool calls, so a consumer
 * never sees a provider's own block numbering. {@code id} and {@code name} arrive on the first fragment
 * and are null on the rest; {@code argumentsFragment} is null on a fragment that only opens the call.</p>
 *
 * @param index             which tool call this fragment belongs to, zero-based and consecutive
 * @param id                the provider's call id, on the opening fragment; otherwise null
 * @param name              the function name, on the opening fragment; otherwise null
 * @param argumentsFragment a slice of the JSON arguments, or null
 */
public record ToolCallDelta(int index, String id, String name, String argumentsFragment) {

    public ToolCallDelta {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0, got " + index);
        }
    }

    /** The opening fragment of a call: its id and name, with or without a first slice of arguments. */
    public static ToolCallDelta open(int index, String id, String name, String argumentsFragment) {
        return new ToolCallDelta(index, id, name, argumentsFragment);
    }

    /** A continuation fragment: more arguments for a call already opened. */
    public static ToolCallDelta arguments(int index, String argumentsFragment) {
        return new ToolCallDelta(index, null, null, argumentsFragment);
    }
}
