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
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.providers.support.OpenAiCompatibleStreamDecoder;
import com.dvarahq.providers.support.StreamTransport;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.AbstractLlmProvider;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Shared base for any provider whose chat and streaming surface speaks the OpenAI wire format:
 * the OpenAI, Azure OpenAI, Qwen, DeepSeek, Moonshot, ChatGLM and Grok providers. A new
 * OpenAI-compatible provider extends it rather than copying the body builder, response mapper or
 * stream decoder ({@link OpenAiCompatibleStreamDecoder}, which yields {@link SseChunk}s).
 *
 * <p>Subclasses override {@link #upstreamLabel()} for error messages, {@link #chatUri()} and
 * {@link #chatUriVars(ChatRequest)} when the chat endpoint embeds path variables (Azure OpenAI),
 * and {@link #includeModelInBody()} when the model must not be sent in the body (Azure OpenAI,
 * where it is the deployment name in the URL). Auth, base URL and interceptors are configured on
 * the {@link RestClient} passed to the constructor. Embeddings and {@code listModels} stay
 * provider-specific.
 *
 * <p>It lives in the {@code openai} package because OpenAI is the canonical implementation, and
 * is {@code public} so sibling packages can extend it.
 */
public abstract class AbstractOpenAiCompatibleProvider extends AbstractLlmProvider {

    protected final RestClient restClient;

    protected AbstractOpenAiCompatibleProvider(String name, RestClient restClient) {
        super(name);
        this.restClient = restClient;
    }

    // -------------------------------------------------------------------------
    // Subclass override points
    // -------------------------------------------------------------------------

    /**
     * Human-readable upstream name baked into error messages — e.g.
     * {@code "OpenAI"}, {@code "Azure OpenAI"}, {@code "Qwen"}. Surfaces in
     * {@code GatewayException} bodies that the data plane returns to clients.
     */
    protected abstract String upstreamLabel();

    /**
     * URI template for the chat completions endpoint relative to the
     * configured {@link RestClient} base URL. Default {@code "/chat/completions"}
     * is the canonical OpenAI shape; subclasses with a different shape (e.g.
     * Azure OpenAI's {@code /deployments/{deployment}/chat/completions?api-version=...})
     * override this and also {@link #chatUriVars(ChatRequest)}.
     */
    protected String chatUri() {
        return "/chat/completions";
    }

    /**
     * Variables to substitute into the {@link #chatUri()} template, in
     * declaration order. Default is the empty array — most subclasses don't
     * have per-request URI variables. Azure overrides to inject the
     * deployment name and API version.
     */
    protected Object[] chatUriVars(ChatRequest request) {
        return new Object[0];
    }

    /**
     * Whether the {@code model} field is included in the request body. True
     * for the canonical OpenAI shape (and everything that mirrors it); false
     * for Azure OpenAI, where the model is the URL deployment name and
     * including it in the body is rejected by the upstream. Default true.
     */
    protected boolean includeModelInBody() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        Map<String, Object> body = buildChatBody(request);

        OaiChatResponse oai = restClient.post()
                .uri(chatUri(), chatUriVars(request))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            upstreamLabel() + " API error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(OaiChatResponse.class);

        return mapChatResponse(oai);
    }

    // -------------------------------------------------------------------------
    // Batch API passthrough
    //
    // Bodies are relayed verbatim (raw JSON / bytes): the gateway governs +
    // meters at the surface, it does not model the provider's batch schema.
    // URI override points default to the canonical OpenAI shapes; Azure OpenAI
    // overrides them to append its {@code ?api-version=...} query.
    // -------------------------------------------------------------------------

    /** URI template for the Files endpoint (upload). */
    protected String filesUri() {
        return "/files";
    }

    /** URI template for the Batches collection endpoint (create). */
    protected String batchesUri() {
        return "/batches";
    }

    /** URI template for a single Batch resource; the batch id is the sole path var. */
    protected String batchUri() {
        return "/batches/{batchId}";
    }

    /** URI template for a file's raw content; the file id is the sole path var. */
    protected String fileContentUri() {
        return "/files/{fileId}/content";
    }

    /** URI template for cancelling a batch; the batch id is the sole path var. */
    protected String batchCancelUri() {
        return "/batches/{batchId}/cancel";
    }

    @Override
    public String uploadFile(byte[] content, String filename, String purpose) {
        MultipartBodyBuilder parts = new MultipartBodyBuilder();
        parts.part("purpose", purpose);
        parts.part("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);
        return restClient.post()
                .uri(filesUri())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parts.build())
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(), upstreamLabel() + " file upload error "
                            + res.getStatusCode().value()
                            + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(String.class);
    }

    @Override
    public String createBatch(String requestJson) {
        return restClient.post()
                .uri(batchesUri())
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestJson)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(), upstreamLabel() + " batch submit error "
                            + res.getStatusCode().value()
                            + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(String.class);
    }

    @Override
    public String getBatch(String batchId) {
        return restClient.get()
                .uri(batchUri(), batchId)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(), upstreamLabel() + " batch fetch error "
                            + res.getStatusCode().value()
                            + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(String.class);
    }

    @Override
    public String cancelBatch(String batchId) {
        return restClient.post()
                .uri(batchCancelUri(), batchId)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(), upstreamLabel() + " batch cancel error "
                            + res.getStatusCode().value()
                            + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(String.class);
    }

    @Override
    public byte[] getFileContent(String fileId) {
        return restClient.get()
                .uri(fileContentUri(), fileId)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(), upstreamLabel() + " file download error "
                            + res.getStatusCode().value()
                            + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(byte[].class);
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        Map<String, Object> body = buildChatBody(request);
        body.put("stream", true);
        // Ask for real usage. Without this the upstream sends no usage block on a stream and the
        // request is billed on a TokenEstimator guess. The final chunk then carries `usage` with an
        // empty `choices` array, which the stream decoder folds into the chunk carrying the finish
        // reason.
        body.put("stream_options", Map.of("include_usage", true));

        return restClient.post()
                .uri(chatUri(), chatUriVars(request))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    if (res.getStatusCode().isError()) {
                        throw GatewayException.upstream(res.getStatusCode().value(),
                                upstreamLabel() + " streaming error " + res.getStatusCode().value()
                                    + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                    }
                    // The body stream is the transport handle, released through StreamTransport:
                    // ClientHttpResponse.close() drains the body first and would wait for a stalled server, and
                    // what returns a parked read differs per HTTP client; StreamTransport knows both.
                    java.io.InputStream responseBody = res.getBody();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8));
                    return new OpenAiCompatibleStreamDecoder(() -> StreamTransport.release(res, responseBody), reader, request.getModel(), upstreamLabel());
                }, false);
    }

    // -------------------------------------------------------------------------
    // Body builder + content translation
    // -------------------------------------------------------------------------

    protected Map<String, Object> buildChatBody(ChatRequest request) {
        List<Map<String, Object>> messages = request.getMessages().stream()
                .map(this::serializeMessage)
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        if (includeModelInBody()) body.put("model", request.getModel());
        body.put("messages", messages);
        if (request.getMaxTokens()   != null) body.put("max_tokens",   request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature",  request.getTemperature());
        if (request.getTopP()        != null) body.put("top_p",        request.getTopP());
        if (request.getFrequencyPenalty() != null) body.put("frequency_penalty", request.getFrequencyPenalty());
        if (request.getPresencePenalty()  != null) body.put("presence_penalty",  request.getPresencePenalty());
        if (request.getStop() != null) body.put("stop", request.getStop());
        if (request.getSeed() != null) body.put("seed", request.getSeed());
        applyResponseFormat(body, request.getResponseFormat());
        applyTools(body, request);
        return body;
    }

    /**
     * Serializes one message to the OpenAI wire shape, carrying tool metadata:
     * {@code tool_calls} on an assistant message, {@code tool_call_id} on a
     * {@code tool}-role result message.
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
     * Relays function-calling tool definitions + {@code tool_choice} in the
     * native OpenAI shape. The gateway is a faithful pipe — it forwards
     * the definitions and forwards the model's {@code tool_calls} back; it runs
     * no agent loop.
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

    private void applyResponseFormat(Map<String, Object> body, ResponseFormat format) {
        if (format == null || format instanceof ResponseFormat.Text) return;
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

    private Object extractContent(MultimodalMessage msg) {
        if (msg.getContent() == null || msg.getContent().isEmpty()) return "";
        boolean allText = msg.getContent().stream().allMatch(b -> b instanceof ContentBlock.TextBlock);
        if (allText) {
            return msg.getContent().stream()
                    .map(b -> ((ContentBlock.TextBlock) b).text())
                    .collect(Collectors.joining("\n"));
        }
        return msg.getContent().stream()
                // No default: ContentBlock is sealed and both permitted types are handled, so a third
                // one is a compile error here rather than silently becoming the text of its own
                // toString().
                .map(b -> switch (b) {
                    case ContentBlock.TextBlock tb -> (Object) Map.of("type", "text", "text", tb.text());
                    case ContentBlock.ImageBlock ib -> Map.of("type", "image_url",
                            "image_url", Map.of("url", "data:" + ib.mediaType() + ";base64," + ib.data()));
                })
                .collect(Collectors.toList());
    }

    private ChatResponse mapChatResponse(OaiChatResponse oai) {
        List<ChatResponse.Choice> choices = oai.getChoices().stream()
                .map(c -> ChatResponse.Choice.builder()
                        .index(c.getIndex())
                        .message(toAssistantMessage(c.getMessage()))
                        .finishReason(c.getFinishReason())
                        .build())
                .toList();

        // Null where the upstream reported nothing: a zeroed block would claim the call consumed
        // nothing, which the metering path cannot tell from a real zero.
        ChatResponse.Usage usage = oai.getUsage() == null
                ? null
                : ChatResponse.Usage.builder()
                        .promptTokens(oai.getUsage().getPromptTokens())
                        .completionTokens(oai.getUsage().getCompletionTokens())
                        .totalTokens(oai.getUsage().getTotalTokens())
                        .build();

        return ChatResponse.builder()
                .id(oai.getId())
                .object("chat.completion")
                .created(oai.getCreated())
                .model(oai.getModel())
                .choices(choices)
                .usage(usage)
                .build();
    }

    /**
     * Maps an upstream assistant message to the internal model, carrying any
     * {@code tool_calls} the model emitted so they reach the client.
     */
    private MultimodalMessage toAssistantMessage(OaiChatResponse.OaiMessage m) {
        MultimodalMessage.MultimodalMessageBuilder builder = MultimodalMessage.builder()
                .role(m.getRole() != null ? m.getRole() : "assistant")
                .content(List.of(new ContentBlock.TextBlock(m.getContent() != null ? m.getContent() : "")));
        if (m.getToolCalls() != null && !m.getToolCalls().isEmpty()) {
            builder.toolCalls(m.getToolCalls().stream()
                    .map(tc -> com.dvarahq.core.model.ToolCall.builder()
                            .id(tc.getId())
                            .name(tc.getFunction() != null ? tc.getFunction().getName() : null)
                            .arguments(tc.getFunction() != null ? tc.getFunction().getArguments() : null)
                            .build())
                    .toList());
        }
        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Wire-format DTOs — shared across every OpenAI-compatible upstream
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class OaiChatResponse {
        private String id;
        private String object;
        private long created;
        private String model;
        private List<OaiChoice> choices;
        private OaiUsage usage;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OaiChoice {
            private int index;
            private OaiMessage message;
            @JsonProperty("finish_reason") private String finishReason;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OaiMessage {
            private String role;
            private String content;
            @JsonProperty("tool_calls") private List<OaiToolCall> toolCalls;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OaiToolCall {
            private String id;
            private String type;
            private OaiFunction function;

            @Data @JsonIgnoreProperties(ignoreUnknown = true)
            static class OaiFunction {
                private String name;
                private String arguments;
            }
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OaiUsage {
            @JsonProperty("prompt_tokens")     private int promptTokens;
            @JsonProperty("completion_tokens") private int completionTokens;
            @JsonProperty("total_tokens")      private int totalTokens;
        }
    }

    // -------------------------------------------------------------------------
    // Model catalogue helper — shared across OpenAI-compatible providers
    // -------------------------------------------------------------------------

    /**
     * Helper that hits the canonical {@code GET /models} endpoint and returns
     * the catalogue as {@link com.dvarahq.core.provider.ModelInfo} entries.
     * Tolerates missing or non-standard responses by returning an empty list
     * rather than throwing — many OpenAI-compatible upstreams either don't
     * implement {@code /models} or return a divergent shape.
     *
     * <p>Subclasses that need a different URL path (e.g. Azure's
     * {@code /models?api-version=...}) should override
     * {@link com.dvarahq.core.provider.LlmProvider#listModels()} directly
     * rather than calling this.
     *
     * @param defaultOwner fallback {@code ownedBy} when the upstream omits it
     */
    protected List<com.dvarahq.core.provider.ModelInfo> listOaiCompatModels(String defaultOwner) {
        try {
            OaiCompatModelList response = restClient.get()
                    .uri("/models")
                    .retrieve()
                    .body(OaiCompatModelList.class);
            if (response == null || response.data == null) return List.of();
            return response.data.stream()
                    .map(m -> new com.dvarahq.core.provider.ModelInfo(
                            m.id, m.ownedBy != null ? m.ownedBy : defaultOwner, m.created))
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class OaiCompatModelList {
        private List<OaiCompatModel> data;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class OaiCompatModel {
        private String id;
        @JsonProperty("owned_by") private String ownedBy;
        private long created;
    }
}