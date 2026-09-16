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
package com.dvarahq.providers.azureopenai;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.openai.AbstractOpenAiCompatibleProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.function.Function;

/**
 * Azure OpenAI provider. Same wire format as OpenAI but two distinct shape
 * differences:
 *
 * <ul>
 *   <li>The chat endpoint embeds the deployment name and API version in the
 *       URL: {@code /deployments/{deployment}/chat/completions?api-version=...}.
 *       The {@code model} field is the deployment name and lives in the URL,
 *       not in the body.</li>
 *   <li>Auth uses a custom {@code api-key} header instead of
 *       {@code Authorization: Bearer}.</li>
 * </ul>
 *
 * <p>Both differences are encoded as overrides on
 * {@link AbstractOpenAiCompatibleProvider}: the URL via {@link #chatUri()} +
 * {@link #chatUriVars(ChatRequest)}, the model-in-body suppression via
 * {@link #includeModelInBody()}, and the auth header via the
 * {@link CredentialInterceptor} configured on the {@link RestClient}.
 *
 * <p>Embeddings are not supported. {@code listModels} calls Azure's catalogue endpoint with the
 * API version and prefixes each ID with {@code azure/} so it matches the routing prefix.
 */
public class AzureOpenAiProvider extends AbstractOpenAiCompatibleProvider {

    private static final String API_VERSION = "2024-10-21";
    private static final String MODEL_PREFIX = "azure/";

    public AzureOpenAiProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("azure-openai", builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.azure-openai.api-key",
                        "api-key", Function.identity()))
                .build());
    }

    public AzureOpenAiProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    AzureOpenAiProvider(RestClient restClient) {
        super("azure-openai", restClient);
    }

    // -------------------------------------------------------------------------
    // Override points — Azure-shaped URI + model-in-URL behaviour
    // -------------------------------------------------------------------------

    @Override
    protected String upstreamLabel() {
        return "Azure OpenAI";
    }

    @Override
    protected String chatUri() {
        return "/deployments/{deployment}/chat/completions?api-version={apiVersion}";
    }

    @Override
    protected Object[] chatUriVars(ChatRequest request) {
        return new Object[] { extractDeployment(request.getModel()), API_VERSION };
    }

    @Override
    protected boolean includeModelInBody() {
        // Azure expects the deployment in the URL path; including a `model`
        // field in the body is rejected by the upstream as a validation error.
        return false;
    }

    // Batch API: Azure's Files + Batches endpoints are resource-scoped
    // (not deployment-scoped like chat) and carry the api-version query.

    @Override
    protected String filesUri() {
        return "/files?api-version=" + API_VERSION;
    }

    @Override
    protected String batchesUri() {
        return "/batches?api-version=" + API_VERSION;
    }

    @Override
    protected String batchUri() {
        return "/batches/{batchId}?api-version=" + API_VERSION;
    }

    @Override
    protected String fileContentUri() {
        return "/files/{fileId}/content?api-version=" + API_VERSION;
    }

    // -------------------------------------------------------------------------
    // Capabilities / routing
    // -------------------------------------------------------------------------

    @Override
    public ProviderCapabilities capabilities() {
        // Full form: supportsBatch=true — Azure OpenAI exposes /files + /batches — and streamed tool calls.
        return new ProviderCapabilities(true, true, true, true, true, true, true, 128_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of(MODEL_PREFIX);
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        OaiCompatModelList response = restClient.get()
                .uri("/models?api-version={apiVersion}", API_VERSION)
                .retrieve()
                .body(OaiCompatModelList.class);
        if (response == null || response.data == null) return List.of();
        return response.data.stream()
                .map(m -> new com.dvarahq.core.provider.ModelInfo(
                        MODEL_PREFIX + m.id, m.ownedBy != null ? m.ownedBy : "azure", m.created))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String extractDeployment(String model) {
        if (model != null && model.startsWith(MODEL_PREFIX)) {
            return model.substring(MODEL_PREFIX.length());
        }
        return model;
    }

    // -------------------------------------------------------------------------
    // Azure-specific DTOs (model catalogue only — chat / streaming DTOs are
    // shared via the abstract base)
    // -------------------------------------------------------------------------

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