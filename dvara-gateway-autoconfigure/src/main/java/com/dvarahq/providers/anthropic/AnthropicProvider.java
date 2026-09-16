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
package com.dvarahq.providers.anthropic;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.ReleasableUpstream;
import com.dvarahq.providers.support.StreamTransport;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.provider.AbstractLlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
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

public class AnthropicProvider extends AbstractLlmProvider {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int    DEFAULT_MAX_TOKENS = 1024;
    private static final String JSON_SYSTEM_SUFFIX = "\n\nRespond with valid JSON only. Do not include any text outside the JSON object.";
    private static final String STRUCTURED_OUTPUT_TOOL = "structured_output";

    private final RestClient restClient;

    public AnthropicProvider(SecretProvider secretProvider, RestClient.Builder builder) {
        super("anthropic");
        this.restClient = builder
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.anthropic.api-key",
                        "x-api-key", java.util.function.Function.identity()))
                .build();
    }

    public AnthropicProvider(SecretProvider secretProvider) {
        this(secretProvider, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    AnthropicProvider(RestClient restClient) {
        super("anthropic");
        this.restClient = restClient;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        Map<String, Object> body = buildBody(request);

        AnthropicResponse ar = restClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "Anthropic API error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(AnthropicResponse.class);

        return mapToInternal(ar, request.getModel(), request.getResponseFormat());
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        Map<String, Object> body = buildBody(request);
        body.put("stream", true);

        boolean jsonSchemaMode = request.getResponseFormat() instanceof ResponseFormat.JsonSchema;

        return restClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    if (res.getStatusCode().isError()) {
                        throw GatewayException.upstream(res.getStatusCode().value(),
                                "Anthropic streaming error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                    }
                    // The body stream is the transport handle, released through StreamTransport:
                    // ClientHttpResponse.close() drains the body first and would wait for a stalled server, and
                    // what returns a parked read differs per HTTP client; StreamTransport knows both.
                    java.io.InputStream responseBody = res.getBody();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8));
                    return new AnthropicSseIterator(() -> StreamTransport.release(res, responseBody), reader, request.getModel(), jsonSchemaMode);
                }, false);
    }

    // -------------------------------------------------------------------------
    // Shared body builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildBody(ChatRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        String systemPrompt = null;

        for (MultimodalMessage msg : request.getMessages()) {
            if ("system".equals(msg.getRole())) {
                systemPrompt = extractText(msg);
            } else {
                messages.add(buildAnthropicMessage(msg));
            }
        }

        ResponseFormat format = request.getResponseFormat();

        // For json_object mode, append instruction to system prompt
        if (format instanceof ResponseFormat.JsonObject) {
            systemPrompt = (systemPrompt != null ? systemPrompt : "") + JSON_SYSTEM_SUFFIX;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("max_tokens", request.getMaxTokens() != null ? request.getMaxTokens() : DEFAULT_MAX_TOKENS);
        if (systemPrompt != null)         body.put("system",      systemPrompt);
        body.put("messages", messages);
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP()        != null) body.put("top_p",       request.getTopP());
        if (request.getStop()        != null) body.put("stop_sequences", request.getStop());
        if (request.getSeed() != null) {
            // The Messages API has no seed; serving the call without it would drop what was asked.
            throw new GatewayException("UNSUPPORTED_CAPABILITY",
                    "seed is not supported by Anthropic; remove it or route to a provider that accepts one");
        }

        // Relay function-calling tools in the Anthropic shape ({name, description, input_schema}).
        // The structured-output tool for json_schema mode goes into the same tools array.
        List<Map<String, Object>> tools = new ArrayList<>();
        if (request.getTools() != null) {
            for (ToolDefinition t : request.getTools()) {
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("name", t.getName());
                if (t.getDescription() != null) tool.put("description", t.getDescription());
                tool.put("input_schema", t.getParameters() != null ? t.getParameters()
                        : Map.of("type", "object"));
                tools.add(tool);
            }
        }

        // For json_schema mode, rewrite to a forced structured-output tool-use.
        if (format instanceof ResponseFormat.JsonSchema js) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", STRUCTURED_OUTPUT_TOOL);
            tool.put("description", "Respond with structured output matching the provided schema.");
            tool.put("input_schema", js.schema());
            tools.add(tool);
            body.put("tool_choice", Map.of("type", "tool", "name", STRUCTURED_OUTPUT_TOOL));
        } else if (request.getToolChoice() != null) {
            body.put("tool_choice", mapToolChoice(request.getToolChoice()));
        }

        if (!tools.isEmpty()) body.put("tools", tools);

        return body;
    }

    /**
     * Builds one Anthropic message, translating the internal tool shapes:
     * a {@code tool}-role result becomes a {@code user} message with a
     * {@code tool_result} block; an assistant message carrying tool calls becomes
     * text (if any) plus {@code tool_use} blocks. Anthropic has no {@code tool} role.
     */
    private Map<String, Object> buildAnthropicMessage(MultimodalMessage msg) {
        if ("tool".equals(msg.getRole())) {
            return Map.of("role", "user", "content", List.of(Map.of(
                    "type", "tool_result",
                    "tool_use_id", msg.getToolCallId() == null ? "" : msg.getToolCallId(),
                    "content", extractText(msg))));
        }
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            List<Object> blocks = new ArrayList<>();
            String text = extractText(msg);
            if (!text.isEmpty()) blocks.add(Map.of("type", "text", "text", text));
            for (com.dvarahq.core.model.ToolCall tc : msg.getToolCalls()) {
                blocks.add(Map.of(
                        "type", "tool_use",
                        "id", tc.getId() == null ? "" : tc.getId(),
                        "name", tc.getName() == null ? "" : tc.getName(),
                        "input", parseToolArguments(tc.getArguments())));
            }
            return Map.of("role", msg.getRole(), "content", blocks);
        }
        return Map.of("role", msg.getRole(), "content", buildContent(msg));
    }

    /** Anthropic {@code tool_use.input} is a JSON object; the internal args are a
     *  JSON string — parse them, defaulting to an empty object on any failure. */
    private Object parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            return JsonMapper.instance().readValue(arguments, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Maps an OpenAI-style {@code tool_choice} to the Anthropic shape. */
    private Object mapToolChoice(Object choice) {
        if (choice instanceof String s) {
            return switch (s) {
                case "required" -> Map.of("type", "any");
                default -> Map.of("type", "auto"); // "auto"/"none" → auto (Anthropic has no 'none')
            };
        }
        if (choice instanceof Map<?, ?> m && m.get("function") instanceof Map<?, ?> f
                && f.get("name") != null) {
            return Map.of("type", "tool", "name", f.get("name").toString());
        }
        return Map.of("type", "auto");
    }

    /** Serializes an Anthropic {@code tool_use.input} object back to the raw
     *  JSON-string form the internal {@link com.dvarahq.core.model.ToolCall} carries. */
    private String serializeToolInput(Object input) {
        if (input == null) return "{}";
        try {
            return JsonMapper.instance().writeValueAsString(input);
        } catch (Exception e) {
            return input.toString();
        }
    }

    private String extractText(MultimodalMessage msg) {
        if (msg.getContent() == null) return "";
        return msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Anthropic accepts {@code content} as either a string (text-only) or a list of typed
     * blocks. Text-only messages use the string form; any non-text block switches to typed blocks.
     */
    private Object buildContent(MultimodalMessage msg) {
        if (msg.getContent() == null || msg.getContent().isEmpty()) return "";
        boolean allText = msg.getContent().stream().allMatch(b -> b instanceof ContentBlock.TextBlock);
        if (allText) return extractText(msg);
        return msg.getContent().stream()
                .map(b -> switch (b) {
                    case ContentBlock.TextBlock tb -> Map.<String, Object>of(
                            "type", "text",
                            "text", tb.text());
                    case ContentBlock.ImageBlock ib -> Map.<String, Object>of(
                            "type", "image",
                            "source", Map.of(
                                    "type", "base64",
                                    "media_type", ib.mediaType(),
                                    "data", ib.data()));
                    // No default: the switch is exhaustive over ContentBlock's permitted kinds, so a
                    // new kind is a compile error here rather than a block silently relayed as text.
                })
                .collect(Collectors.toList());
    }

    private ChatResponse mapToInternal(AnthropicResponse ar, String model, ResponseFormat format) {
        String text;
        String finishReason;
        List<com.dvarahq.core.model.ToolCall> toolCalls = null;

        if (format instanceof ResponseFormat.JsonSchema) {
            // Extract tool_use input as JSON text
            text = ar.getContent().stream()
                    .filter(c -> "tool_use".equals(c.getType()))
                    .map(c -> {
                        try {
                            return JsonMapper.instance().writeValueAsString(c.getInput());
                        } catch (Exception e) {
                            return c.getInput() != null ? c.getInput().toString() : "";
                        }
                    })
                    .findFirst()
                    .orElse("");
            // Map tool_use stop reason to stop
            finishReason = "stop";
        } else {
            text = ar.getContent().stream()
                    .filter(c -> "text".equals(c.getType()))
                    .map(AnthropicResponse.ContentItem::getText)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.joining("\n"));
            // Relay the model's tool_use blocks back as tool calls.
            List<com.dvarahq.core.model.ToolCall> calls = ar.getContent().stream()
                    .filter(c -> "tool_use".equals(c.getType()))
                    .map(c -> com.dvarahq.core.model.ToolCall.builder()
                            .id(c.getId())
                            .name(c.getName())
                            .arguments(serializeToolInput(c.getInput()))
                            .build())
                    .toList();
            if (!calls.isEmpty()) toolCalls = calls;
            finishReason = "tool_use".equals(ar.getStopReason()) ? "tool_calls"
                    : ("end_turn".equals(ar.getStopReason()) ? "stop" : ar.getStopReason());
        }

        Map<String, String> gatewayHeaders = null;
        if (format instanceof ResponseFormat.JsonSchema js && js.strict()) {
            gatewayHeaders = Map.of("X-Gateway-Strict-Downgraded", "true");
        }

        return ChatResponse.builder()
                .id(ar.getId())
                .object("chat.completion")
                .created(Instant.now().getEpochSecond())
                .model(model)
                .choices(List.of(
                        ChatResponse.Choice.builder()
                                .index(0)
                                .message(MultimodalMessage.builder()
                                        .role("assistant")
                                        .content(List.of(new ContentBlock.TextBlock(text)))
                                        .toolCalls(toolCalls)
                                        .build())
                                .finishReason(finishReason)
                                .build()
                ))
                // Null where the upstream reported nothing: a zeroed block would claim the call
                // consumed nothing, which the metering path cannot tell from a real zero.
                .usage(ar.getUsage() == null ? null
                        : ChatResponse.Usage.builder()
                                .promptTokens(ar.getUsage().getInputTokens())
                                .completionTokens(ar.getUsage().getOutputTokens())
                                .totalTokens(ar.getUsage().getInputTokens() + ar.getUsage().getOutputTokens())
                                .build())
                .gatewayHeaders(gatewayHeaders)
                .build();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, true, true, true, false, true, 200_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("claude");
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        AnthropicModelList response = restClient.get()
                .uri("/v1/models")
                .retrieve()
                .body(AnthropicModelList.class);
        if (response == null || response.data == null) return List.of();
        return response.data.stream()
                .map(m -> new com.dvarahq.core.provider.ModelInfo(
                        m.id, "anthropic",
                        m.createdAt != null ? Instant.parse(m.createdAt).getEpochSecond() : 0L))
                .toList();
    }

    // -------------------------------------------------------------------------
    // SSE stream iterator
    // -------------------------------------------------------------------------

    // Package-private so tests can drive the parser from a literal stream.
    static class AnthropicSseIterator implements Iterator<SseChunk>, AutoCloseable, ReleasableUpstream {

        private final BufferedReader reader;
        private final AutoCloseable transport;   // the body stream; closing it bypasses the reader's lock
        private final String model;
        private final boolean jsonSchemaMode;
        private SseChunk next;
        private boolean done;
        private String messageId;
        /** A stop reason arrived (message_delta); the stream may end after it, never before. */
        private boolean finished;
        /**
         * Content-block index → tool-call index. Anthropic numbers every block, text ones
         * included, so the first tool_use block of a reply may be block 1; a consumer sees tool calls
         * numbered from zero in order of appearance, whatever their block numbers.
         */
        private final Map<Integer, Integer> toolIndexes = new LinkedHashMap<>();

        AnthropicSseIterator(BufferedReader reader, String model, boolean jsonSchemaMode) {
            this(() -> { }, reader, model, jsonSchemaMode);
        }

        AnthropicSseIterator(AutoCloseable transport, BufferedReader reader, String model, boolean jsonSchemaMode) {
            this.transport = transport;
            this.reader = reader;
            this.model = model;
            this.jsonSchemaMode = jsonSchemaMode;
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
            return result;
        }

        private SseChunk advance() {
            try {
                String currentEvent = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("event: ")) {
                        currentEvent = line.substring(7).trim();
                        continue;
                    }
                    if (!line.startsWith("data: ")) continue;
                    if (currentEvent == null) continue;

                    String data = line.substring(6).trim();
                    JsonNode node = JsonMapper.instance().readTree(data);

                    switch (currentEvent) {
                        case "message_start":
                            if (node.has("message") && node.get("message").has("id")) {
                                messageId = node.get("message").get("id").asText();
                            }
                            currentEvent = null;
                            continue;

                        case "content_block_start":
                            // A tool_use block opens a streamed tool call: its id and name arrive here,
                            // its arguments follow as input_json_delta fragments on the same block index.
                            // In JSON-schema mode the block is the structured output itself and stays text.
                            JsonNode block = node.path("content_block");
                            currentEvent = null;
                            if (jsonSchemaMode || !"tool_use".equals(block.path("type").asText(null))) {
                                continue;
                            }
                            int opened = openedToolIndex(node);
                            return SseChunk.builder()
                                    .id(messageId)
                                    .model(model)
                                    .toolCalls(List.of(ToolCallDelta.open(opened,
                                            block.path("id").asText(null), block.path("name").asText(null), null)))
                                    .done(false)
                                    .build();

                        case "content_block_delta":
                            JsonNode delta = node.path("delta");
                            String deltaType = delta.path("type").asText(null);
                            currentEvent = null;

                            if ("input_json_delta".equals(deltaType) && !jsonSchemaMode) {
                                String partial = delta.path("partial_json").asText(null);
                                if (partial == null || partial.isEmpty()) {
                                    continue;
                                }
                                return SseChunk.builder()
                                        .id(messageId)
                                        .model(model)
                                        .toolCalls(List.of(ToolCallDelta.arguments(continuedToolIndex(node), partial)))
                                        .done(false)
                                        .build();
                            }
                            String text = "input_json_delta".equals(deltaType)
                                    ? delta.path("partial_json").asText(null)
                                    : delta.path("text").asText(null);
                            return SseChunk.builder()
                                    .id(messageId)
                                    .model(model)
                                    .delta(text)
                                    .done(false)
                                    .build();

                        case "message_delta":
                            String stopReason = node.path("delta").path("stop_reason").asText(null);
                            String finishReason;
                            if (jsonSchemaMode && "tool_use".equals(stopReason)) {
                                finishReason = "stop";
                            } else if ("tool_use".equals(stopReason)) {
                                finishReason = "tool_calls"; // the same word the non-streaming path uses
                            } else {
                                finishReason = "end_turn".equals(stopReason) ? "stop" : stopReason;
                            }
                            currentEvent = null;
                            finished = true;
                            return SseChunk.builder()
                                    .id(messageId)
                                    .model(model)
                                    .delta(null)
                                    .finishReason(finishReason)
                                    .done(true)
                                    .build();

                        case "message_stop":
                            if (!finished) {
                                throw fail("Anthropic stream stopped before a stop reason; the answer is incomplete");
                            }
                            done = true;
                            closeReader();
                            currentEvent = null;
                            return null;

                        case "error":
                            // An overloaded or failing upstream reports it in-band, part-way through.
                            JsonNode error = node.path("error");
                            throw fail("Anthropic stream sent an error part-way: "
                                    + error.path("message").asText(error.path("type").asText(node.toString())));

                        default:
                            currentEvent = null;
                            continue;
                    }
                }
                if (!finished) {
                    throw fail("Anthropic stream ended before a stop reason; the answer is incomplete");
                }
                done = true;
                closeReader();
                return null;
            } catch (IOException e) {
                done = true;
                closeReader();
                throw new GatewayException("PROVIDER_ERROR",
                        "Error reading Anthropic stream: " + e.getMessage(), e);
            }
        }

        /** Close the body stream, not the reader: a read parked in readLine() holds the reader's lock. */
        @Override
        public void releaseTransport() {
            try { transport.close(); } catch (Exception ignored) { }
        }

        @Override
        public void close() {
            closeReader();
        }

        private void closeReader() {
            try { reader.close(); } catch (IOException ignored) {}
        }

        /**
         * The tool-call index for the block a tool_use opener names, assigned in order of first
         * appearance. Anthropic always sends the block index; a stream without one is not a stream this
         * decoder can attribute, and guessing would invent or merge calls.
         */
        private int openedToolIndex(JsonNode event) {
            if (!event.hasNonNull("index")) {
                throw fail("Anthropic stream opened a tool_use block without an index");
            }
            return toolIndexes.computeIfAbsent(event.get("index").asInt(), k -> toolIndexes.size());
        }

        /** The tool-call index a fragment continues; it must continue a block that was opened. */
        private int continuedToolIndex(JsonNode event) {
            Integer index = event.hasNonNull("index") ? toolIndexes.get(event.get("index").asInt()) : null;
            if (index == null) {
                throw fail("Anthropic stream sent tool-call arguments for a block that was never opened");
            }
            return index;
        }

        /** Ends the stream cleanly before a provider error propagates: no further reads, reader closed. */
        private GatewayException fail(String message) {
            done = true;
            closeReader();
            return new GatewayException("PROVIDER_ERROR", message);
        }
    }

    // -------------------------------------------------------------------------
    // Inner response DTOs (Anthropic wire format)
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AnthropicResponse {
        private String id;
        private String type;
        private String role;
        private List<ContentItem> content;
        private String model;
        @JsonProperty("stop_reason")   private String stopReason;
        @JsonProperty("stop_sequence") private String stopSequence;
        private Usage usage;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class ContentItem {
            private String type;
            private String text;
            private String id;
            private String name;
            private Object input;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Usage {
            @JsonProperty("input_tokens")  private int inputTokens;
            @JsonProperty("output_tokens") private int outputTokens;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AnthropicModelList {
        private List<AnthropicModelEntry> data;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class AnthropicModelEntry {
        private String id;
        @JsonProperty("created_at") private String createdAt;
    }
}