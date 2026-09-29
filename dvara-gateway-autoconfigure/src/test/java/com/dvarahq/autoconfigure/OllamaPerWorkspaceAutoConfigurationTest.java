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
}
