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
package com.dvarahq.providers.groq;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.AbstractOpenAiCompatibleProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Groq, on its OpenAI-compatible Chat Completions endpoint ({@code /openai/v1/chat/completions}).
 *
 * <p>Groq speaks OpenAI's wire format, so it runs on the shared adapter: function tools and
 * {@code tool_choice} go out in OpenAI's shape, the model's {@code tool_calls} come back on the message,
 * streamed tool-call fragments are assembled by the shared stream decoder, and an image goes out as an
 * {@code image_url} part (a URL as sent, base64 as a {@code data:} URL). Groq takes images only on its
 * vision models; another model refuses one with a 400, which reaches the caller as Groq's own error.
 *
 * <p>Two things differ from OpenAI. The routing key carries a {@code groq/} prefix that Groq does not
 * know, so it is removed from the body. And {@code json_schema} is refused here: Groq offers structured
 * outputs on some models only, and this provider does not declare them, so the dispatcher keeps such a
 * request away; the refusal is the last line if one arrives anyway.
 */
public class GroqProvider extends AbstractOpenAiCompatibleProvider {

    public GroqProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("groq", builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.groq.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build());
    }

    public GroqProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    GroqProvider(RestClient restClient) {
        super("groq", restClient);
    }

    @Override
    protected String upstreamLabel() {
        return "Groq";
    }

    @Override
    protected Map<String, Object> buildChatBody(ChatRequest request) {
        if (request.getResponseFormat() instanceof ResponseFormat.JsonSchema) {
            throw new GatewayException("UNSUPPORTED_RESPONSE_FORMAT",
                    "Groq provider does not support json_schema response format. Supported formats: [text, json_object]");
        }
        Map<String, Object> body = super.buildChatBody(request);
        body.put("model", stripPrefix(request.getModel()));
        return body;
    }

    /** Groq takes {@code parallel_tool_calls}, so a caller's {@code false} is sent on. */
    @Override
    public boolean canDisableParallelToolCalls() {
        return true;
    }

    /** Strip the "groq/" prefix that the routing key uses. */
    private static String stripPrefix(String model) {
        return model != null && model.startsWith("groq/") ? model.substring(5) : model;
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                /* streaming */ true,
                /* vision */ true,
                /* tool calls */ true,
                /* structured outputs */ false,
                /* json mode */ true,
                /* batch */ false,
                /* streamed tool calls */ true,
                /* max context */ 131_072);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("groq/");
    }

    @Override
    public List<ModelInfo> listModels() {
        // The base URL already ends in /openai/v1, so the model list is /models from it. Ids carry the
        // routing prefix, so a listed model routes back here.
        OaiCompatModelList response = restClient.get()
                .uri("/models")
                .retrieve()
                .body(OaiCompatModelList.class);
        if (response == null || response.data == null) return List.of();
        return response.data.stream()
                .map(m -> new ModelInfo("groq/" + m.id, m.ownedBy != null ? m.ownedBy : "groq", m.created))
                .toList();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OaiCompatModelList {
        private List<OaiCompatModel> data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OaiCompatModel {
        private String id;
        @JsonProperty("owned_by") private String ownedBy;
        private long created;
    }
}
