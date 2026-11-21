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
package com.dvarahq.providers.grok;

import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.AbstractOpenAiCompatibleProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * xAI Grok provider, talking to the OpenAI-compatible API at
 * {@code https://api.x.ai/v1}.
 *
 * <p>Activated when {@code dvara.llm-gateway.providers.grok.api-key} is set
 * (env: {@code XAI_API_KEY}). All chat / streaming / multimodal handling
 * inherits from {@link AbstractOpenAiCompatibleProvider}; this class only
 * carries Grok-specific identity.
 *
 * <p>Capabilities: streaming, tools, structured outputs and JSON mode across the {@code grok-2-*}
 * and {@code grok-3-*} families. Vision is declared at the provider level because
 * {@code grok-2-vision-1212} accepts images, although most Grok models do not; sending an image
 * to a non-vision Grok model gets a {@code PROVIDER_ERROR} from the upstream.
 */
public class GrokProvider extends AbstractOpenAiCompatibleProvider {

    public GrokProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("grok", builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.grok.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build());
    }

    public GrokProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    GrokProvider(RestClient restClient) {
        super("grok", restClient);
    }

    @Override
    protected String upstreamLabel() {
        return "Grok";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                /* streaming */ true,
                /* vision */ true,
                /* tool calls */ true,
                /* structured outputs */ true,
                /* json mode */ true,
                /* batch */ false,
                /* streamed tool calls */ true,
                /* max context */ 131_072);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("grok");
    }

    @Override
    public List<ModelInfo> listModels() {
        return listOaiCompatModels("xai");
    }
}