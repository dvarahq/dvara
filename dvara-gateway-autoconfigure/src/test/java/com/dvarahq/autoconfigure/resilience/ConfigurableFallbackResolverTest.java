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
package com.dvarahq.autoconfigure.resilience;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfigurableFallbackResolverTest {

    private final ConfigurableFallbackResolver resolver = new ConfigurableFallbackResolver();

    // -------------------------------------------------------------------------
    // Basic resolution
    // -------------------------------------------------------------------------

    @Test
    void resolve_excludesFailedProvider() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        ChatRequest request = chatRequest("gpt-4o");
        List<LlmProvider> fallbacks = resolver.resolve(request, primary, List.of(primary, secondary));

        assertThat(fallbacks).hasSize(1);
        assertThat(fallbacks.get(0).name()).isEqualTo("anthropic");
    }

    @Test
    void resolve_onlyReturnsSupportingProviders() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider supports = mockProvider("anthropic", true);
        LlmProvider doesNotSupport = mockProvider("ollama", false);

        ChatRequest request = chatRequest("gpt-4o");
        List<LlmProvider> fallbacks = resolver.resolve(request, primary,
                List.of(primary, supports, doesNotSupport));

        assertThat(fallbacks).hasSize(1);
        assertThat(fallbacks.get(0).name()).isEqualTo("anthropic");
    }

    @Test
    void resolve_returnsEmptyWhenNoFallbacksAvailable() {
        LlmProvider primary = mockProvider("openai", true);

        ChatRequest request = chatRequest("gpt-4o");
        List<LlmProvider> fallbacks = resolver.resolve(request, primary, List.of(primary));

        assertThat(fallbacks).isEmpty();
    }

    @Test
    void resolve_returnsMultipleFallbacks() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider fallback1 = mockProvider("anthropic", true);
        LlmProvider fallback2 = mockProvider("bedrock", true);

        ChatRequest request = chatRequest("gpt-4o");
        List<LlmProvider> fallbacks = resolver.resolve(request, primary,
                List.of(primary, fallback1, fallback2));

        assertThat(fallbacks).hasSize(2);
        assertThat(fallbacks).extracting(LlmProvider::name)
                .containsExactly("anthropic", "bedrock");
    }

    // -------------------------------------------------------------------------
    // Order preservation
    // -------------------------------------------------------------------------

    @Test
    void resolve_preservesProviderOrder() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider a = mockProvider("alpha", true);
        LlmProvider b = mockProvider("beta", true);
        LlmProvider c = mockProvider("gamma", true);

        ChatRequest request = chatRequest("gpt-4o");
        List<LlmProvider> fallbacks = resolver.resolve(request, primary,
                List.of(primary, a, b, c));

        assertThat(fallbacks).extracting(LlmProvider::name)
                .containsExactly("alpha", "beta", "gamma");
    }

    // -------------------------------------------------------------------------
    // Edge cases
    // -------------------------------------------------------------------------

    @Test
    void resolve_emptyProviderList() {
        LlmProvider primary = mockProvider("openai", true);

        List<LlmProvider> fallbacks = resolver.resolve(chatRequest("gpt-4o"), primary, List.of());

        assertThat(fallbacks).isEmpty();
    }

    @Test
    void resolve_allProvidersDoNotSupportRequest() {
        LlmProvider primary = mockProvider("openai", false);
        LlmProvider other1 = mockProvider("anthropic", false);
        LlmProvider other2 = mockProvider("gemini", false);

        List<LlmProvider> fallbacks = resolver.resolve(chatRequest("gpt-4o"), primary,
                List.of(primary, other1, other2));

        assertThat(fallbacks).isEmpty();
    }

    @Test
    void resolve_returnsMutableList() {
        LlmProvider primary = mockProvider("openai", true);
        LlmProvider secondary = mockProvider("anthropic", true);

        List<LlmProvider> fallbacks = resolver.resolve(chatRequest("gpt-4o"), primary,
                List.of(primary, secondary));

        // The returned list must accept additions.
        fallbacks.add(mockProvider("extra", true));
        assertThat(fallbacks).hasSize(2);
    }

    @Test
    void resolve_failedProviderNotInList() {
        LlmProvider failed = mockProvider("openai", true);
        LlmProvider available = mockProvider("anthropic", true);

        // The failed provider is not in the candidate list; every supporting candidate is returned.
        List<LlmProvider> fallbacks = resolver.resolve(chatRequest("gpt-4o"), failed,
                List.of(available));

        assertThat(fallbacks).hasSize(1);
        assertThat(fallbacks.get(0).name()).isEqualTo("anthropic");
    }

    // -------------------------------------------------------------------------
    // Vision on failover
    // -------------------------------------------------------------------------

    /**
     * A request carrying an image is not failed over to a provider that declares no vision support.
     * The gateway is choosing the substitute here, not the caller, so excluding those providers turns
     * an unpredictable upstream outcome into a stated {@code FAILOVER_CAPABILITY_MISMATCH}.
     */
    @Test
    void resolve_excludesANonVisionProviderWhenTheRequestCarriesAnImage() {
        LlmProvider primary = visionProvider("openai", true);
        LlmProvider visionCapable = visionProvider("anthropic", true);
        LlmProvider textOnly = visionProvider("mistral", false);

        List<LlmProvider> fallbacks = resolver.resolve(imageRequest(), primary,
                List.of(primary, visionCapable, textOnly));

        assertThat(fallbacks).extracting(LlmProvider::name).containsExactly("anthropic");
    }

    /** With no image in the request, vision says nothing about who may serve it. */
    @Test
    void resolve_keepsATextOnlyProviderWhenThereIsNoImage() {
        LlmProvider primary = visionProvider("openai", true);
        LlmProvider textOnly = visionProvider("mistral", false);

        List<LlmProvider> fallbacks = resolver.resolve(chatRequest("gpt-4o"), primary,
                List.of(primary, textOnly));

        assertThat(fallbacks).extracting(LlmProvider::name).containsExactly("mistral");
    }

    private static LlmProvider visionProvider(String name, boolean vision) {
        LlmProvider provider = mockProvider(name, true);
        when(provider.capabilities()).thenReturn(new ProviderCapabilities(
                true, vision, true, true, true, false, true, 128_000));
        return provider;
    }

    private static ChatRequest imageRequest() {
        return ChatRequest.builder()
                .model("gpt-4o")
                .messages(List.of(MultimodalMessage.builder()
                        .role("user")
                        .content(List.of(new ContentBlock.TextBlock("what is in this picture?"),
                                new ContentBlock.ImageBlock("image/png", "aGVsbG8=")))
                        .build()))
                .build();
    }

    private static LlmProvider mockProvider(String name, boolean supports) {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.name()).thenReturn(name);
        when(provider.supports(any())).thenReturn(supports);
        return provider;
    }

    private static ChatRequest chatRequest(String model) {
        return ChatRequest.builder()
                .model(model)
                .messages(List.of(MultimodalMessage.user("hi")))
                .build();
    }
}