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
package com.dvarahq.providers.mistral;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.routing.ModelPrefixRoutingStrategy;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.anthropic.AnthropicProvider;
import com.dvarahq.providers.azureopenai.AzureOpenAiProvider;
import com.dvarahq.providers.bedrock.BedrockProvider;
import com.dvarahq.providers.chatglm.ChatGlmProvider;
import com.dvarahq.providers.cohere.CohereProvider;
import com.dvarahq.providers.deepseek.DeepSeekProvider;
import com.dvarahq.providers.gemini.GeminiProvider;
import com.dvarahq.providers.grok.GrokProvider;
import com.dvarahq.providers.groq.GroqProvider;
import com.dvarahq.providers.moonshot.MoonshotProvider;
import com.dvarahq.providers.ollama.OllamaProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import com.dvarahq.providers.qwen.QwenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Routes Mistral's model names among every real provider at once. The default strategy takes the
 * first provider that claims a name, so two providers claiming the same name would make the
 * result depend on list order. These tests put Mistral first and last and expect the same answer.
 */
class MistralModelFamilyRoutingTest {

    private static final String[] MISTRAL_MODELS = {
            "mistral-large-latest", "open-mistral-nemo", "open-mixtral-8x22b", "codestral-latest",
            "devstral-small-latest", "magistral-medium-latest", "ministral-8b-latest",
            "pixtral-large-latest", "voxtral-small-latest"};

    private final ModelPrefixRoutingStrategy strategy = new ModelPrefixRoutingStrategy();

    private static List<LlmProvider> others() {
        // Routing reads only the model name, so no secret and no server is needed.
        SecretProvider secrets = key -> Optional.empty();
        String url = "http://localhost";
        return List.of(
                new OpenAiProvider(secrets, url), new AzureOpenAiProvider(secrets, url),
                new AnthropicProvider(secrets), new GeminiProvider(secrets, url),
                new BedrockProvider(secrets, "us-east-1"), new GrokProvider(secrets, url),
                new DeepSeekProvider(secrets, url), new MoonshotProvider(secrets, url),
                new ChatGlmProvider(secrets, url), new CohereProvider(secrets, url),
                new GroqProvider(secrets, url), new QwenProvider(secrets, url),
                new OllamaProvider(url));
    }

    private static MistralProvider mistral() {
        return new MistralProvider(RestClient.builder().baseUrl("http://localhost").build());
    }

    private static ChatRequest request(String model) {
        return ChatRequest.builder().model(model).messages(List.of()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "mistral-large-latest", "open-mistral-nemo", "open-mixtral-8x22b", "codestral-latest",
            "devstral-small-latest", "magistral-medium-latest", "ministral-8b-latest",
            "pixtral-large-latest", "voxtral-small-latest"})
    void everyMistralFamily_routesToMistral_whereverMistralSitsInTheList(String model) {
        List<LlmProvider> mistralFirst = new ArrayList<>();
        mistralFirst.add(mistral());
        mistralFirst.addAll(others());
        List<LlmProvider> mistralLast = new ArrayList<>(others());
        mistralLast.add(mistral());

        assertThat(strategy.route(request(model), mistralFirst).name()).isEqualTo("mistral");
        assertThat(strategy.route(request(model), mistralLast).name()).isEqualTo("mistral");
    }

    @Test
    void noOtherProvider_claimsAMistralFamilyName() {
        for (String model : MISTRAL_MODELS) {
            for (LlmProvider other : others()) {
                assertThat(other.supports(request(model)))
                        .as("%s claims %s", other.name(), model)
                        .isFalse();
            }
        }
    }

    // A Mistral model served by another host keeps that host's prefix and goes to that host, even
    // with Mistral first in the list.
    @Test
    void anotherHostsCopyOfAMistralModel_staysWithThatHost() {
        List<LlmProvider> providers = new ArrayList<>();
        providers.add(mistral());
        providers.addAll(others());

        assertThat(strategy.route(request("groq/mixtral-8x7b-32768"), providers).name())
                .isEqualTo("groq");
        assertThat(strategy.route(request("ollama/mistral"), providers).name())
                .isEqualTo("ollama");
        assertThat(strategy.route(request("bedrock/mistral.pixtral-large-2502-v1:0"), providers).name())
                .isEqualTo("bedrock");
    }
}
