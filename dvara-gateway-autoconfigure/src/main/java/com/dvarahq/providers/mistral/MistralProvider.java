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
package com.dvarahq.providers.mistral;

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

public class MistralProvider extends AbstractLlmProvider {

    private final RestClient restClient;

    public MistralProvider(SecretProvider secretProvider, String baseUrl, RestClient.Builder builder) {
        super("mistral");
        this.restClient = builder
                .baseUrl(baseUrl)
                .requestInterceptor(new CredentialInterceptor(
                        secretProvider, "provider.mistral.api-key",
                        "Authorization", key -> "Bearer " + key))
                .build();
    }

    public MistralProvider(SecretProvider secretProvider, String baseUrl) {
        this(secretProvider, baseUrl, RestClient.builder());
    }

    /** Package-private constructor for unit tests. Accepts a pre-configured {@link RestClient}. */
    MistralProvider(RestClient restClient) {
        super("mistral");
        this.restClient = restClient;
    }

    // -------------------------------------------------------------------------
    // Chat
    // -------------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request) {
        Map<String, Object> body = buildChatBody(request);

        MistralChatResponse mistral = restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(status -> status.isError(), (req, res) -> {
                    throw GatewayException.upstream(res.getStatusCode().value(),
                            "Mistral API error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                })
                .body(MistralChatResponse.class);

        return mapChatResponse(mistral);
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    @Override
    public Iterator<SseChunk> streamChat(ChatRequest request) {
        Map<String, Object> body = buildChatBody(request);
        body.put("stream", true);
        // Mistral sends a usage block on the final chunk only when asked; without this every
        // Mistral stream is billed on an estimate.
        body.put("stream_options", Map.of("include_usage", true));

        return restClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((req, res) -> {
                    if (res.getStatusCode().isError()) {
                        throw GatewayException.upstream(res.getStatusCode().value(),
                                "Mistral streaming error " + res.getStatusCode().value()
                                + GatewayException.describeHttpStatus(res.getStatusCode().value()));
                    }
                    // The body stream is the transport handle, released through StreamTransport:
                    // ClientHttpResponse.close() drains the body first and would wait for a stalled server, and
                    // what returns a parked read differs per HTTP client; StreamTransport knows both.
                    java.io.InputStream responseBody = res.getBody();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8));
                    return new OpenAiCompatibleStreamDecoder(() -> StreamTransport.release(res, responseBody), reader, request.getModel(), "Mistral");
                }, false);
    }

    // -------------------------------------------------------------------------
    // Shared body builder
    // -------------------------------------------------------------------------

    private Map<String, Object> buildChatBody(ChatRequest request) {
        List<Map<String, Object>> messages = request.getMessages().stream()
                .map(this::serializeMessage)
                .collect(Collectors.toList());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("messages", messages);
        if (request.getMaxTokens()   != null) body.put("max_tokens",   request.getMaxTokens());
        if (request.getTemperature() != null) body.put("temperature",  request.getTemperature());
        if (request.getTopP()        != null) body.put("top_p",        request.getTopP());
        if (request.getFrequencyPenalty() != null) body.put("frequency_penalty", request.getFrequencyPenalty());
        if (request.getPresencePenalty()  != null) body.put("presence_penalty",  request.getPresencePenalty());
        if (request.getStop() != null) body.put("stop", request.getStop());
        if (request.getSeed() != null) body.put("random_seed", request.getSeed());   // Mistral's name for it
        applyResponseFormat(body, request.getResponseFormat());
        applyTools(body, request);
        return body;
    }

    /**
     * One message on Mistral's wire, which is OpenAI's for tools: {@code tool_calls} on
     * an assistant message, {@code tool_call_id} on a tool-result message.
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
     * Relays tool definitions and {@code tool_choice}. Mistral's shape is OpenAI's, except that
     * the forced choice is spelled {@code any} where OpenAI spells it {@code required}.
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
            body.put("tool_choice", "required".equals(request.getToolChoice()) ? "any" : request.getToolChoice());
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
                .map(b -> switch (b) {
                    case ContentBlock.TextBlock tb -> (Object) Map.of("type", "text", "text", tb.text());
                    // This provider declares supportsVision=false, so an image is refused outright,
                    // as Cohere, Groq and Ollama do, rather than relayed as text.
                    case ContentBlock.ImageBlock ib -> throw new GatewayException("UNSUPPORTED_CAPABILITY",
                            "Mistral does not support image input through this gateway. Send text only, "
                            + "or route the request to a vision-capable provider.");
                })
                .collect(Collectors.toList());
    }

    private ChatResponse mapChatResponse(MistralChatResponse mistral) {
        List<ChatResponse.Choice> choices = mistral.getChoices().stream()
                .map(c -> ChatResponse.Choice.builder()
                        .index(c.getIndex())
                        .message(toAssistantMessage(c.getMessage()))
                        .finishReason(c.getFinishReason())
                        .build())
                .toList();

        // The upstream reported nothing; null says that, and a zeroed block would say it
        // consumed nothing — which the metering path cannot tell from a real zero.
        ChatResponse.Usage usage = mistral.getUsage() == null
                ? null
                : ChatResponse.Usage.builder()
                        .promptTokens(mistral.getUsage().getPromptTokens())
                        .completionTokens(mistral.getUsage().getCompletionTokens())
                        .totalTokens(mistral.getUsage().getTotalTokens())
                        .build();

        return ChatResponse.builder()
                .id(mistral.getId())
                .object("chat.completion")
                .created(mistral.getCreated())
                .model(mistral.getModel())
                .choices(choices)
                .usage(usage)
                .build();
    }

    /** The assistant message with any {@code tool_calls} the model emitted, so they reach the client. */
    private static MultimodalMessage toAssistantMessage(MistralChatResponse.MistralMessage m) {
        MultimodalMessage.MultimodalMessageBuilder builder = MultimodalMessage.builder()
                .role(m.getRole() != null ? m.getRole() : "assistant")
                .content(List.of(new com.dvarahq.core.model.ContentBlock.TextBlock(m.getContent() != null ? m.getContent() : "")));
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
    // Capabilities / routing
    // -------------------------------------------------------------------------

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, false, true, true, true, false, true, 128_000);
    }

