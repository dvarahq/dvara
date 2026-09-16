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
package com.dvarahq.providers.bedrock;

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
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.dvarahq.core.util.JsonMapper;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

public class BedrockProvider extends AbstractLlmProvider {

    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final RestClient restClient;

    public BedrockProvider(SecretProvider secretProvider, String region, RestClient.Builder builder) {
        super("bedrock");
        String baseUrl = "https://bedrock-runtime." + region + ".amazonaws.com";
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestInterceptor(new SigV4Interceptor(secretProvider, region))
                .build();
    }

    public BedrockProvider(SecretProvider secretProvider, String region) {
        this(secretProvider, region, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    BedrockProvider(RestClient restClient) {
        super("bedrock");
        this.restClient = restClient;
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        String modelId = stripPrefix(request.getModel());
        Map<String, Object> body = buildBody(request);

        ConverseResponse resp = restClient.post()
                .uri("/model/{modelId}/converse", modelId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "Bedrock API error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(ConverseResponse.class);

        return mapToInternal(resp, request.getModel(), request.getResponseFormat());
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        String modelId = stripPrefix(request.getModel());
        Map<String, Object> body = buildBody(request);

        return restClient.post()
                .uri("/model/{modelId}/converse-stream", modelId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> openStream(res, request.getModel(),
                        request.getResponseFormat() instanceof ResponseFormat.JsonSchema), false);
    }

    /**
     * The exchange leaves the response open ({@code false} above), so whoever this returns to
     * owns closing it: the iterator on success, and this method itself on an HTTP error, since no
     * iterator exists then and the controller has nothing it could close.
     */
    static Iterator<SseChunk> openStream(org.springframework.http.client.ClientHttpResponse res,
                                         String model, boolean jsonSchemaMode) throws IOException {
        boolean handedOver = false;
        try {
            int status = res.getStatusCode().value();
            if (res.getStatusCode().isError()) {
                throw GatewayException.upstream(status,
                        "Bedrock streaming error " + status + GatewayException.describeHttpStatus(status));
            }
            // The body is Amazon Event Stream, binary frames, not SSE.
            java.io.InputStream responseBody = res.getBody();
            // Released through StreamTransport, not res::close: the response's close drains the body
            // first, which on a stalled stream waits for the server.
            BedrockStreamIterator iterator = new BedrockStreamIterator(responseBody, () -> StreamTransport.release(res, responseBody), model, jsonSchemaMode);
            handedOver = true;
            return iterator;
        } finally {
            // Reading the status or the body can throw too; until the iterator exists nobody else
            // holds the response, so every exit but the hand-off closes it here.
            if (!handedOver) {
                try {
                    res.close();
                } catch (RuntimeException ignored) {
                    // nothing to do with a response we are already refusing
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Shared body builder
    // -------------------------------------------------------------------------

    private static final String JSON_SYSTEM_SUFFIX = "\n\nRespond with valid JSON only. Do not include any text outside the JSON object.";
    private static final String STRUCTURED_OUTPUT_TOOL = "structured_output";

    private Map<String, Object> buildBody(ChatRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        List<Map<String, String>> systemMessages = new ArrayList<>();

        for (MultimodalMessage msg : request.getMessages()) {
            if ("system".equals(msg.getRole())) {
                systemMessages.add(Map.of("text", extractText(msg)));
            } else {
                messages.add(buildBedrockMessage(msg));
            }
        }

        ResponseFormat format = request.getResponseFormat();

        // For json_object mode, inject system message
        if (format instanceof ResponseFormat.JsonObject) {
            systemMessages.add(Map.of("text", JSON_SYSTEM_SUFFIX.trim()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        if (!systemMessages.isEmpty()) body.put("system", systemMessages);

        Map<String, Object> inferenceConfig = new LinkedHashMap<>();
        if (request.getMaxTokens() != null) inferenceConfig.put("maxTokens", request.getMaxTokens());
        if (request.getTemperature() != null) inferenceConfig.put("temperature", request.getTemperature());
        if (request.getTopP() != null) inferenceConfig.put("topP", request.getTopP());
        if (request.getStop() != null) inferenceConfig.put("stopSequences", request.getStop());
        if (request.getSeed() != null) {
            // Converse has no seed; serving the call without it would drop what was asked.
            throw new GatewayException("UNSUPPORTED_CAPABILITY",
                    "seed is not supported by Bedrock; remove it or route to a provider that accepts one");
        }
        if (!inferenceConfig.isEmpty()) body.put("inferenceConfig", inferenceConfig);

        // Relay function-calling tools in the Bedrock Converse toolConfig shape
        // ({toolSpec: {name, description, inputSchema: {json}}}). The structured-output tool for
        // json_schema mode goes into the same array.
        List<Map<String, Object>> tools = new ArrayList<>();
        if (request.getTools() != null) {
            for (ToolDefinition t : request.getTools()) {
                Map<String, Object> toolSpec = new LinkedHashMap<>();
                toolSpec.put("name", t.getName());
                if (t.getDescription() != null) toolSpec.put("description", t.getDescription());
                toolSpec.put("inputSchema", Map.of("json", t.getParameters() != null
                        ? t.getParameters() : Map.of("type", "object")));
                tools.add(Map.of("toolSpec", toolSpec));
            }
        }

        Object toolChoice = null;
        if (format instanceof ResponseFormat.JsonSchema js) {
            Map<String, Object> toolSpec = new LinkedHashMap<>();
            toolSpec.put("name", STRUCTURED_OUTPUT_TOOL);
            toolSpec.put("description", "Respond with structured output matching the provided schema.");
            toolSpec.put("inputSchema", Map.of("json", js.schema()));
            tools.add(Map.of("toolSpec", toolSpec));
            toolChoice = Map.of("tool", Map.of("name", STRUCTURED_OUTPUT_TOOL));
        } else if (request.getToolChoice() != null) {
            toolChoice = mapToolChoice(request.getToolChoice());
        }

        if (!tools.isEmpty()) {
            Map<String, Object> toolConfig = new LinkedHashMap<>();
            toolConfig.put("tools", tools);
            if (toolChoice != null) toolConfig.put("toolChoice", toolChoice);
            body.put("toolConfig", toolConfig);
        }

        return body;
    }

    /**
     * Builds one Bedrock Converse message, translating the internal tool shapes:
     * a {@code tool}-role result becomes a {@code user} message with a
     * {@code toolResult} block; an assistant message's tool calls become
     * {@code toolUse} blocks (appended in {@link #buildContentBlocks}).
     */
    private Map<String, Object> buildBedrockMessage(MultimodalMessage msg) {
        if ("tool".equals(msg.getRole())) {
            return Map.of("role", "user", "content", List.of(Map.of(
                    "toolResult", Map.of(
                            "toolUseId", msg.getToolCallId() == null ? "" : msg.getToolCallId(),
                            "content", List.of(Map.of("text", extractText(msg)))))));
        }
        return Map.of("role", msg.getRole(), "content", buildContentBlocks(msg));
    }

    /** Maps an OpenAI-style {@code tool_choice} to the Bedrock Converse shape. */
    private Object mapToolChoice(Object choice) {
        if (choice instanceof String s) {
            return switch (s) {
                case "required" -> Map.of("any", Map.of());
                default -> Map.of("auto", Map.of()); // "auto"/"none" → auto (Bedrock has no 'none')
            };
        }
        if (choice instanceof Map<?, ?> m && m.get("function") instanceof Map<?, ?> f
                && f.get("name") != null) {
            return Map.of("tool", Map.of("name", f.get("name").toString()));
        }
        return Map.of("auto", Map.of());
    }

    /** Bedrock {@code toolUse.input} is a JSON object; parse the internal
     *  JSON-string args, defaulting to an empty object on failure. */
    private Object parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            return JsonMapper.instance().readValue(arguments, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Serializes a Bedrock {@code toolUse.input} object to the internal
     *  JSON-string form the {@link com.dvarahq.core.model.ToolCall} carries. */
    private String serializeToolInput(Object input) {
        if (input == null) return "{}";
        try {
            return JsonMapper.instance().writeValueAsString(input);
        } catch (Exception e) {
            return input.toString();
        }
    }

    /** Strip the "bedrock/" routing prefix before sending the model ID to Bedrock. */
    private String stripPrefix(String model) {
        return model != null && model.startsWith("bedrock/") ? model.substring(8) : model;
    }

    private String extractText(MultimodalMessage msg) {
        if (msg.getContent() == null) return "";
        return msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Build a Bedrock Converse {@code content} array. Text blocks become
     * {@code {text: "..."}} entries; image blocks become
     * {@code {image: {format, source: {bytes: <base64>}}}} entries (Bedrock's
     * native vision envelope). The Converse API's {@code format} field expects
     * the bare subtype ("png", "jpeg", "gif", "webp"), so the {@code image/}
     * MIME-type prefix is stripped.
     */
    private List<Map<String, Object>> buildContentBlocks(MultimodalMessage msg) {
        List<Map<String, Object>> blocks = new ArrayList<>();
        if (msg.getContent() != null) {
            for (ContentBlock b : msg.getContent()) {
                blocks.add(switch (b) {
                    case ContentBlock.TextBlock tb -> Map.<String, Object>of("text", tb.text());
                    case ContentBlock.ImageBlock ib -> Map.<String, Object>of(
                            "image", Map.of(
                                    "format", stripImageMimePrefix(ib.mediaType()),
                                    "source", Map.of("bytes", ib.data())));
                    // No default: the switch is exhaustive over ContentBlock's permitted kinds, so a
                    // new kind is a compile error here rather than a block silently relayed as text.
                });
            }
        }
        // An assistant message's tool calls become Bedrock toolUse blocks.
        if (msg.getToolCalls() != null) {
            for (com.dvarahq.core.model.ToolCall tc : msg.getToolCalls()) {
                blocks.add(Map.of("toolUse", Map.of(
                        "toolUseId", tc.getId() == null ? "" : tc.getId(),
                        "name", tc.getName() == null ? "" : tc.getName(),
                        "input", parseToolArguments(tc.getArguments()))));
            }
        }
        if (blocks.isEmpty()) blocks.add(Map.of("text", ""));
        return blocks;
    }

    private static String stripImageMimePrefix(String mediaType) {
        if (mediaType == null) return "png";
        return mediaType.startsWith("image/") ? mediaType.substring(6) : mediaType;
    }

    private ChatResponse mapToInternal(ConverseResponse resp, String model, ResponseFormat format) {
        String text = "";
        String finishReason;
        List<com.dvarahq.core.model.ToolCall> toolCalls = null;

        if (format instanceof ResponseFormat.JsonSchema) {
            // Extract toolUse input as JSON text
            if (resp.getOutput() != null && resp.getOutput().getMessage() != null
                    && resp.getOutput().getMessage().getContent() != null) {
                text = resp.getOutput().getMessage().getContent().stream()
                        .filter(c -> c.getToolUse() != null)
                        .map(c -> {
                            try {
                                return JsonMapper.instance().writeValueAsString(c.getToolUse().getInput());
                            } catch (Exception e) {
                                return c.getToolUse().getInput() != null ? c.getToolUse().getInput().toString() : "";
                            }
                        })
                        .findFirst()
                        .orElse("");
            }
            finishReason = "stop";
        } else {
            if (resp.getOutput() != null && resp.getOutput().getMessage() != null
                    && resp.getOutput().getMessage().getContent() != null
                    && !resp.getOutput().getMessage().getContent().isEmpty()) {
                text = resp.getOutput().getMessage().getContent().stream()
                        .map(ConverseResponse.ContentItem::getText)
                        .filter(t -> t != null)
                        .collect(Collectors.joining());
                // Relay the model's toolUse blocks back as tool calls.
                List<com.dvarahq.core.model.ToolCall> calls = resp.getOutput().getMessage().getContent().stream()
                        .filter(c -> c.getToolUse() != null)
                        .map(c -> com.dvarahq.core.model.ToolCall.builder()
                                .id(c.getToolUse().getToolUseId())
                                .name(c.getToolUse().getName())
                                .arguments(serializeToolInput(c.getToolUse().getInput()))
                                .build())
                        .toList();
                if (!calls.isEmpty()) toolCalls = calls;
            }
            finishReason = "tool_use".equals(resp.getStopReason()) ? "tool_calls"
                    : mapStopReason(resp.getStopReason());
        }

        Map<String, String> gatewayHeaders = null;
        if (format instanceof ResponseFormat.JsonSchema js && js.strict()) {
            gatewayHeaders = Map.of("X-Gateway-Strict-Downgraded", "true");
        }

        // Null, not a zeroed block: a zeroed one cannot be told apart from a call that really cost
        // nothing, and the metering path drops a response whose total is not positive.
        ChatResponse.Usage usage = null;
        if (resp.getUsage() != null) {
            int input = resp.getUsage().getInputTokens();
            int output = resp.getUsage().getOutputTokens();
            usage = ChatResponse.Usage.builder()
                    .promptTokens(input)
                    .completionTokens(output)
                    .totalTokens(input + output)
                    .build();
        }

        return ChatResponse.builder()
                .id("bedrock-" + Instant.now().toEpochMilli())
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
                .usage(usage)
                .gatewayHeaders(gatewayHeaders)
                .build();
    }

    private static String mapStopReason(String bedrockReason) {
        if (bedrockReason == null) return null;
        return switch (bedrockReason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "content_filtered" -> "content_filter";
            default -> bedrockReason;
        };
    }

    // -------------------------------------------------------------------------
    // Capabilities / routing
    // -------------------------------------------------------------------------

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, true, true, true, false, true, 200_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("bedrock/");
    }

    // -------------------------------------------------------------------------
    // ConverseStream iterator (Amazon Event Stream frames)
    // -------------------------------------------------------------------------

    /**
     * Turns ConverseStream frames into {@link SseChunk}s.
     *
     * <p>The framing is binary event stream, decoded by {@link BedrockEventStreamDecoder}. The
     * event's name is in the frame's {@code :event-type} header and the payload is the bare
     * structure, {@code {"delta":{"text":"Hi"},"contentBlockIndex":0}}, not wrapped under the event
     * name the way an AWS SDK presents it. {@code metadata}, which carries the exact usage, arrives
     * <em>after</em> {@code messageStop}, so the final chunk is emitted on {@code metadata} with the
     * finish reason saved at {@code messageStop}; a consumer stops at the first {@code done} chunk
     * and would otherwise never see the usage.
     *
     * <p>Owns the response: closed on exhaustion, on error, and on an explicit {@link #close()},
     * once.
     */
    static class BedrockStreamIterator implements Iterator<SseChunk>, AutoCloseable, ReleasableUpstream {

        private final BedrockEventStreamDecoder decoder;
        private final AutoCloseable response;
        private final String model;
        private final boolean jsonSchemaMode;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private SseChunk next;
        private boolean done;
        private String finishReason;
        private boolean stopped;
        /** contentBlockIndex → tool-call index; Bedrock numbers text blocks too, callers see tools from zero. */
        private final Map<Integer, Integer> toolIndexes = new LinkedHashMap<>();

        BedrockStreamIterator(java.io.InputStream body, AutoCloseable response, String model, boolean jsonSchemaMode) {
            this.decoder = new BedrockEventStreamDecoder(body);
            this.response = response;
            this.model = model;
            this.jsonSchemaMode = jsonSchemaMode;
        }

        @Override
        public boolean hasNext() {
            if (next != null) return true;
            if (done) return false;
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
                BedrockEventStreamDecoder.Frame frame;
                while ((frame = decoder.next()) != null) {
                    if (frame.isException()) {
                        throw providerError("Bedrock stream " + frame.exceptionType() + ": " + message(frame));
                    }
                    if (frame.isError()) {
                        throw providerError("Bedrock stream error " + frame.errorCode() + ": " + frame.errorMessage());
                    }
                    if (!frame.isEvent() || frame.eventType() == null) {
                        continue;
                    }
                    switch (frame.eventType()) {
                        case "contentBlockStart" -> {
                            // A toolUse block opens a streamed tool call: id and name here, the
                            // arguments as toolUse.input fragments on the same contentBlockIndex. In
                            // JSON-schema mode the block is the structured output and stays text.
                            JsonNode start = JsonMapper.instance().readTree(frame.payload());
                            JsonNode toolUse = start.path("start").path("toolUse");
                            if (!jsonSchemaMode && !toolUse.isMissingNode()) {
                                int opened = openedToolIndex(start);
                                return SseChunk.builder().id("bedrock-stream").model(model)
                                        .toolCalls(List.of(ToolCallDelta.open(opened,
                                                toolUse.path("toolUseId").asText(null), toolUse.path("name").asText(null), null)))
                                        .done(false).build();
                            }
                        }
                        case "contentBlockDelta" -> {
                            JsonNode event = JsonMapper.instance().readTree(frame.payload());
                            JsonNode delta = event.path("delta");
                            if (!jsonSchemaMode && delta.has("toolUse")) {
                                String input = delta.path("toolUse").path("input").asText(null);
                                if (input != null && !input.isEmpty()) {
                                    return SseChunk.builder().id("bedrock-stream").model(model)
                                            .toolCalls(List.of(ToolCallDelta.arguments(continuedToolIndex(event), input)))
                                            .done(false).build();
                                }
                                continue;
                            }
                            String text = jsonSchemaMode && delta.has("toolUse")
                                    ? delta.path("toolUse").path("input").asText(null)
                                    : delta.path("text").asText(null);
                            if (text != null) {
                                return SseChunk.builder().id("bedrock-stream").model(model).delta(text).done(false).build();
                            }
                        }
                        case "messageStop" -> {
                            String stopReason = JsonMapper.instance().readTree(frame.payload()).path("stopReason").asText(null);
                            finishReason = jsonSchemaMode && "tool_use".equals(stopReason) ? "stop"
                                    : "tool_use".equals(stopReason) ? "tool_calls" // as the non-streaming path says it
                                    : mapStopReason(stopReason);
                            stopped = true;
                        }
                        case "metadata" -> {
                            if (!stopped) {
                                throw providerError("Bedrock stream sent metadata before messageStop");
                            }
                            JsonNode usage = JsonMapper.instance().readTree(frame.payload()).path("usage");
                            return finalChunk(usage.isMissingNode() ? null : ChatResponse.Usage.builder()
                                    .promptTokens(usage.path("inputTokens").asInt())
                                    .completionTokens(usage.path("outputTokens").asInt())
                                    .totalTokens(usage.path("totalTokens").asInt())
                                    .build());
                        }
                        default -> { }   // messageStart, contentBlockStop: nothing to emit
                    }
                }
                // Clean end. A stream that stopped but never sent metadata still owes its consumer
                // the final chunk. One that never reached messageStop is incomplete (an empty 200
                // body, a connection dropped between frames) and must not pass as a short answer.
                if (!stopped) {
                    throw providerError("Bedrock stream ended before messageStop");
                }
                return finalChunk(null);
            } catch (IOException e) {
                finish(null);
                throw new GatewayException("PROVIDER_ERROR", "Error reading Bedrock stream: " + e.getMessage(), e);
            } catch (GatewayException e) {
                finish(null);
                throw e;
            }
        }

        /**
         * The tool-call index for the block a toolUse opener names, assigned in order of first
         * appearance. Bedrock always sends contentBlockIndex; a stream without one cannot be attributed,
         * and guessing would invent or merge calls.
         */
        private int openedToolIndex(JsonNode event) {
            if (!event.hasNonNull("contentBlockIndex")) {
                throw providerError("Bedrock stream opened a toolUse block without a contentBlockIndex");
            }
            return toolIndexes.computeIfAbsent(event.get("contentBlockIndex").asInt(), k -> toolIndexes.size());
        }

        /** The tool-call index a fragment continues; it must continue a block that was opened. */
        private int continuedToolIndex(JsonNode event) {
            Integer index = event.hasNonNull("contentBlockIndex")
                    ? toolIndexes.get(event.get("contentBlockIndex").asInt()) : null;
            if (index == null) {
                throw providerError("Bedrock stream sent toolUse input for a block that was never opened");
            }
            return index;
        }

        private SseChunk finalChunk(ChatResponse.Usage usage) {
            return finish(SseChunk.builder().id("bedrock-stream").model(model).delta(null)
                    .finishReason(finishReason != null ? finishReason : "stop").usage(usage).done(true).build());
        }

        private SseChunk finish(SseChunk last) {
            done = true;
            close();
            return last;
        }

        private static GatewayException providerError(String message) {
            return new GatewayException("PROVIDER_ERROR", message);
        }

        private static String message(BedrockEventStreamDecoder.Frame frame) {
            try {
                return JsonMapper.instance().readTree(frame.payload()).path("message").asText("(no message)");
            } catch (IOException e) {
                return "(unreadable message)";
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try { response.close(); } catch (Exception ignored) { }
            }
        }

        /** The response is what this iterator holds, so releasing the transport is closing it. */
        @Override
        public void releaseTransport() {
            close();
        }
    }

    // -------------------------------------------------------------------------
    // AWS SigV4 request signing interceptor
    // -------------------------------------------------------------------------

    static class SigV4Interceptor implements ClientHttpRequestInterceptor {

        private final SecretProvider secretProvider;
        private final String region;

        SigV4Interceptor(SecretProvider secretProvider, String region) {
            this.secretProvider = secretProvider;
            this.region = region;
        }

        @Override
        public org.springframework.http.client.ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request,
                byte[] body,
                org.springframework.http.client.ClientHttpRequestExecution execution) throws IOException {
            try {
                String workspaceId = com.dvarahq.providers.support.CredentialInterceptor.resolveWorkspaceId(); // static import not used due to inner class scope
                String accessKey = secretProvider.requireSecret("provider.bedrock.access-key", workspaceId);
                String secretKey = secretProvider.requireSecret("provider.bedrock.secret-key", workspaceId);
                // A report about a leaked AWS key names its access key id, so the call is attributed to that.
                com.dvarahq.providers.support.CredentialInterceptor.recordFingerprint(accessKey);

                Instant now = Instant.now();
                String dateStamp = DATE_STAMP.format(now);
                String amzDate = AMZ_DATE.format(now);
                String service = "bedrock";

                URI uri = request.getURI();
                String host = uri.getHost();
                String path = uri.getPath();
                String method = request.getMethod().name();

                String payloadHash = sha256Hex(body);

                request.getHeaders().set("x-amz-date", amzDate);
                request.getHeaders().set("x-amz-content-sha256", payloadHash);
                if (request.getHeaders().getFirst("Host") == null) {
                    request.getHeaders().set("Host", host);
                }

                String signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
                String canonicalHeaders =
                        "content-type:" + request.getHeaders().getFirst("Content-Type") + "\n" +
                        "host:" + host + "\n" +
                        "x-amz-content-sha256:" + payloadHash + "\n" +
                        "x-amz-date:" + amzDate + "\n";

                String canonicalRequest = method + "\n" + path + "\n" + "\n" +
                        canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;

                String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
                String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + credentialScope + "\n" +
                        sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));

                byte[] signingKey = getSignatureKey(secretKey, dateStamp, region, service);
                String signature = hexEncode(hmacSha256(signingKey, stringToSign));

                String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope +
                        ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

                request.getHeaders().set("Authorization", authorization);
            } catch (Exception e) {
                throw new IOException("SigV4 signing failed", e);
            }

            return execution.execute(request, body);
        }

        private static byte[] hmacSha256(byte[] key, String data) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        }

        private static byte[] getSignatureKey(String key, String dateStamp, String region, String service) throws Exception {
            byte[] kSecret = ("AWS4" + key).getBytes(StandardCharsets.UTF_8);
            byte[] kDate = hmacSha256(kSecret, dateStamp);
            byte[] kRegion = hmacSha256(kDate, region);
            byte[] kService = hmacSha256(kRegion, service);
            return hmacSha256(kService, "aws4_request");
        }

        private static String sha256Hex(byte[] data) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return hexEncode(md.digest(data));
            } catch (Exception e) {
                throw new RuntimeException("SHA-256 not available", e);
            }
        }

        private static String hexEncode(byte[] bytes) {
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Inner response DTOs (Bedrock Converse wire format)
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class ConverseResponse {
        private Output output;
        private String stopReason;
        private ConversUsage usage;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Output {
            private OutputMessage message;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class OutputMessage {
            private String role;
            private List<ContentItem> content;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class ContentItem {
            private String text;
            private ToolUseItem toolUse;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class ToolUseItem {
            private String toolUseId;
            private String name;
            private Object input;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class ConversUsage {
            private int inputTokens;
            private int outputTokens;
            private int totalTokens;
        }
    }
}