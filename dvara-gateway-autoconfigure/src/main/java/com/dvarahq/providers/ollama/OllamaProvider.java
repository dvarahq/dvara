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
package com.dvarahq.providers.ollama;

import com.dvarahq.providers.support.CredentialInterceptor;
import com.dvarahq.providers.support.ImageFetcher;
import com.dvarahq.providers.support.ProviderErrors;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ReleasableUpstream;
import com.dvarahq.providers.support.StreamTransport;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.provider.AbstractLlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.dvarahq.core.util.JsonMapper;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

/**
 * Calls a locally running Ollama instance via its OpenAI-compatible endpoint
 * ({@code /v1/chat/completions}), served at {@code http://localhost:11434/v1}.
 */
public class OllamaProvider extends AbstractLlmProvider {

    private final RestClient restClient;

    public OllamaProvider(String baseUrl, RestClient.Builder builder) {
        super("ollama");
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }

    public OllamaProvider(String baseUrl) {
        this(baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    OllamaProvider(RestClient restClient) {
        super("ollama");
        this.restClient = restClient;
    }

    /** Fetches an https image URL to inline it; off unless the operator turned fetching on. */
    private ImageFetcher imageFetcher = ImageFetcher.DISABLED;

    /**
     * Lets an https image URL be fetched and sent as base64, with the fetcher's own address and size checks.
     * Ollama takes images as base64 only and refuses a URL.
     */
    public void setImageFetcher(ImageFetcher imageFetcher) {
        this.imageFetcher = imageFetcher != null ? imageFetcher : ImageFetcher.DISABLED;
    }

    /** Whether a JSON schema is sent. Ollama follows one from 0.5; an operator on an older one turns this off. */
    private boolean structuredOutputs = true;

    /** Turns structured outputs ({@code response_format} {@code json_schema}) on or off, and its capability flag with it. */
    public void setStructuredOutputs(boolean enabled) {
        this.structuredOutputs = enabled;
    }

    // -------------------------------------------------------------------------
    // Per-workspace endpoints (#30, UC-105 A6/A7)
    // -------------------------------------------------------------------------

    /** Each workspace's own Ollama, or null to call the platform-wide one as before. */
    private OllamaEndpointResolver workspaceEndpoints;
    /** The client for workspace endpoints: absolute URLs, no redirects followed. */
    private RestClient workspaceClient;

    /**
     * Sends every call to the calling workspace's own Ollama (DVARA Cloud) and never to the platform-wide
     * one (BR-105-4). A workspace with none is refused (A7). {@code resolver} may be null, which refuses
     * every call: a Cloud gateway without the resolver must not fall back to DVARA's own Ollama.
     *
     * @param client a client that follows no redirects: a tenant's endpoint passed egress validation, and
     *               a redirect to somewhere that did not must not be followed
     */
    public void usePerWorkspaceEndpoints(OllamaEndpointResolver resolver, RestClient client) {
        this.workspaceEndpoints = resolver != null ? resolver : workspaceId -> java.util.Optional.empty();
        this.workspaceClient = client;
    }

    /** Where a call goes: the platform client and a relative path, or a workspace's endpoint. */
    private record Target(RestClient client, String base, String credential) {
        RestClient.RequestBodySpec post(String path) {
            RestClient.RequestBodySpec spec = client.post().uri(base + path);
            if (credential != null) {
                spec = spec.header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + credential);
            }
            return spec;
        }
    }

    private Target target() {
        if (workspaceEndpoints == null) {
            return new Target(restClient, "", null);
        }
        String workspaceId = com.dvarahq.providers.support.CredentialInterceptor.resolveWorkspaceId();
        OllamaEndpointResolver.Endpoint endpoint = workspaceId == null ? null
                : workspaceEndpoints.resolve(workspaceId).orElse(null);
        if (endpoint == null) {
            // A7: never the platform-wide Ollama, which on DVARA Cloud is DVARA's machine, not the tenant's.
            throw new GatewayException("NO_PROVIDER", "No Ollama endpoint is registered for this workspace. "
                    + "Add one under Credentials: provider Ollama, with its HTTPS base URL and the key your "
                    + "endpoint expects.");
        }
        String base = endpoint.baseUrl().replaceAll("/+$", "");
        if (base.endsWith("/v1")) {
            base = base.substring(0, base.length() - 3);
        }
        CredentialInterceptor.recordFingerprint(endpoint.credential());
        return new Target(workspaceClient, base, endpoint.credential());
    }

    /** A workspace endpoint's redirect is refused, not followed (BR-105-5). */
    private static boolean refusedRedirect(org.springframework.http.HttpStatusCode status) {
        return status.is3xxRedirection();
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        rejectUnsupportedResponseFormat(request.getResponseFormat());
        Map<String, Object> body = buildChatBody(request);

        OllamaResponse resp = target().post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(OllamaProvider::refusedRedirect, (req, res) -> {
                    throw new GatewayException("PROVIDER_ERROR",
                            "The Ollama endpoint answered with a redirect, which is not followed");
                })
                .onStatus(status -> status.isError(), (req, res) -> {
                    ProviderErrors.logRefusal("Ollama", res);
                    // Named with the model: "does not support tools" is a property of the model (#30 A1).
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "Ollama error " + res.getStatusCode().value() + " for model "
                                + stripPrefix(request.getModel())
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(OllamaResponse.class);

        return mapToInternal(resp, request.getModel());
    }

    // -------------------------------------------------------------------------
    // Embeddings
    // -------------------------------------------------------------------------

    /**
     * Every {@code ollama/} model is offered: Ollama decides which of its models can embed, and one that
     * cannot is refused upstream with its name in the error.
     */
    @Override
    public boolean supportsEmbedding(String model) {
        return model != null && model.startsWith("ollama/");
    }

    /** Ollama's OpenAI-compatible {@code /v1/embeddings}, on the same endpoint a chat call would use. */
    @Override
    public EmbeddingResponse embed(EmbeddingRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", stripPrefix(request.getModel()));
        body.put("input", request.getInput());
        if (request.getDimensions() != null) body.put("dimensions", request.getDimensions());

        OllamaEmbeddingResponse resp = target().post("/v1/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(OllamaProvider::refusedRedirect, (req, res) -> {
                    throw new GatewayException("PROVIDER_ERROR",
                            "The Ollama endpoint answered with a redirect, which is not followed");
                })
                .onStatus(status -> status.isError(), (req, res) -> {
                    ProviderErrors.logRefusal("Ollama", res);
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "Ollama embedding error " + res.getStatusCode().value() + " for model "
                                + stripPrefix(request.getModel())
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(OllamaEmbeddingResponse.class);

        if (resp == null || resp.getData() == null) {
            throw new GatewayException("PROVIDER_ERROR", "Ollama returned an empty embedding response");
        }
        return EmbeddingResponse.builder()
                .object("list")
                .model(request.getModel())
                .data(resp.getData().stream()
                        .map(d -> EmbeddingResponse.EmbeddingData.builder()
                                .object("embedding")
                                .index(d.getIndex())
                                .embedding(d.getEmbedding())
                                .build())
                        .toList())
                .usage(EmbeddingResponse.Usage.builder()
                        .promptTokens(resp.getUsage() != null ? resp.getUsage().getPromptTokens() : 0)
                        .totalTokens(resp.getUsage() != null ? resp.getUsage().getTotalTokens() : 0)
                        .build())
                .build();
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        rejectUnsupportedResponseFormat(request.getResponseFormat());
        Map<String, Object> body = buildChatBody(request);
        body.put("stream", true);

        return target().post("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    if (refusedRedirect(res.getStatusCode())) {
                        throw new GatewayException("PROVIDER_ERROR",
                                "The Ollama endpoint answered with a redirect, which is not followed");
                    }
                    if (res.getStatusCode().isError()) {
                        throw GatewayException.upstream(res.getStatusCode().value(),
                                "Ollama streaming error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                    }
                    // The body stream is the transport handle, released through StreamTransport:
                    // ClientHttpResponse.close() drains the body first and would wait for a stalled server, and
                    // what returns a parked read differs per HTTP client; StreamTransport knows both.
                    java.io.InputStream responseBody = res.getBody();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8));
                    return new OllamaSseIterator(() -> StreamTransport.release(res, responseBody), reader, request.getModel());
                }, false);
    }

    // -------------------------------------------------------------------------
    // Shared body builder
    // -------------------------------------------------------------------------

    private void rejectUnsupportedResponseFormat(ResponseFormat format) {
        if (format instanceof ResponseFormat.JsonSchema && !structuredOutputs) {
            throw new GatewayException("UNSUPPORTED_RESPONSE_FORMAT",
                    "Ollama provider does not support response_format json_schema. Supported formats: [text, json_object]");
        }
    }

    /**
     * JSON mode and a JSON schema as OpenAI's {@code response_format}. Ollama's {@code /v1} endpoint follows
     * that field and ignores its native top-level {@code format}, so {@code format} would ask for nothing.
     */
    private static void applyResponseFormat(Map<String, Object> body, ResponseFormat format) {
        if (format instanceof ResponseFormat.JsonObject) {
            body.put("response_format", Map.of("type", "json_object"));
        } else if (format instanceof ResponseFormat.JsonSchema js) {
            Map<String, Object> jsonSchema = new LinkedHashMap<>();
            jsonSchema.put("name", js.name());
            jsonSchema.put("schema", js.schema());
            jsonSchema.put("strict", js.strict());
            body.put("response_format", Map.of("type", "json_schema", "json_schema", jsonSchema));
        }
    }

    private Map<String, Object> buildChatBody(ChatRequest request) {
        List<Map<String, Object>> messages = request.getMessages().stream()
                .map(this::serializeMessage)
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", stripPrefix(request.getModel()));
        body.put("messages", messages);
        if (request.getMaxTokens()   != null) body.put("max_tokens",  request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP()        != null) body.put("top_p",       request.getTopP());
        if (request.getStop() != null) body.put("stop", request.getStop());
        if (request.getSeed() != null) body.put("seed", request.getSeed());
        applyResponseFormat(body, request.getResponseFormat());
        applyTools(body, request);
        return body;
    }

    /**
     * One message in the OpenAI shape Ollama's {@code /v1} endpoint reads, with the tool metadata of an
     * agent loop (#30): {@code tool_calls} on an assistant message, {@code tool_call_id} on a tool result.
     */
    private Map<String, Object> serializeMessage(MultimodalMessage m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("role", m.getRole());
        out.put("content", extractContent(m));
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            out.put("tool_calls", m.getToolCalls().stream()
                    .map(tc -> Map.of(
                            "id", tc.getId() == null ? "" : tc.getId(),
                            "type", "function",
                            "function", Map.of(
                                    "name", tc.getName() == null ? "" : tc.getName(),
                                    "arguments", tc.getArguments() == null ? "" : tc.getArguments())))
                    .collect(Collectors.toList()));
        }
        if (m.getToolCallId() != null) {
            out.put("tool_call_id", m.getToolCallId());
        }
        if (m.getName() != null) {
            out.put("name", m.getName());
        }
        return out;
    }

    /**
     * Tool definitions and {@code tool_choice}, passed through as given (#30). The gateway never drops
     * them: a model without tool support makes Ollama refuse the request, and that refusal is returned.
     */
    private void applyTools(Map<String, Object> body, ChatRequest request) {
        if (request.getTools() == null || request.getTools().isEmpty()) return;
        body.put("tools", request.getTools().stream()
                .map(t -> {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", t.getName());
                    if (t.getDescription() != null) function.put("description", t.getDescription());
                    if (t.getParameters() != null) function.put("parameters", t.getParameters());
                    return Map.of("type", "function", "function", function);
                })
                .collect(Collectors.toList()));
        if (request.getToolChoice() != null) {
            body.put("tool_choice", request.getToolChoice());
        }
    }

    /** Strip the "ollama/" prefix that the routing key uses. */
    private String stripPrefix(String model) {
        return model != null && model.startsWith("ollama/") ? model.substring(7) : model;
    }

    /**
     * A text-only message stays a plain string. A message with an image becomes the OpenAI content array
     * Ollama's {@code /v1} endpoint reads, each image as a base64 {@code data:} URL.
     */
    private Object extractContent(MultimodalMessage msg) {
        if (msg.getContent() == null || msg.getContent().isEmpty()) return "";
        boolean allText = msg.getContent().stream().allMatch(b -> b instanceof ContentBlock.TextBlock);
        if (allText) {
            return msg.getContent().stream()
                    .map(b -> ((ContentBlock.TextBlock) b).text())
                    .collect(Collectors.joining("\n"));
        }
        return msg.getContent().stream()
                // No default: ContentBlock is sealed, so a new kind is a compile error here rather than a
                // block silently dropped.
                .map(b -> switch (b) {
                    case ContentBlock.TextBlock tb -> (Object) Map.of("type", "text", "text", tb.text());
                    case ContentBlock.ImageBlock ib -> Map.of("type", "image_url", "image_url", Map.of("url", dataUrl(ib)));
                })
                .collect(Collectors.toList());
    }

    /**
     * An image as a {@code data:} URL. Ollama refuses an https URL ("please use base64 encoded data"), so a
     * URL is fetched when the operator allowed fetching and refused before the call when not.
     */
    private String dataUrl(ContentBlock.ImageBlock ib) {
        if (!ib.isUrl()) {
            return "data:" + ib.mediaType() + ";base64," + ib.data();
        }
        if (!imageFetcher.enabled()) {
            throw new GatewayException("UNSUPPORTED_CAPABILITY",
                    "Ollama takes images as base64 data: URLs, not https URLs. Send the image as base64, "
                    + "or turn on image fetching (dvara.llm-gateway.image-fetch.enabled).");
        }
        ImageFetcher.FetchedImage image = imageFetcher.fetch(ib.data());
        return "data:" + image.mediaType() + ";base64," + image.base64();
    }

    private ChatResponse mapToInternal(OllamaResponse resp, String model) {
        if (resp == null || resp.getChoices() == null || resp.getChoices().isEmpty()) {
            // An empty reply is the upstream's failure. Left alone it became a null-pointer message.
            throw new GatewayException("PROVIDER_ERROR", "Ollama" + " returned an empty response");
        }
        List<ChatResponse.Choice> choices = resp.getChoices().stream()
                .map(c -> ChatResponse.Choice.builder()
                        .index(c.getIndex())
                        .message(toAssistantMessage(c.getMessage()))
                        .finishReason(c.getFinishReason())
                        .build())
                .toList();

        return ChatResponse.builder()
                .id(resp.getId() != null ? resp.getId() : "ollama-" + Instant.now().toEpochMilli())
                .object("chat.completion")
                .created(resp.getCreated() > 0 ? resp.getCreated() : Instant.now().getEpochSecond())
                .model(model)
                .choices(choices)
                // Null where the upstream reported nothing: a zeroed block would claim the call
                // consumed nothing, which the metering path cannot tell from a real zero.
                .usage(resp.getUsage() == null ? null
                        : ChatResponse.Usage.builder()
                                .promptTokens(resp.getUsage().getPromptTokens())
                                .completionTokens(resp.getUsage().getCompletionTokens())
                                .totalTokens(resp.getUsage().getTotalTokens())
                                .build())
                .build();
    }

    /** The model's reply, carrying any tool calls it made (#30) so they reach the agent. */
    private static MultimodalMessage toAssistantMessage(OllamaResponse.OllamaMessage m) {
        String text = m != null && m.getContent() != null ? m.getContent() : "";
        if (m == null || m.getToolCalls() == null || m.getToolCalls().isEmpty()) {
            return MultimodalMessage.assistant(text);
        }
        return MultimodalMessage.builder()
                .role("assistant")
                .content(List.of(new ContentBlock.TextBlock(text)))
                .toolCalls(m.getToolCalls().stream()
                        .map(tc -> com.dvarahq.core.model.ToolCall.builder()
                                .id(tc.getId())
                                .name(tc.getFunction() != null ? tc.getFunction().getName() : null)
                                .arguments(tc.getFunction() != null ? tc.getFunction().getArguments() : null)
                                .build())
                        .toList())
                .build();
    }

    /**
     * Vision is declared: a model without it makes Ollama refuse the image, and that refusal is returned.
     * Tool calls are supported on the plain path and on a stream: the stream decoder puts every
     * tool-call fragment on the chunk, which was checked against a live Ollama.
     */
    @Override
    public ProviderCapabilities capabilities() {
        // streaming, vision, toolCalls, structuredOutputs, jsonMode, batch, streamingToolCalls, maxContextTokens
        return new ProviderCapabilities(true, true, true, structuredOutputs, true, false, true, 32_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("ollama/");
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        if (workspaceEndpoints != null) {
            // Per-workspace: there is no one Ollama whose models are the platform's to list.
            return List.of();
        }
        OllamaModelList response = restClient.get()
                .uri("/api/tags")
                .retrieve()
                .body(OllamaModelList.class);
        if (response == null || response.models == null) return List.of();
        return response.models.stream()
                .map(m -> new com.dvarahq.core.provider.ModelInfo(
                        "ollama/" + m.name, "ollama", 0L))
                .toList();
    }

    // -------------------------------------------------------------------------
    // SSE stream iterator (same format as OpenAI)
    // -------------------------------------------------------------------------

    // Package-private so tests can drive the parser from a literal stream.
    static class OllamaSseIterator implements Iterator<SseChunk>, AutoCloseable, ReleasableUpstream {

        private final BufferedReader reader;
        private final AutoCloseable transport;   // the body stream; closing it bypasses the reader's lock
        private final String model;
        private SseChunk next;
        private boolean done;
        private boolean finished;   // a chunk carrying a finish reason has been returned
        /** Ollama's tool-call key (its index, or its id when it sends none) to a zero-based index. */
        private final Map<String, Integer> toolCallIndexes = new LinkedHashMap<>();
        /** Fragments with neither index nor id: each is its own call. */
        private int anonymousCalls;

        OllamaSseIterator(BufferedReader reader, String model) {
            this(() -> { }, reader, model);
        }

        OllamaSseIterator(AutoCloseable transport, BufferedReader reader, String model) {
            this.transport = transport;
            this.reader = reader;
            this.model = model;
        }

        @Override
        public boolean hasNext() {
            if (done) return false;
            if (next != null) return true;
            next = advance();
            return next != null;
        }

        @Override
        public SseChunk next() {
            if (!hasNext()) throw new NoSuchElementException();
            SseChunk result = next;
            next = null;
            if (result.isDone()) finished = true;
            return result;
        }

        private SseChunk advance() {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith(":")) continue;
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) {
                        done = true;
                        closeReader();
                        if (finished) return null;
                        throw incomplete();   // the chunk carrying a finish reason ends the stream first
                    }
                    OllamaStreamChunk chunk = JsonMapper.instance().readValue(data, OllamaStreamChunk.class);
                    try {
                        return mapStreamChunk(chunk);
                    } catch (GatewayException e) {
                        done = true;
                        closeReader();
                        throw e;
                    }
                }
                done = true;
                closeReader();
                if (finished) return null;
                throw incomplete();
            } catch (IOException e) {
                done = true;
                closeReader();
                throw new GatewayException("PROVIDER_ERROR",
                        "Error reading Ollama stream: " + e.getMessage(), e);
            }
        }

        /** The stream ended with no finish reason, so the answer is incomplete. */
        private GatewayException incomplete() {
            return new GatewayException("PROVIDER_ERROR",
                    "Ollama stream ended before a finish reason; the answer is incomplete");
        }

        private SseChunk mapStreamChunk(OllamaStreamChunk chunk) {
            String delta = null;
            String finishReason = null;
            List<ToolCallDelta> toolCalls = null;
            if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                OllamaStreamChunk.StreamChoice choice = chunk.getChoices().get(0);
                if (choice.getDelta() != null) {
                    delta = choice.getDelta().getContent();
                    toolCalls = toolCalls(choice.getDelta().getToolCalls());
                }
                finishReason = choice.getFinishReason();
            }
            // Any finish reason ends the answer: "length" stops it as surely as "stop".
            boolean isDone = finishReason != null;
            return SseChunk.builder()
                    .id(chunk.getId())
                    .model(chunk.getModel() != null ? chunk.getModel() : model)
                    .delta(delta)
                    .toolCalls(toolCalls)
                    .finishReason(finishReason)
                    .done(isDone)
                    .build();
        }

        /**
         * The tool-call fragments on one delta, null when there are none. Ollama's position becomes a
         * consecutive zero-based index in order of first appearance; a fragment without one is keyed by its
         * id, and one with neither is a call of its own. Empty argument text is null.
         */
        private List<ToolCallDelta> toolCalls(List<OllamaStreamChunk.StreamToolCall> raw) {
            if (raw == null || raw.isEmpty()) return null;
            List<ToolCallDelta> out = new ArrayList<>(raw.size());
            for (OllamaStreamChunk.StreamToolCall call : raw) {
                if (call.getType() != null && !"function".equals(call.getType())) {
                    throw new GatewayException("PROVIDER_ERROR", "Ollama streamed a tool call of type '"
                            + call.getType() + "', which this gateway cannot relay");
                }
                String key = call.getIndex() != null ? "i:" + call.getIndex()
                        : call.getId() != null ? "id:" + call.getId()
                        : "anonymous:" + anonymousCalls++;
                int index = toolCallIndexes.computeIfAbsent(key, k -> toolCallIndexes.size());
                String name = call.getFunction() != null ? call.getFunction().getName() : null;
                String arguments = call.getFunction() != null ? call.getFunction().getArguments() : null;
                out.add(new ToolCallDelta(index, call.getId(), name,
                        arguments == null || arguments.isEmpty() ? null : arguments));
            }
            return out;
        }

        /** Close the body stream, not the reader: a read parked in readLine() holds the reader's lock. */
        @Override
        public void releaseTransport() {
            try { transport.close(); } catch (Exception ignored) { }
        }

        @Override
        public void close() { closeReader(); }

        private void closeReader() {
            try { reader.close(); } catch (IOException ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Inner response DTOs (Ollama returns OpenAI-compatible format)
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OllamaResponse {
        private String id;
        private String object;
        private long created;
        private String model;
        private List<OllamaChoice> choices;
        private OllamaUsage usage;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OllamaChoice {
            private int index;
            private OllamaMessage message;
            @JsonProperty("finish_reason") private String finishReason;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OllamaMessage {
            private String role;
            private String content;
            @JsonProperty("tool_calls") private List<OllamaToolCall> toolCalls;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OllamaToolCall {
            private String id;
            private String type;
            private OllamaFunction function;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OllamaFunction {
            private String name;
            private String arguments;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OllamaUsage {
            @JsonProperty("prompt_tokens")     private int promptTokens;
            @JsonProperty("completion_tokens") private int completionTokens;
            @JsonProperty("total_tokens")      private int totalTokens;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class OllamaStreamChunk {
        private String id;
        private String object;
        private long created;
        private String model;
        private List<StreamChoice> choices;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class StreamChoice {
            private int index;
            private StreamDelta delta;
            @JsonProperty("finish_reason") private String finishReason;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class StreamDelta {
            private String role;
            private String content;
            @JsonProperty("tool_calls") private List<StreamToolCall> toolCalls;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class StreamToolCall {
            private Integer index;
            private String id;
            private String type;
            private StreamFunction function;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class StreamFunction {
            private String name;
            private String arguments;
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OllamaEmbeddingResponse {
        private String model;
        private List<OllamaEmbedding> data;
        private OllamaEmbeddingUsage usage;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OllamaEmbedding {
            private int index;
            private List<Double> embedding;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OllamaEmbeddingUsage {
            @JsonProperty("prompt_tokens") private int promptTokens;
            @JsonProperty("total_tokens")  private int totalTokens;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OllamaModelList {
        private List<OllamaModelEntry> models;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class OllamaModelEntry {
        private String name;
    }
}