    @Override
    protected List<String> modelPrefixes() {
        return List.of("mistral");
    }

    @Override
    public java.util.List<com.dvarahq.core.provider.ModelInfo> listModels() {
        // The base URL already ends in /v1, so the model list is /models from it.
        OaiCompatModelList response = restClient.get()
                .uri("/models")
                .retrieve()
                .body(OaiCompatModelList.class);
        if (response == null || response.data == null) return List.of();
        return response.data.stream()
                .map(m -> new com.dvarahq.core.provider.ModelInfo(
                        m.id, m.ownedBy != null ? m.ownedBy : "mistralai", m.created))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Inner response DTOs (Mistral wire format — OpenAI-compatible)
    // -------------------------------------------------------------------------

    @Data @JsonIgnoreProperties(ignoreUnknown = true)
    private static class MistralChatResponse {
        private String id;
        private String object;
        private long created;
        private String model;
        private List<MistralChoice> choices;
        private MistralUsage usage;

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class MistralChoice {
            private int index;
            private MistralMessage message;
            @JsonProperty("finish_reason") private String finishReason;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class MistralMessage {
            private String role;
            private String content;
            @JsonProperty("tool_calls") private List<MistralToolCall> toolCalls;
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class MistralToolCall {
            private String id;
            private String type;
            private MistralFunction function;

            @Data @JsonIgnoreProperties(ignoreUnknown = true)
            static class MistralFunction {
                private String name;
                private String arguments;
            }
        }

        @Data @JsonIgnoreProperties(ignoreUnknown = true)
        static class MistralUsage {
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