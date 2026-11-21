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
package com.dvarahq.providers.vertexai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.provider.ModelInfo;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.gemini.GeminiProvider;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.providers.support.ProviderErrors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.dvarahq.providers.support.CredentialInterceptor.recordFingerprint;
import static com.dvarahq.providers.support.CredentialInterceptor.resolveWorkspaceId;

/**
 * Gemini on Google Cloud's Vertex AI. The models and the {@code generateContent} API are Gemini's, so the request
 * and response translation is {@link GeminiProvider}'s; what differs is where a call goes and how it is
 * authorised.
 *
 * <ul>
 *   <li><b>Where.</b> A project and a location: {@code https://{location}-aiplatform.googleapis.com} for a region
 *       such as {@code europe-west4}, {@code https://aiplatform.googleapis.com} for {@code global}. The data stays in
 *       the region named, which is why a deployment with residency rules names one.</li>
 *   <li><b>How.</b> An OAuth2 access token from a Google credential, not an API key (see
 *       {@link VertexAiCredentials} for which one, and {@link GoogleAccessTokens} for how the token is minted and
 *       kept).</li>
 *   <li><b>Models.</b> Asked for as {@code vertex/<model>}, for example {@code vertex/gemini-2.5-flash}, so they
 *       never collide with the Gemini API provider's {@code gemini-*} names. A route that names this provider may
 *       send the bare name too. Embedding models ({@code vertex/text-embedding-005},
 *       {@code vertex/gemini-embedding-001}) serve {@code /v1/embeddings}.</li>
 * </ul>
 */
public class VertexAiProvider extends GeminiProvider {

    public static final String NAME = "vertex-ai";
    public static final String MODEL_PREFIX = "vertex/";
    public static final String DEFAULT_LOCATION = "global";

    /** A Google Cloud location: lower-case letters, digits and single dashes, so it is safe in a host name. */
    private static final Pattern LOCATION = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /**
     * Where and as whom the provider calls Vertex AI.
     *
     * @param project         the Google Cloud project id or number; required
     * @param location        a region such as {@code us-central1}, or {@code global}; blank means {@code global}
     * @param baseUrl         an endpoint to use in place of the location's own, such as a Private Service Connect
     *                        address; blank means the location's
     * @param credentialsFile a Google credential file; blank means the secret, then application default credentials
     * @param tokenUrl        Google's token endpoint; blank means {@link GoogleAccessTokens#DEFAULT_TOKEN_URL}
     */
    public record Settings(String project, String location, String baseUrl, String credentialsFile, String tokenUrl) {
    }

    private final String project;
    private final String location;
    private final RestClient restClient;
    private final GoogleAccessTokens tokens;
    private final VertexAiCredentials credentials;

    /**
     * @param builder the upstream client's builder; it is cloned, once for Vertex AI and once for the token
     *                endpoint, so its own base URL, if any, is not used
     */
    public VertexAiProvider(SecretProvider secretProvider, Settings settings, RestClient.Builder builder) {
        this(settings, builder.clone().baseUrl(baseUrl(settings)).build(),
                new GoogleAccessTokens(builder.clone().build(), settings.tokenUrl()),
                new VertexAiCredentials(secretProvider, settings.credentialsFile()));
    }

    VertexAiProvider(Settings settings, RestClient restClient, GoogleAccessTokens tokens,
                     VertexAiCredentials credentials) {
        super(NAME, null, restClient);
        if (settings.project() == null || settings.project().isBlank()) {
            throw new IllegalArgumentException("Vertex AI needs a Google Cloud project");
        }
        this.project = settings.project().trim();
        this.location = location(settings.location());
        this.restClient = restClient;
        this.tokens = tokens;
        this.credentials = credentials;
    }

    /** The endpoint for the settings: the base URL when one is given, else the location's own. */
    public static String baseUrl(Settings settings) {
        if (settings.baseUrl() != null && !settings.baseUrl().isBlank()) {
            return settings.baseUrl().trim();
        }
        String location = location(settings.location());
        return DEFAULT_LOCATION.equals(location)
                ? "https://aiplatform.googleapis.com"
                : "https://" + location + "-aiplatform.googleapis.com";
    }

    private static String location(String configured) {
        String location = configured == null || configured.isBlank() ? DEFAULT_LOCATION : configured.trim();
        if (!LOCATION.matcher(location).matches()) {
            throw new IllegalArgumentException("Vertex AI location '" + location + "' is not a Google Cloud location "
                    + "such as us-central1, europe-west4 or global");
        }
        return location;
    }

    public String project() {
        return project;
    }

    public String location() {
        return location;
    }

    // -------------------------------------------------------------------------
    // Where and how a call goes
    // -------------------------------------------------------------------------

    @Override
    protected String modelPath(String method) {
        return "/v1/projects/{project}/locations/{location}/publishers/google/models/{model}:" + method;
    }

