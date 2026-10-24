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
package com.dvarahq.providers.cohere;

import com.dvarahq.providers.support.ProviderErrors;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ReleasableUpstream;
import com.dvarahq.providers.support.StreamTransport;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.AbstractLlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

public class CohereProvider extends AbstractLlmProvider {

    private final RestClient restClient;

    public CohereProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("cohere");
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.cohere.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build();
    }

    public CohereProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    CohereProvider(RestClient restClient) {
        super("cohere");
        this.restClient = restClient;
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        rejectUnsupportedResponseFormat(request.getResponseFormat());

        Map<String, Object> body = buildChatBody(request);

        CohereChatResponse cohere = restClient.post()
                .uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw ProviderErrors.refusal("Cohere", res, "Cohere API error");
                })
                .body(CohereChatResponse.class);

        return mapChatResponse(cohere);
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        rejectUnsupportedResponseFormat(request.getResponseFormat());

        Map<String, Object> body = buildChatBody(request);
        body.put("stream", true);

        return restClient.post()
                .uri("/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    if (res.getStatusCode().isError()) {
                        throw GatewayException.upstream(res.getStatusCode().value(),
                                "Cohere streaming error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                    }
                    // The body stream is the transport handle, released through StreamTransport:
                    // ClientHttpResponse.close() drains the body first and would wait for a stalled server, and
                    // what returns a parked read differs per HTTP client; StreamTransport knows both.
                    java.io.InputStream responseBody = res.getBody();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8));
                    return new CohereSseIterator(() -> StreamTransport.release(res, responseBody), reader, request.getModel());
                }, false);
    }

    // -------------------------------------------------------------------------
    // Shared body builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildChatBody(ChatRequest request) {
        List<Map<String, Object>> messages = request.getMessages().stream()
                .map(CohereProvider::serializeMessage)
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("messages", messages);
        if (request.getMaxTokens()   != null) body.put("max_tokens",  request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP()        != null) body.put("p",           request.getTopP());
        if (request.getStop()        != null) body.put("stop_sequences", request.getStop());
        if (request.getSeed()        != null) body.put("seed",        request.getSeed());
        applyTools(body, request);
        return body;
    }

    /**
     * One message on Cohere's v2 wire. Tool calls ride on an assistant message as {@code tool_calls}, in
     * OpenAI's {@code {id, type, function: {name, arguments}}} shape; a tool result is a {@code tool}
     * message with its {@code tool_call_id} and the result as a string. An assistant message that only
     * calls tools carries no {@code content}.
     */
    private static Map<String, Object> serializeMessage(MultimodalMessage m) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", m.getRole());
        boolean callsTools = m.getToolCalls() != null && !m.getToolCalls().isEmpty();
        Object content = extractContent(m);
        if (!(callsTools && "".equals(content))) {
            msg.put("content", content);
        }
        if (callsTools) {
            msg.put("tool_calls", m.getToolCalls().stream()
                    .map(tc -> Map.of(
                            "id", tc.getId() == null ? "" : tc.getId(),
                            "type", "function",
                            "function", Map.of(
                                    "name", tc.getName() == null ? "" : tc.getName(),
                                    "arguments", tc.getArguments() == null || tc.getArguments().isBlank()
                                            ? "{}" : tc.getArguments())))
                    .collect(Collectors.toList()));
        }
        if (m.getToolCallId() != null) {
            msg.put("tool_call_id", m.getToolCallId());
        }
        return msg;
    }

    /**
     * Tool definitions in Cohere's v2 shape, which is OpenAI's, and {@code tool_choice} in Cohere's words.
     * Cohere takes {@code REQUIRED} and {@code NONE}, and lets the model choose when it is absent, so
     * {@code auto} sends nothing. Cohere has no way to name one tool, so a choice that names one offers
     * that tool alone, as {@code REQUIRED}: the model must call it, which is what the caller asked.
     */
    private static void applyTools(Map<String, Object> body, ChatRequest request) {
        if (request.getTools() == null || request.getTools().isEmpty()) return;
        List<com.dvarahq.core.model.ToolDefinition> tools = request.getTools().stream()
                .map(com.dvarahq.core.model.ToolDefinition::asFunction)
                .toList();
        Object choice = request.getToolChoice();
        String forced = namedTool(choice);
        if (forced != null) {
            tools = tools.stream().filter(t -> forced.equals(t.getName())).toList();
            if (tools.isEmpty()) {
                throw new GatewayException("INVALID_REQUEST",
                        "tool_choice names the function " + forced + ", which is not among the request's tools");
            }
        }
        body.put("tools", tools.stream()
                .map(t -> {
                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", t.getName());
                    if (t.getDescription() != null) function.put("description", t.getDescription());
                    function.put("parameters", t.getParameters() != null ? t.getParameters() : Map.of("type", "object"));
                    return Map.of("type", "function", "function", function);
                })
                .collect(Collectors.toList()));
        if (forced != null || "required".equals(choice)) {
            body.put("tool_choice", "REQUIRED");
        } else if ("none".equals(choice)) {
            body.put("tool_choice", "NONE");
        }
    }

    /** The function a {@code {"type": "function", "function": {"name": ...}}} choice names, or null. */
    private static String namedTool(Object choice) {
        if (choice instanceof Map<?, ?> m && m.get("function") instanceof Map<?, ?> f && f.get("name") instanceof String n) {
            return n;
        }
        return null;
    }

    /**
     * Text alone goes as a string. With an image the content is a list of parts: {@code text}, and
     * {@code image_url} with its {@code url} (a URL as sent, base64 as a {@code data:} URL) and
     * {@code detail} when the caller set one. A model with no vision refuses the image itself.
     */
    private static Object extractContent(MultimodalMessage msg) {
        if (msg.getContent() == null || msg.getContent().isEmpty()) return "";
        boolean allText = msg.getContent().stream().allMatch(b -> b instanceof ContentBlock.TextBlock);
        if (allText) {
            return msg.getContent().stream()
                    .map(b -> ((ContentBlock.TextBlock) b).text())
                    .collect(Collectors.joining("\n"));
        }
        return msg.getContent().stream()
                .map(b -> switch (b) {
                    case ContentBlock.TextBlock tb -> (Object) Map.of("type", "text", "text", tb.text());
                    case ContentBlock.ImageBlock ib -> Map.of("type", "image_url", "image_url", imageUrl(ib));
                    // Only an Anthropic provider takes thinking; the dispatcher never sends it here.
                    case ContentBlock.ThinkingBlock _, ContentBlock.RedactedThinkingBlock _ ->
                            throw new GatewayException("UNSUPPORTED_CAPABILITY",
                                    "Cohere cannot take extended thinking; only an Anthropic provider can.");
                })
                .collect(Collectors.toList());
    }

    private static Map<String, Object> imageUrl(ContentBlock.ImageBlock ib) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", ib.isUrl() ? ib.data() : "data:" + ib.mediaType() + ";base64," + ib.data());
        if (ib.detail() != null) {
            m.put("detail", ib.detail());
        }
        return m;
    }

    private void rejectUnsupportedResponseFormat(ResponseFormat format) {
        if (format != null && !(format instanceof ResponseFormat.Text)) {
            throw new GatewayException("UNSUPPORTED_RESPONSE_FORMAT",
                    "Cohere provider does not support response_format. Supported formats: [text]");
        }
    }

    // -------------------------------------------------------------------------
    // Response mapping
    // -------------------------------------------------------------------------

    private ChatResponse mapChatResponse(CohereChatResponse cohere) {
        String content = extractResponseContent(cohere);

        MultimodalMessage.MultimodalMessageBuilder message = MultimodalMessage.builder()
                .role("assistant")
                .content(List.of(new ContentBlock.TextBlock(content)));
        if (cohere.getMessage() != null && cohere.getMessage().getToolCalls() != null
                && !cohere.getMessage().getToolCalls().isEmpty()) {
            message.toolCalls(cohere.getMessage().getToolCalls().stream()
                    .map(tc -> com.dvarahq.core.model.ToolCall.builder()
                            .id(tc.getId())
                            .name(tc.getFunction() != null ? tc.getFunction().getName() : null)
                            .arguments(tc.getFunction() != null ? tc.getFunction().getArguments() : null)
                            .build())
                    .toList());
        }
        ChatResponse.Choice choice = ChatResponse.Choice.builder()
                .index(0)
                .message(message.build())
                .finishReason(mapFinishReason(cohere.getFinishReason()))
                .build();

        ChatResponse.Usage usage = mapUsage(cohere.getUsage());

        return ChatResponse.builder()
                .id(cohere.getId())
                .object("chat.completion")
                .model(cohere.getModel() != null ? cohere.getModel() : "")
                .choices(List.of(choice))
                .usage(usage)
                .build();
    }

    private String extractResponseContent(CohereChatResponse cohere) {
        if (cohere.getMessage() == null || cohere.getMessage().getContent() == null) return "";
        return cohere.getMessage().getContent().stream()
                .filter(c -> "text".equals(c.getType()))
                .map(CohereContentBlock::getText)
                .collect(Collectors.joining());
    }

    static String mapFinishReason(String reason) {
        if (reason == null) return null;
        return switch (reason) {
            case "COMPLETE", "STOP_SEQUENCE" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "TOOL_CALL" -> "tool_calls";
            case "ERROR" -> "error";
            default -> reason;
        };
    }

    /**
     * Null where the upstream reported nothing — no usage object at all, or one carrying neither
     * counted tokens nor billed units. A zeroed block would claim the call consumed nothing, which
     * the metering path cannot tell from a real zero.
     */
    private static ChatResponse.Usage mapUsage(CohereUsage usage) {
        if (usage == null) return null;
        int inputTokens;
        int outputTokens;
        if (usage.getTokens() != null) {
            inputTokens = usage.getTokens().getInputTokens();
            outputTokens = usage.getTokens().getOutputTokens();
        } else if (usage.getBilledUnits() != null) {
            inputTokens = usage.getBilledUnits().getInputTokens();
            outputTokens = usage.getBilledUnits().getOutputTokens();
        } else {
            return null;
        }
        return ChatResponse.Usage.builder()
                .promptTokens(inputTokens)
                .completionTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .build();
    }

    // -------------------------------------------------------------------------
    // Capabilities / routing
    // -------------------------------------------------------------------------

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                /* streaming */ true,
                /* vision */ true,
                /* tool calls */ true,
                /* structured outputs */ false,
                /* json mode */ false,
                /* batch */ false,
                /* streamed tool calls */ true,
                /* max context */ 128_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("command");
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        // Chat is a v2 endpoint and the model list is v1. The base URL ends in /v2, so a path relative to it
        // would ask for /v2/v1/models: swap the trailing version and keep any path in front of it.
        CohereModelList response = restClient.get()
                .uri(uriBuilder -> {
                    String basePath = uriBuilder.build().getPath();
                    basePath = basePath == null ? "" : basePath;
                    return uriBuilder.replacePath(basePath.replaceFirst("/v2/?$", "") + "/v1/models").build();
                })
                .retrieve()
                .body(CohereModelList.class);
        if (response == null || response.models == null) return List.of();
        return response.models.stream()
                .map(m -> new com.dvarahq.core.provider.ModelInfo(
                        m.name, "cohere", 0L))
                .toList();
    }

    // -------------------------------------------------------------------------
    // SSE stream iterator
    // -------------------------------------------------------------------------

    private static class CohereSseIterator implements Iterator<SseChunk>, AutoCloseable, ReleasableUpstream {

        private final BufferedReader reader;
        private final AutoCloseable transport;   // the body stream; closing it bypasses the reader's lock
        private final String model;
        private SseChunk next;
        private boolean done;
        private boolean finished;   // a chunk carrying a finish reason has been returned

        CohereSseIterator(BufferedReader reader, String model) {
            this(() -> { }, reader, model);
        }

        CohereSseIterator(AutoCloseable transport, BufferedReader reader, String model) {
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
                        throw incomplete();   // message-end, which carries the finish, ends the stream first
                    }
                    CohereStreamEvent event = JsonMapper.instance().readValue(data, CohereStreamEvent.class);
                    SseChunk chunk = mapStreamEvent(event);
                    if (chunk != null) return chunk;
                }
                done = true;
                closeReader();
                if (finished) return null;
                throw incomplete();
            } catch (IOException e) {
                done = true;
                closeReader();
                throw new GatewayException("PROVIDER_ERROR",
                        "Error reading Cohere stream: " + e.getMessage(), e);
            }
        }

        /** The stream ended with no message-end finish, so the answer is incomplete. */
        private GatewayException incomplete() {
            return new GatewayException("PROVIDER_ERROR",
                    "Cohere stream ended before a finish reason; the answer is incomplete");
        }

        private SseChunk mapStreamEvent(CohereStreamEvent event) {
            if ("content-delta".equals(event.getType())) {
                String text = null;
                if (event.getDelta() != null && event.getDelta().getMessage() != null) {
                    text = event.getDelta().getMessage().text();
                }
                return SseChunk.builder()
                        .model(model)
                        .delta(text)
                        .done(false)
                        .build();
            } else if ("tool-call-start".equals(event.getType()) || "tool-call-delta".equals(event.getType())) {
                com.dvarahq.core.model.ToolCallDelta delta = toolCallDelta(event);
                if (delta == null) return null;
                return SseChunk.builder()
                        .model(model)
                        .toolCalls(List.of(delta))
                        .done(false)
                        .build();
            } else if ("message-end".equals(event.getType())) {
                String finishReason = null;
                ChatResponse.Usage usage = null;
                if (event.getDelta() != null) {
                    finishReason = mapFinishReason(event.getDelta().getFinishReason());
                    usage = mapUsage(event.getDelta().getUsage());
                }
                boolean isDone = finishReason != null;
                return SseChunk.builder()
                        .model(model)
                        .finishReason(finishReason)
                        .usage(usage)
                        .done(isDone)
                        .build();
            }
            // Skip other event types: message-start, content-start, content-end, tool-plan-delta (the
            // model's plan before it calls a tool, not answer text), tool-call-end, citations.
            return null;
        }

        /**
         * One fragment of a streamed tool call. {@code tool-call-start} carries the call's id, name and
         * usually empty arguments; each {@code tool-call-delta} carries more of the arguments. Both name
         * the call by the event's top-level {@code index}. The docs show {@code delta.message.tool_calls}
         * as one object in the stream and as a list in an SDK sample, so either is read.
         */
        private static com.dvarahq.core.model.ToolCallDelta toolCallDelta(CohereStreamEvent event) {
            if (event.getDelta() == null || event.getDelta().getMessage() == null) return null;
            com.fasterxml.jackson.databind.JsonNode calls = event.getDelta().getMessage().getToolCalls();
            if (calls == null || calls.isNull()) return null;
            com.fasterxml.jackson.databind.JsonNode call = calls.isArray() ? calls.path(0) : calls;
            if (call.isMissingNode()) return null;
            int index = event.getIndex() != null ? event.getIndex() : 0;
            String id = text(call.path("id"));
            String name = text(call.path("function").path("name"));
            String arguments = text(call.path("function").path("arguments"));
            if (id == null && name == null && (arguments == null || arguments.isEmpty())) return null;
            return new com.dvarahq.core.model.ToolCallDelta(index, id, name,
                    arguments == null || arguments.isEmpty() ? null : arguments);
        }

        private static String text(com.fasterxml.jackson.databind.JsonNode n) {
            return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
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
    // Inner response DTOs (Cohere wire format)
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereChatResponse {
        private String id;
        private String model;
        private CohereMessage message;
        @JsonProperty("finish_reason") private String finishReason;
        private CohereUsage usage;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereMessage {
        private String role;
        private List<CohereContentBlock> content;
        @JsonProperty("tool_calls") private List<CohereToolCall> toolCalls;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereToolCall {
        private String id;
        private String type;
        private CohereFunction function;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereFunction {
        private String name;
        private String arguments;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereContentBlock {
        private String type;
        private String text;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereUsage {
        @JsonProperty("billed_units") private CohereBilledUnits billedUnits;
        private CohereTokens tokens;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereBilledUnits {
        @JsonProperty("input_tokens")  private int inputTokens;
        @JsonProperty("output_tokens") private int outputTokens;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereTokens {
        @JsonProperty("input_tokens")  private int inputTokens;
        @JsonProperty("output_tokens") private int outputTokens;
    }

    // -------------------------------------------------------------------------
    // Streaming event DTOs
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereStreamEvent {
        private String type;
        private Integer index;
        private CohereStreamDelta delta;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereStreamDelta {
        private CohereStreamMessage message;
        @JsonProperty("finish_reason") private String finishReason;
        private CohereUsage usage;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereStreamMessage {
        /**
         * An object with the text on {@code content-delta} and {@code content-start}, and a list on
         * {@code message-start} ({@code "content": []}). Read as a tree, so the list does not fail the whole stream.
         */
        private com.fasterxml.jackson.databind.JsonNode content;
        @JsonProperty("tool_calls") private com.fasterxml.jackson.databind.JsonNode toolCalls;

        /** The text of an object {@code content}, or null for a list or no content. */
        String text() {
            if (content == null || !content.isObject()) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode text = content.get("text");
            return text == null || text.isNull() ? null : text.asText();
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereModelList {
        private List<CohereModelEntry> models;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereModelEntry {
        private String name;
    }
}