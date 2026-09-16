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
package com.dvarahq.providers.openai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI provider — the canonical implementation of the OpenAI wire format.
 *
 * <p>Chat, streaming, body building, response mapping, and SSE iteration all
 * inherit from {@link AbstractOpenAiCompatibleProvider}. This class only
 * carries OpenAI-specific behaviour: embeddings (under the
 * {@code text-embedding} prefix), the {@code GET /models} catalogue endpoint,
 * the chat-family prefix list ({@code gpt}, {@code o1}, {@code o3}, {@code o4},
 * {@code chatgpt}), and the capability declaration.
 *
 * <p>Auth: per-request {@code Authorization: Bearer <key>} via
 * {@link CredentialInterceptor}. Credentials resolve through the standard
 * workspace → platform → vault → env chain at request time.
 */
public class OpenAiProvider extends AbstractOpenAiCompatibleProvider {

    public OpenAiProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("openai", builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.openai.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build());
    }

    public OpenAiProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    OpenAiProvider(RestClient restClient) {
        super("openai", restClient);
    }

    @Override
    protected String upstreamLabel() {
        return "OpenAI";
    }

    // -------------------------------------------------------------------------
    // Embeddings — OpenAI-only in the current provider set
    // -------------------------------------------------------------------------

    @Override
    public boolean supportsEmbedding(String model) {
        return model != null && model.startsWith("text-embedding");
    }

    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("input", request.getInput());
        if (request.getUser() != null) body.put("user", request.getUser());
        if (request.getDimensions() != null) body.put("dimensions", request.getDimensions());

        OaiEmbeddingResponse oai = restClient.post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "OpenAI embedding error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(OaiEmbeddingResponse.class);

        List<EmbeddingResponse.EmbeddingData> data = oai.getData().stream()
                .map(d -> EmbeddingResponse.EmbeddingData.builder()
                        .object("embedding")
                        .index(d.getIndex())
                        .embedding(d.getEmbedding())
                        .build())
                .toList();

        return EmbeddingResponse.builder()
                .object("list")
                .model(oai.getModel())
                .data(data)
                .usage(EmbeddingResponse.Usage.builder()
                        .promptTokens(oai.getUsage() != null ? oai.getUsage().getPromptTokens() : 0)
                        .totalTokens(oai.getUsage()  != null ? oai.getUsage().getTotalTokens()  : 0)
                        .build())
                .build();
    }

    // -------------------------------------------------------------------------
    // Capabilities / routing
    // -------------------------------------------------------------------------

    @Override
    public ProviderCapabilities capabilities() {
        // Full form: supportsBatch=true — OpenAI exposes /files + /batches — and streamed tool calls.
        return new ProviderCapabilities(true, true, true, true, true, true, true, 128_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        // OpenAI's chat catalogue spans classic GPT models, the o-series reasoning models and the
        // chatgpt-* alias family; each prefix is listed so the default prefix matcher routes them
        // all here. Specific o1 / o3 / o4 rather than a bare "o", so another provider's models are
        // not swallowed.
        return List.of(
                "gpt",        // gpt-4o, gpt-4.1, gpt-3.5-turbo, ...
                "o1",         // o1-preview, o1-mini, o1-2024-12-17, ...
                "o3",         // o3-mini, o3, ...
                "o4",         // o4-mini, ...
                "chatgpt"     // chatgpt-4o-latest
        );
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        OaiModelList response = restClient.get()
                .uri("/models")
                .retrieve()
                .body(OaiModelList.class);
        if (response == null || response.data == null) return List.of();
        return response.data.stream()
                .map(m -> new com.dvarahq.core.provider.ModelInfo(
                        m.id, m.ownedBy != null ? m.ownedBy : "openai", m.created))
                .toList();
    }

    // -------------------------------------------------------------------------
    // OpenAI-specific DTOs (embeddings + model catalogue)
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OaiEmbeddingResponse {
        private String object;
        private String model;
        private List<OaiEmbeddingData> data;
        private OaiEmbeddingUsage usage;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OaiEmbeddingData {
            private String object;
            private int index;
            private List<Double> embedding;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OaiEmbeddingUsage {
            @JsonProperty("prompt_tokens") private int promptTokens;
            @JsonProperty("total_tokens")  private int totalTokens;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OaiModelList {
        private List<OaiModel> data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OaiModel {
        private String id;
        @JsonProperty("owned_by") private String ownedBy;
        private long created;
    }
}