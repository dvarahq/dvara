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
package com.dvarahq.providers.qwen;

import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.AbstractOpenAiCompatibleProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Alibaba Qwen / DashScope provider, talking to the OpenAI-compatible mode at
 * {@code https://dashscope.aliyuncs.com/compatible-mode/v1}.
 *
 * <p>Activated when {@code dvara.llm-gateway.providers.qwen.api-key} is set
 * (env: {@code QWEN_API_KEY}). All chat / streaming / multimodal handling
 * inherits from {@link AbstractOpenAiCompatibleProvider}; this class only
 * carries Qwen-specific identity (name, prefix, capabilities, error label).
 *
 * <p>Capabilities are conservative: streaming only. Qwen's catalogue spans text, vision
 * (Qwen-VL family) and reasoning models with different feature shapes, and over-declaring at the
 * provider level would route calls to models that can't handle them; the capability-aware router
 * falls through to a capable provider instead.
 */
public class QwenProvider extends AbstractOpenAiCompatibleProvider {

    public QwenProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("qwen", builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.qwen.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build());
    }

    public QwenProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    QwenProvider(RestClient restClient) {
        super("qwen", restClient);
    }

    @Override
    protected String upstreamLabel() {
        return "Qwen";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                /* streaming */ true,
                /* vision */ false,
                /* tool calls */ false,
                /* structured outputs */ false,
                /* json mode */ false,
                /* max context */ 32_768);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("qwen");
    }

    @Override
    public List<ModelInfo> listModels() {
        // Qwen's compatible-mode endpoint exposes /models in OpenAI shape, but catalogues differ
        // across deployments; listOaiCompatModels() returns an empty list on a missing or
        // non-standard response.
        return listOaiCompatModels("qwen");
    }
}