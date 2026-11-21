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
package com.dvarahq.providers.deepseek;

import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.AbstractOpenAiCompatibleProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * DeepSeek provider, talking to the OpenAI-compatible API at
 * {@code https://api.deepseek.com/v1}.
 *
 * <p>Activated when {@code dvara.llm-gateway.providers.deepseek.api-key} is set
 * (env: {@code DEEPSEEK_API_KEY}). All chat / streaming / multimodal handling
 * inherits from {@link AbstractOpenAiCompatibleProvider}; this class only
 * carries DeepSeek-specific identity.
 *
 * <p>Capabilities: streaming, tools and JSON mode across the {@code deepseek-chat} family.
 * Structured outputs ({@code json_schema}) and vision are not declared at the provider level
 * because the reasoning models ({@code deepseek-reasoner}) enforce JSON Schema differently from
 * the chat models; route {@code json_schema} requests to a provider that supports it.
 */
public class DeepSeekProvider extends AbstractOpenAiCompatibleProvider {

    public DeepSeekProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("deepseek", builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.deepseek.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build());
    }

    public DeepSeekProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    DeepSeekProvider(RestClient restClient) {
        super("deepseek", restClient);
    }

    @Override
    protected String upstreamLabel() {
        return "DeepSeek";
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
                /* max context */ 64_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("deepseek");
    }

    @Override
    public List<ModelInfo> listModels() {
        return listOaiCompatModels("deepseek");
    }
}