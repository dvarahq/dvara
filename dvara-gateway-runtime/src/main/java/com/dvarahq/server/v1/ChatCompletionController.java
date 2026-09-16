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
package com.dvarahq.server.v1;

import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.service.ChatExecutionService;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.v1.dto.ChatCompletionChunkResponse;
import com.dvarahq.server.v1.dto.ChatCompletionRequest;
import com.dvarahq.server.v1.dto.ChatCompletionResponse;
import com.dvarahq.server.v1.dto.Message;
import com.dvarahq.server.v1.dto.ToolCall;
import com.dvarahq.server.web.AccessLogFilter;
import com.dvarahq.server.web.TraceIdFilter;
import com.dvarahq.core.util.JsonMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/v1")
@Tag(name = "Chat", description = "OpenAI-compatible chat completions")
public class ChatCompletionController {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionController.class);
    /**
     * The stream's lifetime, from the global {@code resilience.timeout.streaming-timeout-ms}. The
     * resilience layer bounds opening the stream with the same property, or a provider's own
     * override of it; the emitter is created before a provider is chosen, so an override reaches
     * only the open wait.
     */
    private final long streamingTimeoutMs;

    private static final String CACHE_HEADER = "X-Cache";

    // The shared governance + dispatch + metering body lives in ChatExecutionService so this
    // controller and the other doorways cannot drift. Built from the injected deps rather than
    // injected as a bean so the @WebMvcTest slice stays wired to the same mocks.
    private final ChatExecutionService executionService;

    public ChatCompletionController(ProviderDispatcher dispatcher,
                                    RequestPipeline requestPipeline,
                                    org.springframework.beans.factory.ObjectProvider<ResponseCache> responseCache,
                                    TokenUsageRepository tokenUsageRepository,
                                    org.springframework.beans.factory.ObjectProvider<WorkspaceUsageListener> usageListeners,
                                    org.springframework.beans.factory.ObjectProvider<CostCalculationService> costCalculationService,
                                    org.springframework.beans.factory.ObjectProvider<CostEstimator> costEstimator,
                                    PiiEnforcer piiEnforcer,
                                    RateLimiter rateLimiter,
                                    StreamingResponseEnforcer streamingResponseEnforcer,
                                    AuditWriter auditWriter,
                                    org.springframework.beans.factory.ObjectProvider<PriorityAdmissionController> priorityAdmissionController,
                                    GatewayMetrics metrics,
                                    TokenEstimator tokenEstimator,
                                    org.springframework.beans.factory.ObjectProvider<CallOutcomeListener> outcomeListeners,
                                    @org.springframework.beans.factory.annotation.Value(
                                            "${dvara.llm-gateway.resilience.timeout.streaming-timeout-ms:120000}")
                                    long streamingTimeoutMs) {
        this.streamingTimeoutMs = streamingTimeoutMs;
        this.executionService = new ChatExecutionService(dispatcher, requestPipeline, responseCache,
                tokenUsageRepository, usageListeners, costCalculationService, costEstimator,
                piiEnforcer, rateLimiter,
                streamingResponseEnforcer, auditWriter, priorityAdmissionController, metrics, tokenEstimator,
                outcomeListeners);
    }

    @PostMapping("/chat/completions")
    @Operation(summary = "Create chat completion", description = "Sends a chat completion request to the selected provider. Supports streaming via SSE when stream=true.")
    @ApiResponse(responseCode = "200", description = "Chat completion response or SSE stream")
    @ApiResponse(responseCode = "400", description = "Invalid request or no provider available")
    @ApiResponse(responseCode = "502", description = "Upstream provider error")
    public Object chatCompletions(
            @Valid @RequestBody ChatCompletionRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);

        // Enrich access log context
        httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, request.getModel());
        httpRequest.setAttribute(AccessLogFilter.ATTR_STREAM, String.valueOf(Boolean.TRUE.equals(request.getStream())));

        if (Boolean.TRUE.equals(request.getStream())) {
            return handleStreaming(request, traceId, httpRequest, httpResponse);
        }

        // Governance preamble (template → budget → policy → PII → guardrail → priority → downgrade → context window)
        ChatExecutionService.Prepared prep =
                executionService.prepare(toInternal(request), httpRequest, httpResponse, traceId);
        FilterContext ctx = prep.ctx();
        PolicyDecision policyDecision = ctx.getPolicyDecision();

        try {
            ChatExecutionService.SyncResult result = executionService.executeSync(prep.request(), ctx, httpRequest);
            ChatResponse response = result.response();

            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .header(TraceIdFilter.HEADER, traceId)
                    .header(CACHE_HEADER, result.cacheHit() ? "HIT" : "MISS");
            if (!result.cacheHit()) {
                addGatewayHeaders(builder, response);
            }
            ContextResponseHeaders.apply(builder, ctx, policyDecision);
            return builder.body(toExternal(response));
        } finally {
            executionService.releasePriority(ctx);
        }
    }

    private SseEmitter handleStreaming(ChatCompletionRequest request, String traceId,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
        ChatExecutionService.Prepared prep =
                executionService.prepare(toInternal(request), httpRequest, httpResponse, traceId);
        ChatRequest streamRequest = prep.request();
        boolean includeUsage = request.getStreamOptions() != null
                && Boolean.TRUE.equals(request.getStreamOptions().get("include_usage"));
        FilterContext ctx = prep.ctx();
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");
        PolicyDecision policyDecision = ctx.getPolicyDecision();

        // Set budget headers on servlet response before SSE stream starts
        ContextResponseHeaders.apply(httpResponse, ctx, policyDecision);

        // Set once the stream is open, so the container's timeout can release a read parked on it.
        java.util.concurrent.atomic.AtomicReference<Iterator<SseChunk>> upstream = new java.util.concurrent.atomic.AtomicReference<>();
        SseEmitter emitter = new SseEmitter(streamingTimeoutMs);
        AtomicBoolean completed = new AtomicBoolean(false);

        emitter.onCompletion(() -> completed.set(true));
        emitter.onTimeout(() -> {
            completed.set(true);
            // The emit thread may be parked in hasNext() on a stalled provider, and reads the flag only
            // after that returns. Releasing the transport — never closing the iterator, whose
            // finalization is the emit thread's — is what returns it.
            if (upstream.get() instanceof com.dvarahq.core.model.ReleasableUpstream r) {
                r.releaseTransport();
            }
        });
        emitter.onError(e -> completed.set(true));

        // Accumulates output text so the streaming tail can persist an estimated TokenUsageRecord
        // when the upstream reports no usage, mirroring the non-streaming path.
        StringBuilder outputAccumulator = new StringBuilder();
        // Holds the upstream's own usage block if the stream reports one. AtomicReference
        // because the emit loop runs on a virtual thread and the tail reads it.
        java.util.concurrent.atomic.AtomicReference<com.dvarahq.core.model.ChatResponse.Usage>
                reportedUsage = new java.util.concurrent.atomic.AtomicReference<>();

        // The streaming tail reports the call's outcome, which needs a latency and an error verdict.
        AtomicBoolean streamFailed = new AtomicBoolean();

        // Captured before the emit loop starts, off a request that is certainly live. The
        // tail runs before emitter.complete(), but a client disconnect or timeout can
        // complete the emitter first, and the container may then recycle the request.
        ChatExecutionService.TokenSettlement settlement =
                ChatExecutionService.TokenSettlement.capture(httpRequest);
        // The row's attribution too: key and workspace now; provider and credential are
        // stamped while the stream is opened, so they are taken right after openStream, below.
        ChatExecutionService.Attribution attribution = ChatExecutionService.Attribution.capture(httpRequest);

        Thread.startVirtualThread(ChatExecutionService.withRequestContext(() -> {
            long streamStart = System.nanoTime();
            Iterator<SseChunk> chunks = null;
            Exception failure = null;
            ChatExecutionService.Attribution who = attribution;
            try {
                chunks = executionService.openStream(streamRequest, workspaceId);
                upstream.set(chunks);
                who = who.withUpstreamFrom(httpRequest);   // provider + credential, now known
                boolean firstChunk = true;
                boolean sawDone = false;
                String streamId = null;

                // cancellation first: an abandoned request must not read another frame,
                // which can block, and can end the stream in a way that reads as an upstream
                // failure against a caller who has already left.
                while (!completed.get() && chunks.hasNext()) {
                    SseChunk chunk = chunks.next();
                    if (chunk.getId() != null) {
                        streamId = chunk.getId();
                    }
                    // the exact usage arrives on the final chunk. Hold the last non-null
                    // rather than only checking `done`: the usage-bearing chunk follows the one
                    // carrying finish_reason, so keying off done would miss it.
                    if (chunk.getUsage() != null) {
                        reportedUsage.set(chunk.getUsage());
                    }
                    if (chunk.getDelta() != null) {
                        outputAccumulator.append(chunk.getDelta());
                    }
                    // A tool call is output too: its name and arguments are what the model
                    // produced, so they count in the estimate when the upstream reports no usage,
                    // and a stream that returns only a call is still a stream that served something.
                    if (chunk.getToolCalls() != null) {
                        for (ToolCallDelta call : chunk.getToolCalls()) {
                            if (call.name() != null) {
                                outputAccumulator.append(call.name());
                            }
                            if (call.argumentsFragment() != null) {
                                outputAccumulator.append(call.argumentsFragment());
                            }
                        }
                    }
                    ChatCompletionChunkResponse chunkResponse = toChunkResponse(chunk, firstChunk);
                    String json = JsonMapper.instance().writeValueAsString(chunkResponse);
                    emitter.send(SseEmitter.event().data(json, MediaType.APPLICATION_JSON));
                    firstChunk = false;

                    if (chunk.isDone()) {
                        sawDone = true;
                        break;
                    }
                }

                // The provider decoders raise a stream that ends before its finish; this is the backstop for
                // an iterator that does not. Half an answer followed by [DONE] reads as a whole one. The
                // streaming guard ends its own output with a terminal chunk, so behind the guard it is the
                // decoder's check that counts.
                if (!sawDone && !completed.get()) {
                    throw new GatewayException("PROVIDER_ERROR",
                            "The upstream stream ended before it finished; the answer is incomplete");
                }
                // stream_options.include_usage: a last chunk with empty choices and the usage block, as
                // OpenAI sends it. Only a usage block the upstream reported is sent; an estimate is not
                // presented as the provider's count.
                if (includeUsage && reportedUsage.get() != null && !completed.get()) {
                    com.dvarahq.core.model.ChatResponse.Usage u = reportedUsage.get();
                    ChatCompletionChunkResponse usageChunk = ChatCompletionChunkResponse.builder()
                            .id(streamId)
                            .object("chat.completion.chunk")
                            .created(Instant.now().getEpochSecond())
                            .model(streamRequest.getModel())
                            .choices(List.of())
                            .usage(ChatCompletionResponse.Usage.builder()
                                    .promptTokens(u.getPromptTokens())
                                    .completionTokens(u.getCompletionTokens())
                                    .totalTokens(u.getTotalTokens())
                                    .build())
                            .build();
                    emitter.send(SseEmitter.event().data(
                            JsonMapper.instance().writeValueAsString(usageChunk), MediaType.APPLICATION_JSON));
                }
                emitter.send(SseEmitter.event().data("[DONE]"));
            } catch (Exception e) {
                if (completed.get()) {
                    // Cancellation raced an in-flight read; the caller is gone, and what the read
                    // threw is not an upstream failure to record against them.
                    log.debug("Stream for trace {} ended after the client left: {}", traceId, e.getMessage());
                } else {
                    failure = e;
                    streamFailed.set(true);
                }
            }

            // the guard learns about a client cancellation only through close(). It
            // releases the provider connection first and then, under Immediate delivery,
            // finalizes the audit of what was delivered off this thread; under Deferred it
            // discards what it held. Without this call the cancellation contract is dead code.
            if (chunks instanceof AutoCloseable guard) {
                try {
                    guard.close();
                } catch (Exception closeEx) {
                    log.warn("Failed to close the streaming guard for trace {}: {}",
                            traceId, closeEx.getMessage());
                }
            }
            // The tail runs before the emitter completes. Completing it triggers the async
            // dispatch on which the access log, metrics and audit filters record this request, so
            // the tokens this persists and the error code below have to be on the request first.
            // The client already has its last event; what it waits for here is a usage row.
            try {
                executionService.persistStreamingUsage(httpRequest, streamRequest,
                        outputAccumulator.toString(), ctx,
                        (System.nanoTime() - streamStart) / 1_000_000L, streamFailed.get(),
                        reportedUsage.get(), settlement, who);
            } catch (Exception persistEx) {
                // Best-effort on the streaming path — the response is already delivered.
                log.warn("Failed to persist streaming token usage for trace {}: {}",
                        traceId, persistEx.getMessage());
            }
            executionService.releasePriority(ctx);

            if (failure == null) {
                if (!completed.get()) {
                    emitter.complete();
                }
            } else {
                try {
                    httpRequest.setAttribute(AccessLogFilter.ATTR_ERROR_CODE, streamErrorCode(failure));
                } catch (RuntimeException recycled) {
                    // A client that disconnected mid-stream completed the emitter before this tail
                    // ran, and the container may have recycled the request since.
                    log.debug("Could not record the stream error for trace {}: {}", traceId, recycled.getMessage());
                }
                if (!completed.get()) {
                    log.warn("Streaming error for trace {}: {}", traceId, failure.getMessage());
                    try {
                        emitter.completeWithError(failure);
                    } catch (Exception ignored) {
                        // emitter already completed
                    }
                }
            }
        }));

        return emitter;
    }

    private ChatCompletionChunkResponse toChunkResponse(SseChunk chunk, boolean firstChunk) {
        ChatCompletionChunkResponse.Delta delta = ChatCompletionChunkResponse.Delta.builder()
                .role(firstChunk ? "assistant" : null)
                .content(chunk.getDelta())
                .toolCalls(toWireToolCalls(chunk.getToolCalls()))
                .build();

        return ChatCompletionChunkResponse.builder()
                .id(chunk.getId())
                .object("chat.completion.chunk")
                .created(Instant.now().getEpochSecond())
                .model(chunk.getModel())
                .choices(List.of(
                        ChatCompletionChunkResponse.Choice.builder()
                                .index(0)
                                .delta(delta)
                                .finishReason(chunk.getFinishReason())
                                .build()
                ))
                .build();
    }

    /**
     * The chunk's tool-call fragments in OpenAI's wire shape. An opener — the fragment that
     * names the call — carries {@code type: "function"} and {@code arguments: ""} when it brought no
     * argument text, as OpenAI's does, because SDKs that assemble a call concatenate onto that field
     * and a missing one breaks the concatenation. A continuation carries only its slice.
     */
    private static List<ChatCompletionChunkResponse.ToolCallDelta> toWireToolCalls(List<ToolCallDelta> calls) {
        if (calls == null || calls.isEmpty()) {
            return null;
        }
        List<ChatCompletionChunkResponse.ToolCallDelta> out = new java.util.ArrayList<>(calls.size());
        for (ToolCallDelta call : calls) {
            boolean opener = call.id() != null || call.name() != null;
            out.add(ChatCompletionChunkResponse.ToolCallDelta.builder()
                    .index(call.index())
                    .id(call.id())
                    .type(opener ? "function" : null)
                    .function(ChatCompletionChunkResponse.FunctionDelta.builder()
                            .name(call.name())
                            .arguments(call.argumentsFragment() != null ? call.argumentsFragment() : opener ? "" : null)
                            .build())
                    .build());
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Mapping: external DTO ↔ internal model
    // -------------------------------------------------------------------------

    private ChatRequest toInternal(ChatCompletionRequest req) {
        // The gateway returns one choice. Answering one when several were asked would be a
        // silent lie, so anything but n=1 is refused, as /v1/completions does. `user` is a
        // caller-side tracking id and is accepted and ignored: the gateway attributes by API key.
        if (req.getN() != null && req.getN() != 1) {
            throw new GatewayException("UNSUPPORTED_CAPABILITY",
                    "n must be 1 on /v1/chat/completions; one choice is returned");
        }
        // Fields the gateway cannot honour are refused rather than dropped with a 200. The harmless
        // values (logprobs false, parallel_tool_calls true, top_logprobs 0) ask for nothing extra.
        if (Boolean.TRUE.equals(req.getLogprobs())
                || (req.getTopLogprobs() != null && req.getTopLogprobs() > 0)) {
            throw new GatewayException("UNSUPPORTED_CAPABILITY",
                    "logprobs are not supported on /v1/chat/completions; the response carries none");
        }
        if (Boolean.FALSE.equals(req.getParallelToolCalls())) {
            throw new GatewayException("UNSUPPORTED_CAPABILITY",
                    "parallel_tool_calls=false is not supported on /v1/chat/completions");
        }
        List<MultimodalMessage> messages = req.getMessages().stream()
                .map(m -> MultimodalMessage.builder()
                        .role(m.getRole())
                        .content(toContentBlocks(m.getContent()))
                        .toolCalls(toInternalToolCalls(m.getToolCalls()))
                        .toolCallId(m.getToolCallId())
                        .name(m.getName())
                        .build())
                .toList();
        return ChatRequest.builder()
                .model(req.getModel())
                .messages(messages)
                .stream(Boolean.TRUE.equals(req.getStream()))
                .maxTokens(req.getMaxTokens())
                .temperature(req.getTemperature())
                .topP(req.getTopP())
                .frequencyPenalty(req.getFrequencyPenalty())
                .presencePenalty(req.getPresencePenalty())
                .stop(ChatInputs.stopSequences(req.getStop()))
                .seed(req.getSeed())
                .responseFormat(parseResponseFormat(req.getResponseFormat()))
                .tools(toToolDefinitions(req.getTools()))
                .toolChoice(req.getToolChoice())
                .metadata(req.getMetadata())
                .build();
    }

    /**
     * Maps OpenAI-style {@code tools[]} ({@code {type:"function", function:{name,
     * description, parameters}}}) to the internal {@link ToolDefinition}.
     * Only {@code function} tools are relayed; malformed entries are skipped.
     */
    @SuppressWarnings("unchecked")
    private List<ToolDefinition> toToolDefinitions(List<Object> tools) {
        if (tools == null || tools.isEmpty()) return null;
        List<ToolDefinition> out = new java.util.ArrayList<>();
        for (Object t : tools) {
            if (!(t instanceof Map<?, ?> map)) continue;
            Object fn = map.get("function");
            if (fn instanceof Map<?, ?> function) {
                Object name = function.get("name");
                Object desc = function.get("description");
                Object params = function.get("parameters");
                out.add(ToolDefinition.builder()
                        .name(name != null ? name.toString() : null)
                        .description(desc != null ? desc.toString() : null)
                        .parameters(params instanceof Map ? (Map<String, Object>) params : null)
                        .build());
            }
        }
        return out.isEmpty() ? null : out;
    }

    /** DTO {@code tool_calls} → internal {@link com.dvarahq.core.model.ToolCall}. */
    private List<com.dvarahq.core.model.ToolCall> toInternalToolCalls(List<ToolCall> dtoCalls) {
        if (dtoCalls == null || dtoCalls.isEmpty()) return null;
        return dtoCalls.stream()
                .map(tc -> com.dvarahq.core.model.ToolCall.builder()
                        .id(tc.getId())
                        .name(tc.getFunction() != null ? tc.getFunction().getName() : null)
                        .arguments(tc.getFunction() != null ? tc.getFunction().getArguments() : null)
                        .build())
                .toList();
    }

    /**
     * Overwrites {@code metadata.workspace_id} with the server-resolved workspace ID. Delegates to
     * {@link ChatExecutionService#injectResolvedWorkspace} — kept here as the tested entry point
     * (if the call site were dropped, the canary workspace-scope check would go blind).
     */
    static void injectResolvedWorkspace(ChatRequest request, String workspaceId) {
        ChatExecutionService.injectResolvedWorkspace(request, workspaceId);
    }

    ResponseFormat parseResponseFormat(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return null;

        Object typeObj = raw.get("type");
        if (typeObj == null) {
            throw new GatewayException("INVALID_REQUEST", "response_format.type is required");
        }
        String type = typeObj.toString();

        return switch (type) {
            case "text" -> new ResponseFormat.Text();
            case "json_object" -> new ResponseFormat.JsonObject();
            case "json_schema" -> parseJsonSchema(raw);
            default -> throw new GatewayException("INVALID_REQUEST",
                    "Unknown response_format type: " + type);
        };
    }

    @SuppressWarnings("unchecked")
    private ResponseFormat.JsonSchema parseJsonSchema(Map<String, Object> raw) {
        Object jsonSchemaObj = raw.get("json_schema");
        if (jsonSchemaObj == null) {
            throw new GatewayException("INVALID_REQUEST",
                    "response_format.json_schema is required when type is json_schema");
        }
        if (!(jsonSchemaObj instanceof Map<?, ?> schemaMap)) {
            throw new GatewayException("INVALID_REQUEST",
                    "response_format.json_schema must be an object");
        }
        Map<String, Object> jsonSchema = (Map<String, Object>) schemaMap;

        String name = jsonSchema.get("name") != null ? jsonSchema.get("name").toString() : "response";
        Object schemaObj = jsonSchema.get("schema");
        if (schemaObj == null) {
            throw new GatewayException("INVALID_REQUEST",
                    "response_format.json_schema.schema is required");
        }
        if (!(schemaObj instanceof Map<?, ?>)) {
            throw new GatewayException("INVALID_REQUEST",
                    "response_format.json_schema.schema must be an object");
        }
        Map<String, Object> schema = (Map<String, Object>) schemaObj;
        boolean strict = Boolean.TRUE.equals(jsonSchema.get("strict"));

        return new ResponseFormat.JsonSchema(name, schema, strict);
    }

    /**
     * A message's content: a string, or OpenAI's array of {@code text} and {@code image_url} parts,
     * each parsed into its own content block rather than relayed as the array's Java rendering.
     */
    private List<ContentBlock> toContentBlocks(Object content) {
        if (content instanceof String text) {
            return List.of(new ContentBlock.TextBlock(text));
        }
        if (content == null) {
            return List.of(new ContentBlock.TextBlock(""));
        }
        if (content instanceof List<?> parts) {
            List<ContentBlock> blocks = new java.util.ArrayList<>(parts.size());
            for (Object part : parts) {
                if (!(part instanceof java.util.Map<?, ?> p)) {
                    throw new GatewayException("INVALID_REQUEST", "content parts must be objects");
                }
                blocks.add(ChatInputs.chatPart(p));
            }
            return blocks;
        }
        throw new GatewayException("INVALID_REQUEST", "content must be a string or an array of content parts");
    }

    private ChatCompletionResponse toExternal(ChatResponse resp) {
        List<ChatCompletionResponse.Choice> choices = resp.getChoices().stream()
                .map(c -> ChatCompletionResponse.Choice.builder()
                        .index(c.getIndex())
                        .message(toExternalMessage(c.getMessage()))
                        .finishReason(c.getFinishReason())
                        .build())
                .toList();

        ChatCompletionResponse.Usage usage = resp.getUsage() == null ? null
                : ChatCompletionResponse.Usage.builder()
                        .promptTokens(resp.getUsage().getPromptTokens())
                        .completionTokens(resp.getUsage().getCompletionTokens())
                        .totalTokens(resp.getUsage().getTotalTokens())
                        .build();

        return ChatCompletionResponse.builder()
                .id(resp.getId())
                .object("chat.completion")
                .created(resp.getCreated())
                .model(resp.getModel())
                .choices(choices)
                .usage(usage)
                .build();
    }

    private Message toExternalMessage(MultimodalMessage msg) {
        String text = msg.getContent() == null ? "" : msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .collect(Collectors.joining("\n"));
        Message.MessageBuilder builder = Message.builder().role(msg.getRole()).content(text);
        // relay the model's tool calls back to the client (OpenAI shape).
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            builder.toolCalls(msg.getToolCalls().stream()
                    .map(tc -> ToolCall.builder()
                            .id(tc.getId())
                            .type("function")
                            .function(ToolCall.FunctionCall.builder()
                                    .name(tc.getName())
                                    .arguments(tc.getArguments())
                                    .build())
                            .build())
                    .toList());
        }
        return builder.build();
    }

    private void addGatewayHeaders(ResponseEntity.BodyBuilder builder, ChatResponse response) {
        if (response.getGatewayHeaders() != null) {
            response.getGatewayHeaders().forEach(builder::header);
        }
    }


    /**
     * What a stream that failed mid-flight is recorded under. The status stays 200, since
     * it was committed with the first event; the error code is what says the wire did not end well.
     */
    static String streamErrorCode(Exception failure) {
        return failure instanceof GatewayException ge ? ge.getCode() : "STREAM_ERROR";
    }
}
