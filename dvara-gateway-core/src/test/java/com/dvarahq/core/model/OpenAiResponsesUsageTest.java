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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The usage a Responses API answer reports, as the gateway meters it. */
class OpenAiResponsesUsageTest {

    @Test
    void cacheReadsCacheWritesAndReasoning_areAllMetered() throws Exception {
        // As OpenAI reported it to a Codex CLI session (captured live).
        var usage = OpenAiResponsesBody.usage(new ObjectMapper().readTree("""
                {"input_tokens":10837,"input_tokens_details":{"cache_write_tokens":174,"cached_tokens":10660},
                 "output_tokens":118,"output_tokens_details":{"reasoning_tokens":12},"total_tokens":10955}
                """));

        assertThat(usage.getPromptTokens()).isEqualTo(10837);
        assertThat(usage.getCachedInputTokens()).isEqualTo(10660);
        assertThat(usage.getCacheWriteTokens()).isEqualTo(174);
        assertThat(usage.getReasoningTokens()).isEqualTo(12);
        assertThat(usage.getTotalTokens()).as("the parts are inside the counts, never added").isEqualTo(10955);
    }
}
