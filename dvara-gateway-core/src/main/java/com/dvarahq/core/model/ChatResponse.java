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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
// toBuilder so a "same response, one field changed" copy cannot silently drop a field when one is
// added later. Several places rebuild a ChatResponse to swap gatewayHeaders or choices, and each
// hand-written builder chain is a field-list that has to be kept in step with this class.
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    private String id;
    private String model;
    private String object;
    private long created;
    private List<Choice> choices;
    private Usage usage;
    private Map<String, String> gatewayHeaders;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private int index;
        private MultimodalMessage message;
        private String finishReason;
    }

    /**
     * What the upstream reported it spent.
     *
     * <p><b>Absent usage is a null {@code Usage}, never a zeroed one.</b> Every field here is a
     * primitive {@code int}, so a zeroed block cannot be told apart from a call that genuinely
     * consumed nothing. On the streamed path a null means the tokens are estimated from the text and
     * the row is marked {@code estimated} (see {@link SseChunk#getUsage()}). On the non-streamed path
     * there is no such fallback: a null and a zeroed block are both dropped, with no usage row and no
     * cost row. Null is still the right thing to write when the upstream said nothing: it is the
     * truth, and it is what a non-streamed fallback would key on if one is ever added.
     *
     * <p><b>The breakdown is part of the counts, never added to them.</b> {@code cachedInputTokens}
     * and {@code cacheWriteTokens} are parts of {@code promptTokens}; {@code reasoningTokens} is part
     * of {@code completionTokens}; {@code totalTokens} stays {@code promptTokens + completionTokens}.
     * A provider that reports its cache counts outside its input count has them added into
     * {@code promptTokens} by its mapper, so the relation holds for every provider. Adding a
     * breakdown field to a total would count the same tokens twice. Zero means the upstream reported
     * none, which is also what every provider that does not report them gives.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Usage {
        private int promptTokens;
        private int completionTokens;
        private int totalTokens;
        /** The part of {@code promptTokens} read from the provider's prompt cache. */
        private int cachedInputTokens;
        /** The part of {@code promptTokens} written to the provider's prompt cache. */
        private int cacheWriteTokens;
        /** The part of {@code completionTokens} the model spent reasoning, which the caller does not see as text. */
        private int reasoningTokens;

        /** The three counts alone, with no breakdown. */
        public Usage(int promptTokens, int completionTokens, int totalTokens) {
            this(promptTokens, completionTokens, totalTokens, 0, 0, 0);
        }
    }
}