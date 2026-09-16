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
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "Cohere API error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
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
        rejectUnsupportedContentBlocks(request);
        List<Map<String, Object>> messages = request.getMessages().stream()
                .map(m -> {
                    Map<String, Object> msg = new LinkedHashMap<>();
                    msg.put("role", m.getRole());
                    msg.put("content", extractContent(m));
                    return msg;
                })
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("messages", messages);
        if (request.getMaxTokens()   != null) body.put("max_tokens",  request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP()        != null) body.put("p",           request.getTopP());
        if (request.getStop()        != null) body.put("stop_sequences", request.getStop());
        if (request.getSeed()        != null) body.put("seed",        request.getSeed());
        return body;
    }

    private void rejectUnsupportedContentBlocks(ChatRequest request) {
        for (MultimodalMessage msg : request.getMessages()) {
            if (msg.getContent() == null) continue;
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ContentBlock.ImageBlock) {
                    throw new GatewayException("UNSUPPORTED_CAPABILITY",
                            "Cohere vision is not yet implemented in DVARA. Use a vision-capable "
                            + "provider (OpenAI, Anthropic, Gemini, Bedrock, Azure OpenAI) for now.");
                }
                // No tool-block branch: a tool call travels on the message's toolCalls, not as a
                // content block, and the dispatcher keeps a request carrying tools off a provider
                // whose capabilities declare no tool support, as this one's do.
            }
        }
    }

    private Object extractContent(MultimodalMessage msg) {
        if (msg.getContent() == null || msg.getContent().isEmpty()) return "";
        return msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .collect(Collectors.joining("\n"));
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

        ChatResponse.Choice choice = ChatResponse.Choice.builder()
                .index(0)
                .message(MultimodalMessage.assistant(content))
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
            case "COMPLETE" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "ERROR" -> "error";
            default -> reason;
        };
    }

    /**
     * Null where the upstream reported nothing — no usage object at all, or one carrying neither
     * counted tokens nor billed units. A zeroed block would claim the call consumed nothing, which
     * the metering path cannot tell from a real zero.
     */
    private ChatResponse.Usage mapUsage(CohereUsage usage) {
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
        return new ProviderCapabilities(true, false, false, false, false, 128_000);
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
                if (event.getDelta() != null && event.getDelta().getMessage() != null
                        && event.getDelta().getMessage().getContent() != null) {
                    text = event.getDelta().getMessage().getContent().getText();
                }
                return SseChunk.builder()
                        .model(model)
                        .delta(text)
                        .done(false)
                        .build();
            } else if ("message-end".equals(event.getType())) {
                String finishReason = null;
                if (event.getDelta() != null) {
                    finishReason = mapFinishReason(event.getDelta().getFinishReason());
                }
                boolean isDone = finishReason != null;
                return SseChunk.builder()
                        .model(model)
                        .finishReason(finishReason)
                        .done(isDone)
                        .build();
            }
            // Skip other event types (e.g. message-start, content-start, content-end)
            return null;
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
        private CohereStreamContent content;
    }

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class CohereStreamContent {
        private String text;
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