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
     * @param encoding   the BPE vocabulary its text is counted with: {@code o200k_base} or {@code cl100k_base}
     * @param exact      whether that vocabulary is the model's own. False for every family but OpenAI's: they are
     *                   counted with cl100k as a stand-in, since their own tokenizers are not available locally
     * @param correction what a request's count is multiplied by, 1.0 by default; set per family from a measurement
     */
    record Resolution(String family, String encoding, boolean exact, double correction) {
    }

    Resolution resolve(String model);
}
