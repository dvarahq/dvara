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
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.ContentBlock;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.service.ChatExecutionService;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.v1.dto.ResponseRequest;
import com.dvarahq.server.v1.dto.ResponseResult;
import com.dvarahq.server.web.AccessLogFilter;
import com.dvarahq.server.web.TraceIdFilter;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * OpenAI-compatible Responses API (<code>POST /v1/responses</code>). This controller does only
 * DTO translation and the Responses SSE emit loop; every governance and metering concern is
 * delegated to the shared {@link ChatExecutionService}, so Responses runs the same pipeline,
 * metering, audit and response cache as {@code /v1/chat/completions} across every provider.
 *
 * <p>Text and image input, structured output ({@code text.format}) and {@code top_p} are
 * honoured. Function calling, stateful conversation, hosted tools, reasoning, background mode,
 * and file/audio input are rejected with {@code UNSUPPORTED_CAPABILITY} (400), never silently
 * dropped.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Responses", description = "OpenAI-compatible Responses API (governed, multi-provider)")
public class ResponsesController {

    private static final Logger log = LoggerFactory.getLogger(ResponsesController.class);
    /**
     * The stream's lifetime, from the global {@code resilience.timeout.streaming-timeout-ms}. The
     * resilience layer bounds opening the stream with the same property, or a provider's own
     * override of it; the emitter is created before a provider is chosen, so an override reaches
     * only the open wait.
     */
    private final long streamingTimeoutMs;
    private static final String CACHE_HEADER = "X-Cache";

    // Shared execution line, built from the injected deps (not a bean) so the @WebMvcTest
    // slice stays wired to the same mocks, as ChatCompletionController does.
    private final ChatExecutionService executionService;

    public ResponsesController(ProviderDispatcher dispatcher,
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

    @PostMapping("/responses")
    @Operation(summary = "Create a model response",
            description = "OpenAI Responses API. Maps to the internal model and runs the full governance + "
                    + "metering pipeline. Supports streaming (typed SSE events) when stream=true.")
    @ApiResponse(responseCode = "200", description = "Response object or SSE stream")
    @ApiResponse(responseCode = "400", description = "Invalid request or unsupported feature")
    @ApiResponse(responseCode = "502", description = "Upstream provider error")
    public Object responses(@Valid @RequestBody ResponseRequest request,
                            HttpServletRequest httpRequest,
                            HttpServletResponse httpResponse) {

        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);
        boolean stream = Boolean.TRUE.equals(request.getStream());
        httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, request.getModel());
        httpRequest.setAttribute(AccessLogFilter.ATTR_STREAM, String.valueOf(stream));

        rejectUnsupported(request);
        ChatRequest internal = toInternal(request);

        if (stream) {
            return handleStreaming(internal, traceId, httpRequest, httpResponse);
        }

