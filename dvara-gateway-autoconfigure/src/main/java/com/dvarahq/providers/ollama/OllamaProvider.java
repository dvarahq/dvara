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

    @Override
    public ChatResponse chat(ChatRequest request) {
        rejectUnsupportedResponseFormat(request.getResponseFormat());
        Map<String, Object> body = buildChatBody(request);

        OllamaResponse resp = restClient.post()
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "Ollama error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(OllamaResponse.class);

        return mapToInternal(resp, request.getModel());
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
                .uri("/v1/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
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
        if (format != null && !(format instanceof ResponseFormat.Text)) {
            throw new GatewayException("UNSUPPORTED_RESPONSE_FORMAT",
                    "Ollama provider does not support response_format. Supported formats: [text]");
        }
    }

    private Map<String, Object> buildChatBody(ChatRequest request) {
        rejectUnsupportedContentBlocks(request);
        List<Map<String, Object>> messages = request.getMessages().stream()
                .<Map<String, Object>>map(m -> Map.of("role", m.getRole(), "content", extractText(m)))
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", stripPrefix(request.getModel()));
        body.put("messages", messages);
        if (request.getMaxTokens()   != null) body.put("max_tokens",  request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature", request.getTemperature());
        if (request.getTopP()        != null) body.put("top_p",       request.getTopP());
        if (request.getStop() != null) body.put("stop", request.getStop());
        if (request.getSeed() != null) body.put("seed", request.getSeed());
        return body;
    }

    private void rejectUnsupportedContentBlocks(ChatRequest request) {
        for (MultimodalMessage msg : request.getMessages()) {
            if (msg.getContent() == null) continue;
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ContentBlock.ImageBlock) {
                    throw new GatewayException("UNSUPPORTED_CAPABILITY",
                            "Ollama vision is not yet implemented in DVARA. Use a vision-capable "
                            + "provider (OpenAI, Anthropic, Gemini, Bedrock, Azure OpenAI) for now.");
                }
                // No tool-block branch: a tool call travels on the message's toolCalls, not as a
                // content block, and the dispatcher keeps a request carrying tools off a provider
                // whose capabilities declare no tool support, as this one's do.
            }
        }
    }

    /** Strip the "ollama/" prefix that the routing key uses. */
    private String stripPrefix(String model) {
        return model != null && model.startsWith("ollama/") ? model.substring(7) : model;
    }

    private String extractText(MultimodalMessage msg) {
        if (msg.getContent() == null) return "";
        return msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .collect(Collectors.joining("\n"));
    }

    private ChatResponse mapToInternal(OllamaResponse resp, String model) {
        List<ChatResponse.Choice> choices = resp.getChoices().stream()
                .map(c -> ChatResponse.Choice.builder()
                        .index(c.getIndex())
                        .message(MultimodalMessage.assistant(
                                c.getMessage() != null && c.getMessage().getContent() != null
                                        ? c.getMessage().getContent() : ""))
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

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, false, false, false, false, 32_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("ollama/");
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
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
                    return mapStreamChunk(chunk);
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
            if (chunk.getChoices() != null && !chunk.getChoices().isEmpty()) {
                OllamaStreamChunk.StreamChoice choice = chunk.getChoices().get(0);
                if (choice.getDelta() != null) {
                    delta = choice.getDelta().getContent();
                }
                finishReason = choice.getFinishReason();
            }
            // Any finish reason ends the answer: "length" stops it as surely as "stop".
            boolean isDone = finishReason != null;
            return SseChunk.builder()
                    .id(chunk.getId())
                    .model(chunk.getModel() != null ? chunk.getModel() : model)
                    .delta(delta)
                    .finishReason(finishReason)
                    .done(isDone)
                    .build();
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