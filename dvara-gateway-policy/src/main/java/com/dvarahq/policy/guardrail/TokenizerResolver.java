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
package com.dvarahq.policy.guardrail;

/**
 * Which tokenizer counts a model, and by how much to correct it: a model's family decides (see
 * {@link DefaultTokenizerResolver}). Replace the bean to count some family another way.
 */
@FunctionalInterface
public interface TokenizerResolver {

    /**
     * How a model is counted.
     *
     * @param family     the model's family, such as {@code openai}, {@code anthropic} or {@code unknown}
     * @param encoding   the BPE vocabulary its text is counted with: {@code o200k_base}, {@code cl100k_base},
     *                   {@code tekken} (Mistral) or {@code deepseek_v3}
     * @param exact      whether that vocabulary is the model's own. False where a family is counted with cl100k as a
     *                   stand-in, because its own tokenizer is not available locally
     * @param correction what a request's count is multiplied by, 1.0 where nothing was measured
     * @param toolOverhead tokens added to the count of a request that carries tools, 0 where nothing was measured.
     *                   Some providers put a fixed block of their own text in front of the tool definitions, which no
     *                   count of the request itself sees
     * @param requestOverhead tokens added to the count of every chat request, 0 where nothing was measured. Some
     *                   providers wrap each request in framing tokens of their own beyond the per-message ones the
     *                   count already includes
     */
    record Resolution(String family, String encoding, boolean exact, double correction, int toolOverhead,
                      int requestOverhead) {

        /** No tool overhead and no request overhead. */
        public Resolution(String family, String encoding, boolean exact, double correction) {
            this(family, encoding, exact, correction, 0, 0);
        }

        /** No request overhead. */
        public Resolution(String family, String encoding, boolean exact, double correction, int toolOverhead) {
            this(family, encoding, exact, correction, toolOverhead, 0);
        }
    }

    Resolution resolve(String model);
}
