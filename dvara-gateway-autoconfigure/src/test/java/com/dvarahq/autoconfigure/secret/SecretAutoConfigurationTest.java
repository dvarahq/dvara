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
package com.dvarahq.autoconfigure.secret;

import com.dvarahq.autoconfigure.GatewayAutoConfiguration;
import com.dvarahq.core.secret.SecretProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SecretAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    SecretAutoConfiguration.class,
                    GatewayAutoConfiguration.class))
            .withBean(com.dvarahq.core.routing.RouteRepository.class, () ->
                    org.mockito.Mockito.mock(com.dvarahq.core.routing.RouteRepository.class));

    @Test
    void propertySecretProvider_isTheDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("propertySecretProvider");
            assertThat(ctx.getBean(SecretProvider.class)).isInstanceOf(PropertySecretProvider.class);
        });
    }

    @Test
    void propertySecretProvider_resolvesConfiguredKeys() {
        runner.withPropertyValues("dvara.llm-gateway.providers.openai.api-key=sk-from-property")
                .run(ctx -> {
                    SecretProvider sp = ctx.getBean(SecretProvider.class);
                    assertThat(sp.getSecret("provider.openai.api-key")).hasValue("sk-from-property");
                    assertThat(sp.getSecret("provider.anthropic.api-key")).isEmpty();
                });
    }

    @Test
    void customSecretProvider_standsThePropertyProviderDown() {
        runner.withBean("customSecretProvider", SecretProvider.class,
                        () -> key -> java.util.Optional.of("custom-value"))
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean("propertySecretProvider");
                    assertThat(ctx.getBean(SecretProvider.class).getSecret("any-key")).hasValue("custom-value");
                });
    }
}
