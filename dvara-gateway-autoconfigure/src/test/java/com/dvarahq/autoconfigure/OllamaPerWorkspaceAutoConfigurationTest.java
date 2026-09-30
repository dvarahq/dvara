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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.providers.ollama.OllamaEndpointResolver;
import com.dvarahq.providers.ollama.OllamaProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #30 A6/A7: the per-workspace switch registers Ollama on its own, and never with a platform fallback. */
class OllamaPerWorkspaceAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProviderAutoConfiguration.class))
            .withBean(RestClient.Builder.class, RestClient::builder);

    @Test
    void perWorkspaceAloneRegistersOllama_andAWorkspaceWithoutAnEndpointIsRefused() {
        runner.withPropertyValues("dvara.llm-gateway.providers.ollama.per-workspace=true")
                .withBean(OllamaEndpointResolver.class, () -> ws -> Optional.empty())
                .run(ctx -> {
                    OllamaProvider ollama = ctx.getBean(OllamaProvider.class);
                    ChatRequest request = ChatRequest.builder().model("ollama/llama3.1")
                            .messages(List.of(MultimodalMessage.user("hi"))).build();
                    // The platform-wide base-url (localhost:11434) is never tried: refused before any call.
                    com.dvarahq.providers.support.WorkspaceScope.runWith("acme", () ->
                            assertThatThrownBy(() -> ollama.chat(request)).isInstanceOf(GatewayException.class)
                                    .hasMessageContaining("No Ollama endpoint is registered for this workspace"));
                });
    }

    @Test
    void withNeitherSwitchThereIsNoOllama() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(OllamaProvider.class));
    }

    /** The image fetcher reaches Ollama too: with fetching on, a URL image is fetched, not refused as unsupported. */
    @Test
    void imageFetchingOn_reachesOllama() {
        ChatRequest request = ChatRequest.builder().model("ollama/qwen3.5:4b")
                .messages(List.of(MultimodalMessage.builder().role("user")
                        .content(List.of(new com.dvarahq.core.model.ContentBlock.ImageBlock(
                                com.dvarahq.core.model.ContentBlock.ImageBlock.URL_MEDIA_TYPE,
                                "https://127.0.0.1/cat.png")))
                        .build()))
                .build();
        runner.withPropertyValues("dvara.llm-gateway.providers.ollama.enabled=true")
                .run(ctx -> assertThatThrownBy(() -> ctx.getBean(OllamaProvider.class).chat(request))
                        .isInstanceOf(GatewayException.class)
                        .extracting("code").isEqualTo("UNSUPPORTED_CAPABILITY"));
        // On, the fetcher's own address check refuses the loopback URL before any call: it was consulted.
        runner.withPropertyValues("dvara.llm-gateway.providers.ollama.enabled=true",
                        "dvara.llm-gateway.image-fetch.enabled=true")
                .run(ctx -> assertThatThrownBy(() -> ctx.getBean(OllamaProvider.class).chat(request))
                        .isInstanceOf(GatewayException.class)
                        .hasMessageContaining("Image URL refused"));
    }

    /** Structured outputs are on by default and an operator on an older Ollama turns them off. */
    @Test
    void structuredOutputs_onByDefault_offWithTheSetting() {
        runner.withPropertyValues("dvara.llm-gateway.providers.ollama.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(OllamaProvider.class).capabilities().supportsStructuredOutputs())
                        .isTrue());
        runner.withPropertyValues("dvara.llm-gateway.providers.ollama.enabled=true",
                        "dvara.llm-gateway.providers.ollama.structured-outputs=false")
                .run(ctx -> assertThat(ctx.getBean(OllamaProvider.class).capabilities().supportsStructuredOutputs())
                        .isFalse());
    }
}
