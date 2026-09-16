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
package com.dvarahq.core.provider;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractLlmProviderTest {

    private final AbstractLlmProvider provider = new AbstractLlmProvider("test-openai") {
        @Override protected List<String> modelPrefixes() { return List.of("gpt"); }
        @Override public ChatResponse chat(ChatRequest request) { return null; }
        @Override public ProviderCapabilities capabilities() { return null; }
    };

    /**
     * A provider that claims more than one namespace (the OpenAI shape), so routing across every
     * declared prefix can be checked.
     */
    private final AbstractLlmProvider multiPrefixProvider = new AbstractLlmProvider("test-multi") {
        @Override protected List<String> modelPrefixes() { return List.of("gpt", "o1", "o3", "chatgpt"); }
        @Override public ChatResponse chat(ChatRequest request) { return null; }
        @Override public ProviderCapabilities capabilities() { return null; }
    };

    @Test
    void name_returnsConstructorValue() {
        assertThat(provider.name()).isEqualTo("test-openai");
    }

    @Test
    void supports_trueWhenModelStartsWithPrefix() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_trueForExactPrefixMatch() {
        ChatRequest request = ChatRequest.builder().model("gpt").build();
        assertThat(provider.supports(request)).isTrue();
    }

    @Test
    void supports_falseWhenModelDoesNotMatch() {
        ChatRequest request = ChatRequest.builder().model("claude-3-opus").build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void supports_falseWhenModelIsNull() {
        ChatRequest request = ChatRequest.builder().model(null).build();
        assertThat(provider.supports(request)).isFalse();
    }

    @Test
    void defaultSupportsEmbedding_returnsFalse() {
        assertThat(provider.supportsEmbedding("text-embedding-3-small")).isFalse();
    }

    @Test
    void defaultStreamChat_throwsUnsupported() {
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();
        try {
            provider.streamChat(request);
            assertThat(false).as("Expected UnsupportedOperationException").isTrue();
        } catch (UnsupportedOperationException e) {
            assertThat(e.getMessage()).contains("test-openai");
        }
    }

    // ---------- Multi-prefix providers ----------

    @Test
    void supports_multiPrefix_matchesFirstNamespace() {
        // The most common case — model name matches the first prefix in the list.
        ChatRequest request = ChatRequest.builder().model("gpt-4o").build();
        assertThat(multiPrefixProvider.supports(request)).isTrue();
    }

    @Test
    void supports_multiPrefix_matchesMiddleNamespace() {
        // o-series reasoning models are mid-list — proves the loop iterates
        // beyond the first entry.
        ChatRequest request = ChatRequest.builder().model("o3-mini").build();
        assertThat(multiPrefixProvider.supports(request)).isTrue();
    }

    @Test
    void supports_multiPrefix_matchesLastNamespace() {
        // chatgpt-* is last in the fixture list — proves the loop reaches
        // the tail of the prefix list.
        ChatRequest request = ChatRequest.builder().model("chatgpt-4o-latest").build();
        assertThat(multiPrefixProvider.supports(request)).isTrue();
    }

    @Test
    void supports_multiPrefix_falseWhenNoneMatch() {
        ChatRequest request = ChatRequest.builder().model("claude-sonnet-4-5").build();
        assertThat(multiPrefixProvider.supports(request)).isFalse();
    }
}