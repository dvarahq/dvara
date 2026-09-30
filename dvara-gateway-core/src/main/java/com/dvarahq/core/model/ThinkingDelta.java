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
 * One fragment of a streamed block of Anthropic's extended thinking.
 *
 * <p>Anthropic streams a thinking block as its text in slices, then its signature; a redacted block comes
 * whole, as one piece of encrypted data. {@code index} joins the fragments of one block, zero-based and
 * consecutive among the response's thinking blocks, whatever Anthropic's own block numbers were. A
 * fragment that opens a block may carry no text at all.</p>
 *
 * @param index     which thinking block this fragment belongs to
 * @param text      a slice of the thinking text, or null
 * @param signature a slice of the block's signature, or null
 * @param redacted  a redacted block's whole data, or null for a thinking block
 */
public record ThinkingDelta(int index, String text, String signature, String redacted) {

    public ThinkingDelta {
        if (index < 0) {
            throw new IllegalArgumentException("a thinking block's index is zero or more, not " + index);
        }
    }

    public static ThinkingDelta text(int index, String text) {
        return new ThinkingDelta(index, text, null, null);
    }

    public static ThinkingDelta signature(int index, String signature) {
        return new ThinkingDelta(index, null, signature, null);
    }

    public static ThinkingDelta redacted(int index, String data) {
        return new ThinkingDelta(index, null, null, data);
    }

    public boolean redactedBlock() {
        return redacted != null;
    }
}
