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
package com.dvarahq.providers.chatglm;

import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.AbstractOpenAiCompatibleProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * Zhipu AI / ChatGLM provider, talking to the OpenAI-compatible API at
 * {@code https://open.bigmodel.cn/api/paas/v4}.
 *
 * <p>Activated when {@code dvara.llm-gateway.providers.chatglm.api-key} is set
 * (env: {@code ZHIPU_API_KEY}). All chat / streaming / multimodal handling
 * inherits from {@link AbstractOpenAiCompatibleProvider}; this class only
 * carries ChatGLM-specific identity.
 *
 * <p>Capabilities: streaming, tools and JSON mode across the {@code glm-4*} family. Vision is
 * model-specific ({@code glm-4v-*} only) and not declared at the provider level, since a vision
 * request to a non-vision GLM model would error upstream; route vision to a vision-capable
 * provider.
 */
public class ChatGlmProvider extends AbstractOpenAiCompatibleProvider {

    public ChatGlmProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("chatglm", builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.chatglm.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build());
    }

    public ChatGlmProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    ChatGlmProvider(RestClient restClient) {
        super("chatglm", restClient);
    }

    @Override
    protected String upstreamLabel() {
        return "ChatGLM";
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
                /* max context */ 128_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("glm");
    }

    @Override
    public List<ModelInfo> listModels() {
        return listOaiCompatModels("zhipu");
    }
}