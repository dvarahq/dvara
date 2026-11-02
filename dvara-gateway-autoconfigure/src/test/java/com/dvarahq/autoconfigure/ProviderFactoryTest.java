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

import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.ProviderRegistry;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.OpenAiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

/** A provider built while the gateway runs is the same adapter a startup bean would be. */
class ProviderFactoryTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ProviderAutoConfiguration.class))
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(SecretProvider.class, () -> mock(SecretProvider.class));

    @Test
    void everyTypeIsBuilt_underItsOwnName() {
        runner.withPropertyValues("dvara.llm-gateway.providers.vertex-ai.project=my-project").run(ctx -> {
            ProviderFactory factory = ctx.getBean(ProviderFactory.class);
            for (String type : ProviderFactory.TYPES) {
                String url = type.equals("azure-openai") ? "https://example.openai.azure.com" : null;
                LlmProvider provider = factory.create(new ProviderFactory.Spec(type, url, null, null));
                assertThat(provider.name()).as(type).isEqualTo(type);
            }
        });
    }

    @Test
    void azureWithoutABaseUrl_andAnUnknownType_areRefused() {
        runner.run(ctx -> {
            ProviderFactory factory = ctx.getBean(ProviderFactory.class);
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> factory.create(new ProviderFactory.Spec("azure-openai", " ", null, null)))
                    .withMessageContaining("base URL");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> factory.create(new ProviderFactory.Spec("acme", null, null, null)))
                    .withMessageContaining("Unknown provider type 'acme'");
        });
    }

    /** Vertex AI reads the spec's region as its location and its project, each falling back to configuration. */
    @Test
    void vertexAiTakesItsLocationFromTheSpec_andNeedsAProject() {
        runner.withPropertyValues("dvara.llm-gateway.providers.vertex-ai.project=my-project",
                "dvara.llm-gateway.providers.vertex-ai.location=us-central1").run(ctx -> {
            ProviderFactory factory = ctx.getBean(ProviderFactory.class);
            var inEurope = (com.dvarahq.providers.vertexai.VertexAiProvider) factory.create(
                    new ProviderFactory.Spec("vertex-ai", null, "europe-west4", null));
            assertThat(inEurope.project()).isEqualTo("my-project");
            assertThat(inEurope.location()).isEqualTo("europe-west4");
            var configured = (com.dvarahq.providers.vertexai.VertexAiProvider) factory.create(
                    new ProviderFactory.Spec("vertex-ai", null, null, null));
            assertThat(configured.location()).isEqualTo("us-central1");
            var ownProject = (com.dvarahq.providers.vertexai.VertexAiProvider) factory.create(
                    new ProviderFactory.Spec("vertex-ai", null, "global", null, "other-project"));
            assertThat(ownProject.project()).isEqualTo("other-project");
        });
        runner.run(ctx -> assertThat(((com.dvarahq.providers.vertexai.VertexAiProvider) ctx.getBean(ProviderFactory.class)
                .create(new ProviderFactory.Spec("vertex-ai", null, null, null, "spec-project"))).project())
                .as("a project in the spec needs none configured").isEqualTo("spec-project"));
        runner.run(ctx -> assertThatIllegalArgumentException()
                .isThrownBy(() -> ctx.getBean(ProviderFactory.class).create(new ProviderFactory.Spec("vertex-ai", null, null, null)))
                .withMessageContaining("needs a Google Cloud project"));
    }

    @Test
    void theDefaultRegistryHoldsTheProvidersRegisteredAtStartup() {
        runner.withPropertyValues("dvara.llm-gateway.providers.openai.enabled=true")
                .run(ctx -> assertThat(ctx.getBean(ProviderRegistry.class).current())
                        .singleElement().isInstanceOf(OpenAiProvider.class));
        runner.run(ctx -> assertThat(ctx.getBean(ProviderRegistry.class).current()).isEmpty());
    }

    @Test
    void aRegistryOfAnotherModuleReplacesTheDefault() {
        ProviderRegistry own = java.util.List::of;
        runner.withBean(ProviderRegistry.class, () -> own)
                .run(ctx -> assertThat(ctx.getBean(ProviderRegistry.class)).isSameAs(own));
    }
}
