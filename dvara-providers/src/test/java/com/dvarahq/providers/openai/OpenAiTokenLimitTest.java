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
package com.dvarahq.providers.openai;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiTokenLimitTest {

    @ParameterizedTest
    @ValueSource(strings = {"gpt-5", "gpt-5-mini", "gpt-5.4-mini", "gpt-5-nano-2025-08-07", "GPT-5", "gpt-6",
            "o1", "o1-preview", "o1-mini", "o3", "o3-mini", "o4-mini", "o4-mini-2025-04-16",
            "azure/gpt-5-mini", "azure/o3-mini", "openai/gpt-5"})
    void reasoningModels_takeMaxCompletionTokens(String model) {
        assertThat(OpenAiTokenLimit.fieldFor(model)).isEqualTo("max_completion_tokens");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-4o", "gpt-4o-mini", "gpt-4.1-mini", "gpt-4-turbo", "gpt-3.5-turbo",
            "chatgpt-4o-latest", "gpt-oss-120b", "omni-moderation-latest", "o", "azure/gpt-4o",
            "azure/my-deployment", "deepseek-chat", "", "gpt-99999999999"})
    void otherModels_keepMaxTokens(String model) {
        assertThat(OpenAiTokenLimit.fieldFor(model)).isEqualTo("max_tokens");
    }

    @org.junit.jupiter.api.Test
    void noModel_keepsMaxTokens() {
        assertThat(OpenAiTokenLimit.fieldFor(null)).isEqualTo("max_tokens");
    }
}