        ChatExecutionService.Prepared prep =
                executionService.prepare(internal, httpRequest, httpResponse, traceId);
        FilterContext ctx = prep.ctx();
        PolicyDecision policyDecision = ctx.getPolicyDecision();
        try {
            ChatExecutionService.SyncResult result = executionService.executeSync(prep.request(), ctx, httpRequest);
            ChatResponse response = result.response();

            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .header(TraceIdFilter.HEADER, traceId)
                    .header(CACHE_HEADER, result.cacheHit() ? "HIT" : "MISS");
            // Unconditional, as on the other doorways: the cache-hit path clears a cached
            // response's headers, so whatever is here was produced by this request.
            if (response.getGatewayHeaders() != null) {
                response.getGatewayHeaders().forEach(builder::header);
            }
            ContextResponseHeaders.apply(builder, ctx, policyDecision);
            return builder.body(toResponseResult(response));
        } finally {
            executionService.releasePriority(ctx);
        }
    }

    // -------------------------------------------------------------------------
    // Streaming — the full Responses typed-event taxonomy
    // -------------------------------------------------------------------------

    private SseEmitter handleStreaming(ChatRequest internal, String traceId,
                                       HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        ChatExecutionService.Prepared prep =
                executionService.prepare(internal, httpRequest, httpResponse, traceId);
        ChatRequest streamRequest = prep.request();
        FilterContext ctx = prep.ctx();
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");

        ContextResponseHeaders.apply(httpResponse, ctx, ctx.getPolicyDecision());

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
        // when the upstream reports no usage, identical to chat.
        StringBuilder output = new StringBuilder();
        // Holds the upstream's own usage block if the stream reports one. AtomicReference
        // because the emit loop runs on a virtual thread and the tail reads it.
        java.util.concurrent.atomic.AtomicReference<com.dvarahq.core.model.ChatResponse.Usage>
                reportedUsage = new java.util.concurrent.atomic.AtomicReference<>();
        String responseId = "resp_" + shortId();
        String itemId = "msg_" + shortId();
        long createdAt = Instant.now().getEpochSecond();
        String model = streamRequest.getModel();
        AtomicInteger seq = new AtomicInteger(0);

        // See ChatCompletionController: the streaming tail is the only place a Responses
        // stream's outcome can be reported, and that needs a latency and an error verdict.
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
                Map<String, Object> respInProgress = responseObject(responseId, model, "in_progress", List.of(), createdAt);
                emit(emitter, "response.created", seq, mapOf("response", respInProgress));
                emit(emitter, "response.in_progress", seq, mapOf("response", respInProgress));

                chunks = executionService.openStream(streamRequest, workspaceId);
                upstream.set(chunks);
                who = who.withUpstreamFrom(httpRequest);   // provider + credential, now known

                Map<String, Object> itemInProgress = messageItem(itemId, "in_progress", List.of());
                emit(emitter, "response.output_item.added", seq, mapOf("output_index", 0, "item", itemInProgress));
                Map<String, Object> emptyPart = mapOf("type", "output_text", "text", "", "annotations", List.of());
                emit(emitter, "response.content_part.added", seq,
                        mapOf("item_id", itemId, "output_index", 0, "content_index", 0, "part", emptyPart));

                boolean sawDone = false;
                while (!completed.get() && chunks.hasNext()) {   // cancellation first; see ChatCompletionController
                    SseChunk chunk = chunks.next();
                    // the exact usage arrives on the final chunk. Hold the last non-null
                    // rather than only checking `done`: the usage-bearing chunk follows the one
                    // carrying finish_reason, so keying off done would miss it.
                    if (chunk.getUsage() != null) {
                        reportedUsage.set(chunk.getUsage());
                    }
                    if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                        output.append(chunk.getDelta());
                        emit(emitter, "response.output_text.delta", seq,
                                mapOf("item_id", itemId, "output_index", 0, "content_index", 0, "delta", chunk.getDelta()));
                    }
                    if (chunk.isDone()) {
                        sawDone = true;
                        break;
                    }
                }
                // A stream that ends before its finish is incomplete, not completed; see ChatCompletionController.
                if (!sawDone && !completed.get()) {
                    throw new GatewayException("PROVIDER_ERROR",
                            "The upstream stream ended before it finished; the answer is incomplete");
                }

                String full = output.toString();
                emit(emitter, "response.output_text.done", seq,
                        mapOf("item_id", itemId, "output_index", 0, "content_index", 0, "text", full));
                Map<String, Object> donePart = mapOf("type", "output_text", "text", full, "annotations", List.of());
                emit(emitter, "response.content_part.done", seq,
                        mapOf("item_id", itemId, "output_index", 0, "content_index", 0, "part", donePart));
                Map<String, Object> itemDone = messageItem(itemId, "completed", List.of(donePart));
                emit(emitter, "response.output_item.done", seq, mapOf("output_index", 0, "item", itemDone));
                Map<String, Object> respCompleted = responseObject(responseId, model, "completed", List.of(itemDone), createdAt);
                emit(emitter, "response.completed", seq, mapOf("response", respCompleted));
            } catch (Exception e) {
                if (completed.get()) {
                    log.debug("Responses stream for trace {} ended after the client left: {}", traceId, e.getMessage());
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
            // the tail runs before the emitter completes; see ChatCompletionController.
            try {
                executionService.persistStreamingUsage(httpRequest, streamRequest,
                        output.toString(), ctx,
                        (System.nanoTime() - streamStart) / 1_000_000L, streamFailed.get(),
                        reportedUsage.get(), settlement, who);
            } catch (Exception persistEx) {
                log.warn("Failed to persist streaming token usage for trace {}: {}", traceId, persistEx.getMessage());
            }
            executionService.releasePriority(ctx);

            if (failure == null) {
                if (!completed.get()) {
                    emitter.complete();
                }
            } else {
                try {
                    httpRequest.setAttribute(AccessLogFilter.ATTR_ERROR_CODE,
                            ChatCompletionController.streamErrorCode(failure));
                } catch (RuntimeException recycled) {
                    log.debug("Could not record the stream error for trace {}: {}", traceId, recycled.getMessage());
                }
                if (!completed.get()) {
                    log.warn("Responses streaming error for trace {}: {}", traceId, failure.getMessage());
                    try {
                        Map<String, Object> failed = responseObject(responseId, model, "failed", List.of(), createdAt);
                        failed.put("error", mapOf("code", "provider_error", "message", failure.getMessage()));
                        emit(emitter, "response.failed", seq, mapOf("response", failed));
                        emitter.complete();
                    } catch (Exception ignored) {
                        // emitter already closed
                    }
                }
            }
        }));

        return emitter;
    }

    private void emit(SseEmitter emitter, String type, AtomicInteger seq, Map<String, Object> fields) throws Exception {
        LinkedHashMap<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("sequence_number", seq.getAndIncrement());
        event.putAll(fields);
        String json = JsonMapper.instance().writeValueAsString(event);
        emitter.send(SseEmitter.event().name(type).data(json, MediaType.APPLICATION_JSON));
    }

    // -------------------------------------------------------------------------
    // Reject unsupported features cleanly (never silent-drop)
    // -------------------------------------------------------------------------

    void rejectUnsupported(ResponseRequest r) {
        if (Boolean.TRUE.equals(r.getStore())) {
            throw unsupported("store=true (server-side conversation state) is not supported — the gateway is stateless. Use store=false.");
        }
        if (r.getPreviousResponseId() != null) {
            throw unsupported("previous_response_id (stateful chaining) is not supported — the gateway is stateless.");
        }
        if (r.getReasoning() != null) {
            throw unsupported("reasoning items are not supported in this release.");
        }
        if (Boolean.TRUE.equals(r.getBackground())) {
            throw unsupported("background (async) mode is not supported — it requires server-side state.");
        }
        if (r.getPrompt() != null) {
            throw unsupported("reusable prompt objects are not supported — use DVARA prompt templates instead.");
        }
        if (r.getTools() != null && !r.getTools().isEmpty()) {
            // Function calling is not supported on /v1/responses: its output shape (a
            // `function_call` output item plus typed streaming events) is a distinct surface from
            // chat's message.tool_calls. Refused rather than silently dropped. Built-in hosted
            // tools (web_search, file_search, code_interpreter, computer_use, mcp) are not
            // supported either.
            throw unsupported("function calling on /v1/responses is a follow-up "
                    + "(available now on /v1/chat/completions); built-in hosted tools "
                    + "(web_search, file_search, code_interpreter, computer_use, mcp) are not supported.");
        }
        // Checked after `tools` so a request carrying both gets the more informative tools message.
        if (r.getToolChoice() != null) {
            throw unsupported("tool_choice is not supported without function calling on /v1/responses "
                    + "(a follow-up; available now on /v1/chat/completions).");
        }
        if (r.getInclude() != null && !r.getInclude().isEmpty()) {
            throw unsupported("include is not supported in this release — the requested extra output "
                    + "fields would be absent from the response.");
        }
    }

    private static GatewayException unsupported(String message) {
        return new GatewayException("UNSUPPORTED_CAPABILITY", message);
    }

    // -------------------------------------------------------------------------
    // Mapping: Responses DTO <-> internal model
    // -------------------------------------------------------------------------

    ChatRequest toInternal(ResponseRequest r) {
        List<MultimodalMessage> messages = new ArrayList<>();
        if (r.getInstructions() != null && !r.getInstructions().isBlank()) {
            messages.add(MultimodalMessage.builder()
                    .role("system")
                    .content(List.of(new ContentBlock.TextBlock(r.getInstructions())))
                    .build());
        }
        messages.addAll(parseInput(r.getInput()));
        if (messages.isEmpty()) {
            throw new GatewayException("INVALID_REQUEST", "input is required");
        }
        return ChatRequest.builder()
                .model(r.getModel())
                .messages(messages)
                .stream(Boolean.TRUE.equals(r.getStream()))
                .maxTokens(r.getMaxOutputTokens())
                .temperature(r.getTemperature())
                .topP(r.getTopP())
                .responseFormat(parseTextFormat(r.getText()))
                .metadata(r.getMetadata())
                .build();
    }

    private List<MultimodalMessage> parseInput(Object input) {
        if (input == null) {
            return List.of();
        }
        if (input instanceof String s) {
            return List.of(MultimodalMessage.builder()
                    .role("user").content(List.of(new ContentBlock.TextBlock(s))).build());
        }
        if (input instanceof List<?> items) {
            List<MultimodalMessage> messages = new ArrayList<>();
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> map)) {
                    throw new GatewayException("INVALID_REQUEST", "input array items must be objects");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) map;
                messages.add(parseInputItem(m));
            }
            return messages;
        }
        throw new GatewayException("INVALID_REQUEST", "input must be a string or an array of items");
    }

    private MultimodalMessage parseInputItem(Map<String, Object> m) {
        String role = m.get("role") != null ? m.get("role").toString() : "user";
        Object content = m.get("content");
        List<ContentBlock> blocks = new ArrayList<>();
        if (content instanceof String s) {
            blocks.add(new ContentBlock.TextBlock(s));
        } else if (content instanceof List<?> parts) {
            for (Object p : parts) {
                if (p instanceof Map<?, ?> pm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> part = (Map<String, Object>) pm;
                    blocks.add(parsePart(part));
                }
            }
        } else if (content != null) {
            blocks.add(new ContentBlock.TextBlock(content.toString()));
        }
        return MultimodalMessage.builder().role(role).content(blocks).build();
    }

    private ContentBlock parsePart(Map<String, Object> p) {
        String type = p.get("type") != null ? p.get("type").toString() : "input_text";
        return switch (type) {
            case "input_text", "text", "output_text" ->
                    new ContentBlock.TextBlock(p.getOrDefault("text", "").toString());
            case "input_image", "image" -> parseImage(p);
            case "input_file", "file" -> throw unsupported(
                    "file input is not supported in this release (text + image only).");
            case "input_audio", "audio" -> throw unsupported(
                    "audio input is not supported in this release (text + image only).");
            default -> throw new GatewayException("INVALID_REQUEST",
                    "Unknown input content part type: " + type);
        };
    }

    private ContentBlock parseImage(Map<String, Object> p) {
        return ChatInputs.image(p, "input_image");
    }

    private ResponseFormat parseTextFormat(Map<String, Object> text) {
        if (text == null) {
            return null;
        }
        Object fmtObj = text.get("format");
        if (!(fmtObj instanceof Map<?, ?> f)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> format = (Map<String, Object>) f;
        Object typeObj = format.get("type");
        String type = typeObj != null ? typeObj.toString() : "text";
        return switch (type) {
            case "text" -> new ResponseFormat.Text();
            case "json_object" -> new ResponseFormat.JsonObject();
            case "json_schema" -> {
                String name = format.get("name") != null ? format.get("name").toString() : "response";
                Object schemaObj = format.get("schema");
                if (!(schemaObj instanceof Map<?, ?>)) {
                    throw new GatewayException("INVALID_REQUEST",
                            "text.format.schema is required (an object) when type is json_schema");
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> schema = (Map<String, Object>) schemaObj;
                boolean strict = Boolean.TRUE.equals(format.get("strict"));
                yield new ResponseFormat.JsonSchema(name, schema, strict);
            }
            default -> throw new GatewayException("INVALID_REQUEST", "Unknown text.format.type: " + type);
        };
    }

    ResponseResult toResponseResult(ChatResponse resp) {
        String outputText = extractText(resp);
        ResponseResult.ContentPart part = ResponseResult.ContentPart.builder()
                .type("output_text").text(outputText).build();
        ResponseResult.OutputItem item = ResponseResult.OutputItem.builder()
                .type("message").id("msg_" + shortId()).status("completed").role("assistant")
                .content(List.of(part)).build();
        ResponseResult.Usage usage = resp.getUsage() == null ? null
                : ResponseResult.Usage.builder()
                        .inputTokens(resp.getUsage().getPromptTokens())
                        .outputTokens(resp.getUsage().getCompletionTokens())
                        .totalTokens(resp.getUsage().getTotalTokens())
                        .build();
        return ResponseResult.builder()
                .id("resp_" + shortId())
                .object("response")
                .createdAt(resp.getCreated() > 0 ? resp.getCreated() : Instant.now().getEpochSecond())
                .model(resp.getModel())
                .status("completed")
                .output(List.of(item))
                .usage(usage)
                .build();
    }

    private String extractText(ChatResponse resp) {
        if (resp.getChoices() == null || resp.getChoices().isEmpty()) {
            return "";
        }
        MultimodalMessage msg = resp.getChoices().get(0).getMessage();
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        return msg.getContent().stream()
                .filter(b -> b instanceof ContentBlock.TextBlock)
                .map(b -> ((ContentBlock.TextBlock) b).text())
                .collect(Collectors.joining("\n"));
    }

    // -------------------------------------------------------------------------
    // Small helpers
    // -------------------------------------------------------------------------

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static Map<String, Object> mapOf(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> messageItem(String id, String status, List<?> content) {
        return mapOf("type", "message", "id", id, "status", status, "role", "assistant", "content", content);
    }

    private static Map<String, Object> responseObject(String id, String model, String status,
                                                      List<?> output, long createdAt) {
        return mapOf("id", id, "object", "response", "created_at", createdAt,
                "status", status, "model", model, "output", output);
    }

}