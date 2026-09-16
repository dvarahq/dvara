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
package com.dvarahq.providers.groq;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.providers.support.OpenAiCompatibleStreamDecoder;
import com.dvarahq.providers.support.StreamTransport;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.AbstractLlmProvider;
import com.dvarahq.core.provider.ProviderCapabilities;
import com.dvarahq.core.secret.SecretProvider;
import com.dvarahq.providers.support.CredentialInterceptor;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class GroqProvider extends AbstractLlmProvider {

    private final RestClient restClient;

    public GroqProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("groq");
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.groq.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build();
    }

    public GroqProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    GroqProvider(RestClient restClient) {
        super("groq");
        this.restClient = restClient;
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        Map<String, Object> body = buildChatBody(request);

        GroqChatResponse groq = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "Groq API error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(GroqChatResponse.class);

        return mapChatResponse(groq);
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        Map<String, Object> body = buildChatBody(request);
        body.put("stream", true);

        return restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    if (res.getStatusCode().isError()) {
                        throw GatewayException.upstream(res.getStatusCode().value(),
                                "Groq streaming error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                    }
                    // The body stream is the transport handle, released through StreamTransport:
                    // ClientHttpResponse.close() drains the body first and would wait for a stalled server, and
                    // what returns a parked read differs per HTTP client; StreamTransport knows both.
                    java.io.InputStream responseBody = res.getBody();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8));
                    return new OpenAiCompatibleStreamDecoder(() -> StreamTransport.release(res, responseBody), reader, request.getModel(), "Groq");
                }, false);
    }

    // -------------------------------------------------------------------------
    // Shared body builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildChatBody(ChatRequest request) {
        rejectUnsupportedContentBlocks(request);
        List<Map<String, Object>> messages = request.getMessages().stream()
                .map(m -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("role", m.getRole());
                    out.put("content", extractContent(m));
                    if (m.getName() != null) out.put("name", m.getName());
                    return out;
                })
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", stripPrefix(request.getModel()));
        body.put("messages", messages);
        if (request.getMaxTokens()   != null) body.put("max_tokens",   request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature",  request.getTemperature());
        if (request.getTopP()        != null) body.put("top_p",        request.getTopP());
        if (request.getFrequencyPenalty() != null) body.put("frequency_penalty", request.getFrequencyPenalty());
        if (request.getPresencePenalty()  != null) body.put("presence_penalty",  request.getPresencePenalty());
        if (request.getStop() != null) body.put("stop", request.getStop());
        if (request.getSeed() != null) body.put("seed", request.getSeed());
        applyResponseFormat(body, request.getResponseFormat());
        return body;
    }

    private void rejectUnsupportedContentBlocks(ChatRequest request) {
        for (MultimodalMessage msg : request.getMessages()) {
            if (msg.getContent() == null) continue;
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ContentBlock.ImageBlock) {
                    throw new GatewayException("UNSUPPORTED_CAPABILITY",
                            "Groq vision is not yet implemented in DVARA. Use a vision-capable "
                            + "provider (OpenAI, Anthropic, Gemini, Bedrock, Azure OpenAI) for now.");
                }
                // No tool-block branch: a tool call travels on the message's toolCalls, not as a
                // content block, and the dispatcher keeps a request carrying tools off a provider
                // whose capabilities declare no tool support, as this one's do.
            }
        }
    }

    private void applyResponseFormat(Map<String, Object> body, ResponseFormat format) {
        if (format == null || format instanceof ResponseFormat.Text) return;
        if (format instanceof ResponseFormat.JsonObject) {
            body.put("response_format", Map.of("type", "json_object"));
        } else if (format instanceof ResponseFormat.JsonSchema) {
            throw new GatewayException("UNSUPPORTED_RESPONSE_FORMAT",
                    "Groq provider does not support json_schema response format. Supported formats: [text, json_object]");
        }
    }

    /** Strip the "groq/" prefix that the routing key uses. */
    private String stripPrefix(String model) {
        return model != null && model.startsWith("groq/") ? model.substring(5) : model;
    }

    private Object extractContent(MultimodalMessage msg) {
        if (msg.getContent() == null || msg.getContent().isEmpty()) return "";
        return msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .collect(Collectors.joining("\n"));
    }

    private ChatResponse mapChatResponse(GroqChatResponse groq) {
        List<ChatResponse.Choice> choices = groq.getChoices().stream()
                .map(c -> ChatResponse.Choice.builder()
                        .index(c.getIndex())
                        .message(MultimodalMessage.assistant(
                                c.getMessage().getContent() != null ? c.getMessage().getContent() : ""))
                        .finishReason(c.getFinishReason())
                        .build())
                .toList();

        // The upstream reported nothing; null says that, and a zeroed block would say it
        // consumed nothing — which the metering path cannot tell from a real zero.
        ChatResponse.Usage usage = groq.getUsage() == null
                ? null
                : ChatResponse.Usage.builder()
                        .promptTokens(groq.getUsage().getPromptTokens())
                        .completionTokens(groq.getUsage().getCompletionTokens())
                        .totalTokens(groq.getUsage().getTotalTokens())
                        .build();

        return ChatResponse.builder()
                .id(groq.getId())
                .object("chat.completion")
                .created(groq.getCreated())
                .model(groq.getModel())
                .choices(choices)
                .usage(usage)
                .build();
    }

    // -------------------------------------------------------------------------
    // Capabilities / routing
    // -------------------------------------------------------------------------

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, false, false, false, true, 131_072);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("groq/");
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        // The base URL already ends in /openai/v1, so the model list is /models from it.
        OaiCompatModelList response = restClient.get()
                .uri("/models")
                .retrieve()
                .body(OaiCompatModelList.class);
        if (response == null || response.data == null) return List.of();
        return response.data.stream()
                .map(m -> new com.dvarahq.core.provider.ModelInfo(
                        "groq/" + m.id, m.ownedBy != null ? m.ownedBy : "groq", m.created))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Inner response DTOs (Groq wire format — OpenAI-compatible)
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class GroqChatResponse {
        private String id;
        private String object;
        private long created;
        private String model;
        private List<GroqChoice> choices;
        private GroqUsage usage;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class GroqChoice {
            private int index;
            private GroqMessage message;
            @JsonProperty("finish_reason") private String finishReason;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class GroqMessage {
            private String role;
            private String content;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class GroqUsage {
            @JsonProperty("prompt_tokens")     private int promptTokens;
            @JsonProperty("completion_tokens") private int completionTokens;
            @JsonProperty("total_tokens")      private int totalTokens;
        }
    }

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