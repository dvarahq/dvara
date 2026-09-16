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
package com.dvarahq.autoconfigure.tls;

import org.springframework.web.client.RestClient;

/**
 * Customizes provider {@link RestClient.Builder} instances with TLS configuration, for example mTLS
 * client certificates or TLS version enforcement per provider.
 *
 * <p>There is no default and no no-op bean. {@code ProviderAutoConfiguration} takes this seam
 * through an {@code ObjectProvider} and uses each builder as it arrives when nothing supplies one.
 * This build does not include an implementation; another module or the application may register
 * one.</p>
 *
 * <p>Placed in {@code dvara-gateway-autoconfigure} rather than {@code dvara-gateway-core} because
 * core has no Spring dependencies and {@code RestClient.Builder} is from Spring Web.</p>
 */
@FunctionalInterface
public interface ProviderTlsCustomizer {

    /**
     * Apply TLS customizations to the given builder for the specified provider.
     *
     * @param providerName the provider name (e.g. "openai", "anthropic")
     * @param builder      the RestClient builder to customize
     * @return the customized builder (may be the same instance or a new one)
     */
    RestClient.Builder customize(String providerName, RestClient.Builder builder);
}