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
package com.dvarahq.providers.gemini;

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
import static com.dvarahq.providers.support.CredentialInterceptor.recordFingerprint;
import static com.dvarahq.providers.support.CredentialInterceptor.resolveWorkspaceId;
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

public class GeminiProvider extends AbstractLlmProvider {

    private final RestClient restClient;
    private final SecretProvider secretProvider;

    public GeminiProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("gemini");
        this.secretProvider = secretProvider;
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }

    public GeminiProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    GeminiProvider(RestClient restClient) {
        super("gemini");
        this.secretProvider = key -> java.util.Optional.of("test-key");
        this.restClient = restClient;
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        Map<String, Object> body = buildBody(request);

        String apiKey = secretProvider.requireSecret("provider.gemini.api-key", resolveWorkspaceId());
        recordFingerprint(apiKey);
        GeminiResponse resp = restClient.post()
                .uri("/v1beta/models/{model}:generateContent?key={key}",
                        request.getModel(), apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "Gemini API error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(GeminiResponse.class);

        return mapToInternal(resp, request.getModel());
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        Map<String, Object> body = buildBody(request);

        String apiKey = secretProvider.requireSecret("provider.gemini.api-key", resolveWorkspaceId());
        recordFingerprint(apiKey);
        return restClient.post()
                .uri("/v1beta/models/{model}:streamGenerateContent?alt=sse&key={key}",
                        request.getModel(), apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    if (res.getStatusCode().isError()) {
                        throw GatewayException.upstream(res.getStatusCode().value(),
                                "Gemini streaming error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                    }
                    // The body stream is the transport handle, released through StreamTransport:
                    // ClientHttpResponse.close() drains the body first and would wait for a stalled server, and
                    // what returns a parked read differs per HTTP client; StreamTransport knows both.
                    java.io.InputStream responseBody = res.getBody();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8));
                    return new GeminiSseIterator(() -> StreamTransport.release(res, responseBody), reader, request.getModel());
                }, false);
    }

    // -------------------------------------------------------------------------
    // Shared body builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildBody(ChatRequest request) {
        List<Map<String, Object>> contents = new ArrayList<>();
        String systemText = null;

        // A tool result names the id of the call it answers, and Gemini wants the function name. A non-streamed
        // call's id is its name already; a streamed call gets a minted id, so the name comes from the assistant
        // turn that made the call.
        Map<String, String> callNames = new java.util.HashMap<>();
        for (MultimodalMessage m : request.getMessages()) {
            if (m.getToolCalls() == null) continue;
            for (com.dvarahq.core.model.ToolCall call : m.getToolCalls()) {
                if (call.getId() != null && call.getName() != null) callNames.put(call.getId(), call.getName());
            }
        }

        for (MultimodalMessage msg : request.getMessages()) {
            if ("system".equals(msg.getRole())) {
                systemText = extractText(msg);
            } else if ("tool".equals(msg.getRole())) {
                // A tool result is a Gemini functionResponse (role "user"), correlated by function
                // name, since Gemini has no tool-call id.
                String callId = msg.getToolCallId();
                contents.add(Map.of("role", "user", "parts", List.of(Map.of(
                        "functionResponse", Map.of(
                                "name", callId == null ? "" : callNames.getOrDefault(callId, callId),
                                "response", Map.of("result", extractText(msg)))))));
            } else {
                String geminiRole = "assistant".equals(msg.getRole()) ? "model" : msg.getRole();
                contents.add(Map.of(
                        "role", geminiRole,
                        "parts", buildParts(msg)
                ));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", contents);

        if (systemText != null) {
            body.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", systemText))
            ));
        }

        // Relay tools as Gemini functionDeclarations plus a functionCallingConfig mode.
        List<Map<String, Object>> functionDeclarations = new ArrayList<>();
        if (request.getTools() != null) {
            for (ToolDefinition t : request.getTools()) {
                Map<String, Object> fd = new LinkedHashMap<>();
                fd.put("name", t.getName());
                if (t.getDescription() != null) fd.put("description", t.getDescription());
                if (t.getParameters() != null) fd.put("parameters", t.getParameters());
                functionDeclarations.add(fd);
            }
        }
        if (!functionDeclarations.isEmpty()) {
            body.put("tools", List.of(Map.of("functionDeclarations", functionDeclarations)));
            if (request.getToolChoice() != null) {
                body.put("toolConfig", Map.of("functionCallingConfig",
                        Map.of("mode", mapGeminiToolMode(request.getToolChoice()))));
            }
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (request.getMaxTokens() != null) generationConfig.put("maxOutputTokens", request.getMaxTokens());
        if (request.getTemperature() != null) generationConfig.put("temperature", request.getTemperature());
        if (request.getTopP() != null) generationConfig.put("topP", request.getTopP());
        if (request.getStop() != null) generationConfig.put("stopSequences", request.getStop());
        if (request.getSeed() != null) generationConfig.put("seed", request.getSeed());
        applyResponseFormat(generationConfig, request.getResponseFormat());
        if (!generationConfig.isEmpty()) body.put("generationConfig", generationConfig);

        return body;
    }

    /** Maps an OpenAI-style {@code tool_choice} to a Gemini functionCallingConfig mode. */
    private static String mapGeminiToolMode(Object choice) {
        if (choice instanceof String s) {
            return switch (s) {
                case "required" -> "ANY";
                case "none" -> "NONE";
                default -> "AUTO";
            };
        }
        // A specific-tool object → force a call.
        return choice instanceof Map ? "ANY" : "AUTO";
    }

    private void applyResponseFormat(Map<String, Object> generationConfig, ResponseFormat format) {
        if (format == null || format instanceof ResponseFormat.Text) return;
        if (format instanceof ResponseFormat.JsonObject) {
            generationConfig.put("responseMimeType", "application/json");
        } else if (format instanceof ResponseFormat.JsonSchema js) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseSchema", js.schema());
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
     * Build a Gemini {@code parts} array from the message's content blocks. Text
     * blocks become {@code {text: "..."}} parts; image blocks become
     * {@code {inlineData: {mimeType, data}}} parts (Gemini's native vision
     * envelope). An empty content list collapses to a single empty-text part to
     * keep Gemini's "every content has at least one part" invariant.
     */
    private List<Map<String, Object>> buildParts(MultimodalMessage msg) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (msg.getContent() != null) {
            for (ContentBlock b : msg.getContent()) {
                parts.add(switch (b) {
                    case ContentBlock.TextBlock tb -> Map.<String, Object>of("text", tb.text());
                    case ContentBlock.ImageBlock ib -> Map.<String, Object>of(
                            "inlineData", Map.of(
                                    "mimeType", ib.mediaType(),
                                    "data", ib.data()));
                    // No default: the switch is exhaustive over ContentBlock's permitted kinds, so a
                    // new kind is a compile error here rather than a block silently relayed as text.
                });
            }
        }
        // An assistant message's tool calls become Gemini functionCall parts.
        if (msg.getToolCalls() != null) {
            for (com.dvarahq.core.model.ToolCall tc : msg.getToolCalls()) {
                parts.add(Map.of("functionCall", Map.of(
                        "name", tc.getName() == null ? "" : tc.getName(),
                        "args", parseToolArguments(tc.getArguments()))));
            }
        }
        if (parts.isEmpty()) parts.add(Map.of("text", ""));
        return parts;
    }

    /** Gemini {@code functionCall.args} is a JSON object; parse the internal
     *  JSON-string args, defaulting to an empty object on failure. */
    private Object parseToolArguments(String arguments) {
        if (arguments == null || arguments.isBlank()) return Map.of();
        try {
            return JsonMapper.instance().readValue(arguments, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ChatResponse mapToInternal(GeminiResponse resp, String model) {
        String text = "";
        String finishReason = null;
        List<com.dvarahq.core.model.ToolCall> toolCalls = null;

        if (resp.getCandidates() != null && !resp.getCandidates().isEmpty()) {
            GeminiResponse.Candidate candidate = resp.getCandidates().get(0);
            if (candidate.getContent() != null && candidate.getContent().getParts() != null) {
                text = candidate.getContent().getParts().stream()
                        .map(GeminiResponse.Part::getText)
                        .filter(t -> t != null)
                        .collect(Collectors.joining());
                // Relay Gemini functionCall parts back as tool calls. Gemini has no call id, so the
                // name doubles as the id for round-tripping.
                List<com.dvarahq.core.model.ToolCall> calls = candidate.getContent().getParts().stream()
                        .filter(p -> p.getFunctionCall() != null)
                        .map(p -> com.dvarahq.core.model.ToolCall.builder()
                                .id(p.getFunctionCall().getName())
                                .name(p.getFunctionCall().getName())
                                .arguments(serializeToolArgs(p.getFunctionCall().getArgs()))
                                .build())
                        .toList();
                if (!calls.isEmpty()) toolCalls = calls;
            }
            finishReason = toolCalls != null ? "tool_calls" : mapFinishReason(candidate.getFinishReason());
        }

        // Null unless the response carried usageMetadata: a zeroed block would claim the call
        // consumed nothing.
        ChatResponse.Usage usage = null;
        if (resp.getUsageMetadata() != null) {
            int prompt = resp.getUsageMetadata().getPromptTokenCount();
            int completion = resp.getUsageMetadata().getCandidatesTokenCount();
            usage = ChatResponse.Usage.builder()
                    .promptTokens(prompt)
                    .completionTokens(completion)
                    .totalTokens(prompt + completion)
                    .build();
        }

        return ChatResponse.builder()
                .id("gemini-" + Instant.now().toEpochMilli())
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
                .build();
    }

    /** Serializes a Gemini {@code functionCall.args} object to the internal
     *  JSON-string form. */
    private String serializeToolArgs(Object args) {
        if (args == null) return "{}";
        try {
            return JsonMapper.instance().writeValueAsString(args);
        } catch (Exception e) {
            return args.toString();
        }
    }

    private static String mapFinishReason(String geminiReason) {
        if (geminiReason == null) return null;
        return switch (geminiReason) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY" -> "content_filter";
            default -> geminiReason.toLowerCase();
        };
    }

    // -------------------------------------------------------------------------
    // Capabilities / routing
    // -------------------------------------------------------------------------

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, true, true, true, false, true, 1_000_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("gemini");
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        String apiKey = secretProvider.requireSecret("provider.gemini.api-key", resolveWorkspaceId());
        GeminiModelList response = restClient.get()
                .uri("/v1beta/models?key={key}", apiKey)
                .retrieve()
                .body(GeminiModelList.class);
        if (response == null || response.models == null) return List.of();
        return response.models.stream()
                .map(m -> {
                    // Gemini returns "models/gemini-1.5-pro", strip the prefix
                    String id = m.name != null && m.name.startsWith("models/")
                            ? m.name.substring("models/".length()) : m.name;
                    return new com.dvarahq.core.provider.ModelInfo(id, "google", 0L);
                })
                .toList();
    }

    // -------------------------------------------------------------------------
    // SSE stream iterator
    // -------------------------------------------------------------------------

    private static class GeminiSseIterator implements Iterator<SseChunk>, AutoCloseable, ReleasableUpstream {

        private final BufferedReader reader;
        private final AutoCloseable transport;   // the body stream; closing it bypasses the reader's lock
        private final String model;
        private SseChunk next;
        private boolean done;
        /**
         * Gemini streams a function call whole, in one part, and sends no call id of its own on most
         * models. Each call is one fragment; the id is the part's when it has one, else one
         * minted here — unique within the response and stable for it, never the function name, which
         * two calls can share.
         */
        private int toolCalls;
        private final String callIdPrefix = "gemini-call-"
                + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong()) + "-";

        GeminiSseIterator(BufferedReader reader, String model) {
            this(() -> { }, reader, model);
        }

        GeminiSseIterator(AutoCloseable transport, BufferedReader reader, String model) {
            this.transport = transport;
            this.reader = reader;
            this.model = model;
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
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith(":")) continue;
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();

                    GeminiResponse chunk = JsonMapper.instance().readValue(data, GeminiResponse.class);

                    StringBuilder text = null;
                    String finishReason = null;
                    List<ToolCallDelta> calls = null;

                    if (chunk.getCandidates() != null && !chunk.getCandidates().isEmpty()) {
                        GeminiResponse.Candidate candidate = chunk.getCandidates().get(0);
                        if (candidate.getContent() != null && candidate.getContent().getParts() != null) {
                            for (GeminiResponse.Part part : candidate.getContent().getParts()) {
                                if (part.getText() != null) {
                                    // parts[] is ordered and may mix text and calls; every text part
                                    // is the answer, in order, not only the first.
                                    text = text == null ? new StringBuilder(part.getText()) : text.append(part.getText());
                                }
                                if (part.getFunctionCall() != null) {
                                    GeminiResponse.Part.FunctionCall fc = part.getFunctionCall();
                                    int index = toolCalls++;
                                    String id = fc.getId() != null && !fc.getId().isEmpty() ? fc.getId() : callIdPrefix + index;
                                    String args = fc.getArgs() == null ? null
                                            : JsonMapper.instance().writeValueAsString(fc.getArgs());
                                    if (calls == null) {
                                        calls = new ArrayList<>();
                                    }
                                    calls.add(ToolCallDelta.open(index, id, fc.getName(), args));
                                }
                            }
                        }
                        finishReason = mapFinishReason(candidate.getFinishReason());
                        if (toolCalls > 0 && "stop".equals(finishReason)) {
                            finishReason = "tool_calls"; // as the non-streaming path says it
                        }
                    }

                    // Any finish reason ends the answer: MAX_TOKENS and SAFETY stop it as surely as STOP.
                    boolean isDone = finishReason != null;
                    if (isDone) {
                        done = true;
                    }

                    return SseChunk.builder()
                            .id("gemini-stream")
                            .model(model)
                            .delta(text == null ? null : text.toString())
                            .toolCalls(calls)
                            .finishReason(finishReason)
                            .done(isDone)
                            .build();
                }
                // Reaching the end here means no chunk carried a finish reason: a cut-off stream.
                done = true;
                closeReader();
                throw new GatewayException("PROVIDER_ERROR",
                        "Gemini stream ended before a finish reason; the answer is incomplete");
            } catch (IOException e) {
                done = true;
                closeReader();
                throw new GatewayException("PROVIDER_ERROR",
                        "Error reading Gemini stream: " + e.getMessage(), e);
            }
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
    // Inner response DTOs (Gemini wire format)
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    static class GeminiResponse {
        private List<Candidate> candidates;
        private UsageMetadata usageMetadata;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Candidate {
            private Content content;
            private String finishReason;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Content {
            private String role;
            private List<Part> parts;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class Part {
            private String text;
            private FunctionCall functionCall;

            @Data @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
            static class FunctionCall {
                /** Present on some models and API versions; absent on most. */
                private String id;
                private String name;
                private Object args;
            }
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class UsageMetadata {
            @JsonProperty("promptTokenCount")      private int promptTokenCount;
            @JsonProperty("candidatesTokenCount")   private int candidatesTokenCount;
            @JsonProperty("totalTokenCount")        private int totalTokenCount;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GeminiModelList {
        private List<GeminiModelEntry> models;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GeminiModelEntry {
        private String name;
    }
}