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

import com.dvarahq.autoconfigure.GatewayEncryptionProperties;
import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.autoconfigure.ProviderAutoConfiguration;
import com.dvarahq.core.secret.SecretProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for {@link SecretProvider}.
 * <p>
 * Registers the default ({@link PropertySecretProvider}). Another module or the application can
 * override it by registering its own {@code SecretProvider} bean, for example one backed by a
 * secrets manager.
 * <p>
 * Loaded before {@link ProviderAutoConfiguration} so that provider beans can inject the secret
 * provider at construction time.
 */
@AutoConfiguration(before = ProviderAutoConfiguration.class)
@EnableConfigurationProperties({GatewayProperties.class, GatewayEncryptionProperties.class})
public class SecretAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SecretProvider.class)
    public SecretProvider propertySecretProvider(GatewayProperties properties,
                                                  GatewayEncryptionProperties encryptionProperties,
            org.springframework.beans.factory.ObjectProvider<com.dvarahq.core.secret.WorkspaceCredentialSource> workspaceCredentials) {
        return new PropertySecretProvider(properties, encryptionProperties,
                workspaceCredentials.getIfAvailable());
    }
}