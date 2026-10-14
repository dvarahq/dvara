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
package com.dvarahq.autoconfigure;

import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.anthropic.AnthropicProvider;
import com.dvarahq.providers.azureopenai.AzureOpenAiProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * A key-based provider is switched on by {@code enabled}, so its key can live only in a secret store. An install that
 * sets only the key keeps working, and a provider with neither is not registered.
 */
class ProviderEnabledSwitchTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProviderAutoConfiguration.class))
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(SecretProvider.class, () -> mock(SecretProvider.class));

    @Test
    void enabledWithNoKeyRegistersTheProvider() {
        runner.withPropertyValues("dvara.llm-gateway.providers.openai.enabled=true",
                        "dvara.llm-gateway.providers.anthropic.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(OpenAiProvider.class);
                    assertThat(ctx).hasSingleBean(AnthropicProvider.class);
                });
    }

    @Test
    void aKeyAloneStillRegistersTheProvider() {
        runner.withPropertyValues("dvara.llm-gateway.providers.openai.api-key=sk-test")
                .run(ctx -> assertThat(ctx).hasSingleBean(OpenAiProvider.class));
    }

    @Test
    void neitherKeyNorSwitchRegistersNothing() {
        runner.withPropertyValues("dvara.llm-gateway.providers.openai.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(OpenAiProvider.class);
                    assertThat(ctx).doesNotHaveBean(AnthropicProvider.class);
                });
    }

    @Test
    void azureStillNeedsItsBaseUrl() {
        runner.withPropertyValues("dvara.llm-gateway.providers.azure-openai.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AzureOpenAiProvider.class));
        runner.withPropertyValues("dvara.llm-gateway.providers.azure-openai.enabled=true",
                        "dvara.llm-gateway.providers.azure-openai.base-url=https://example.openai.azure.com")
                .run(ctx -> assertThat(ctx).hasSingleBean(AzureOpenAiProvider.class));
    }
}
