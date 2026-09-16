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
package com.dvarahq.providers.moonshot;

import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.AbstractOpenAiCompatibleProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Moonshot AI (Kimi) provider, talking to the OpenAI-compatible API at
 * {@code https://api.moonshot.cn/v1}.
 *
 * <p>Activated when {@code dvara.llm-gateway.providers.moonshot.api-key} is set
 * (env: {@code MOONSHOT_API_KEY}). All chat / streaming / multimodal handling
 * inherits from {@link AbstractOpenAiCompatibleProvider}; this class only
 * carries Moonshot-specific identity.
 *
 * <p>The provider-level {@code maxContextTokens} is 200K, above the 128K window of
 * {@code moonshot-v1-128k}, so a request between the two can pass the gateway's context check
 * and be refused by the model.
 *
 * <p>Capabilities: streaming, tools and JSON mode across the {@code moonshot-v1-*} family.
 * Vision and strict structured outputs are not declared.
 */
public class MoonshotProvider extends AbstractOpenAiCompatibleProvider {

    public MoonshotProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("moonshot", builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.moonshot.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build());
    }

    public MoonshotProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    MoonshotProvider(RestClient restClient) {
        super("moonshot", restClient);
    }

    @Override
    protected String upstreamLabel() {
        return "Moonshot";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                /* streaming */ true,
                /* vision */ false,
                /* tool calls */ true,
                /* structured outputs */ false,
                /* json mode */ true,
                /* batch */ false,
                /* streamed tool calls */ true,
                /* max context */ 200_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("moonshot");
    }

    @Override
    public List<ModelInfo> listModels() {
        return listOaiCompatModels("moonshot");
    }
}