    @Override
    protected Object[] modelPathVariables(String model) {
        return new Object[] {project, location, upstreamModel(model)};
    }

    @Override
    protected void authorize(HttpHeaders headers) {
        VertexAiCredentials.Resolved credential = credentials.resolve(resolveWorkspaceId());
        GoogleAccessTokens.AccessToken token = tokens.token(credential.cacheKey(), credential.credential());
        // The fingerprint names the credential, not the short-lived token, so calls made with one key group together.
        recordFingerprint(credential.cacheKey());
        headers.setBearerAuth(token.value());
    }

    @Override
    protected String label() {
        return "Vertex AI";
    }

    /** Vertex AI's {@code countTokens} takes the request's fields directly, and has no {@code toolConfig}. */
    @Override
    protected ObjectNode countTokensBody(ObjectNode generate, String model) {
        generate.remove("toolConfig");
        return generate;
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of(MODEL_PREFIX);
    }

    /** Vertex AI lists no Gemini models per project; the catalogue and the routes name them. */
    @Override
    public List<ModelInfo> listModels() {
        return List.of();
    }

    /** The model's name at Vertex AI: the caller's name without {@code vertex/}. */
    static String upstreamModel(String model) {
        return model != null && model.startsWith(MODEL_PREFIX) ? model.substring(MODEL_PREFIX.length()) : model;
    }

    // -------------------------------------------------------------------------
    // Embeddings
    // -------------------------------------------------------------------------

    @Override
    public boolean supportsEmbedding(String model) {
        return model != null && model.startsWith(MODEL_PREFIX) && upstreamModel(model).contains("embedding");
    }

    /**
     * Vertex AI's {@code predict} for a text embedding model: each input an instance, {@code dimensions} as
     * {@code outputDimensionality}. Gemini embedding models take one input per call, so each input is its own
     * call; the older text embedding models take a list in one call.
     */
    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        List<String> inputs = texts(request.getInput());
        String model = upstreamModel(request.getModel());
        List<List<String>> calls = new ArrayList<>();
        if (model.startsWith("gemini-embedding")) {
            inputs.forEach(text -> calls.add(List.of(text)));
        } else {
            calls.add(inputs);
        }
        List<EmbeddingResponse.EmbeddingData> data = new ArrayList<>();
        int tokens = 0;
        for (List<String> batch : calls) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("instances", batch.stream().map(text -> Map.of("content", text)).toList());
            if (request.getDimensions() != null) {
                body.put("parameters", Map.of("outputDimensionality", request.getDimensions()));
            }
            String text = restClient.post()
                    .uri(modelPath("predict"), modelPathVariables(request.getModel()))
                    .headers(this::authorize)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(status -> status.isError(), (req, res) -> {
                        throw ProviderErrors.refusal(label(), res, "Vertex AI embedding error", " for model " + model);
                    })
                    .body(String.class);
            JsonNode predictions = predictions(text);
            if (predictions == null || !predictions.isArray() || predictions.size() != batch.size()) {
                throw new GatewayException("PROVIDER_ERROR",
                        "Vertex AI returned " + (predictions == null ? "no" : predictions.size())
                                + " embeddings for " + batch.size() + " inputs");
            }
            for (JsonNode prediction : predictions) {
                JsonNode embeddings = prediction.path("embeddings");
                List<Double> vector = new ArrayList<>();
                embeddings.path("values").forEach(v -> vector.add(v.asDouble()));
                tokens += embeddings.path("statistics").path("token_count").asInt(0);
                data.add(EmbeddingResponse.EmbeddingData.builder()
                        .object("embedding")
                        .index(data.size())
                        .embedding(vector)
                        .build());
            }
        }
        return EmbeddingResponse.builder()
                .object("list")
                .model(request.getModel())
                .data(data)
                .usage(EmbeddingResponse.Usage.builder().promptTokens(tokens).totalTokens(tokens).build())
                .build();
    }

    /** The {@code predictions} of a {@code predict} answer, read with the gateway's own JSON mapper. */
    private static JsonNode predictions(String text) {
        try {
            JsonNode answer = text == null ? null : JsonMapper.instance().readTree(text);
            return answer == null ? null : answer.get("predictions");
        } catch (java.io.IOException e) {
            throw new GatewayException("PROVIDER_ERROR", "Vertex AI returned an embedding answer that is not JSON");
        }
    }

    /** The texts to embed: a string, or a list of strings. Vertex AI's text models take no token arrays. */
    private static List<String> texts(Object input) {
        if (input instanceof String text) {
            return List.of(text);
        }
        if (input instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(String.class::isInstance)) {
            return list.stream().map(String.class::cast).toList();
        }
        throw new GatewayException("INVALID_REQUEST",
                "Vertex AI embeddings take text: a string or a non-empty list of strings");
    }
}
