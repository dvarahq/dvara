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
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.model.AnthropicEvent;
import com.dvarahq.core.model.AnthropicMessagesBody;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.HeldDelivery;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ThinkingDelta;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.service.ChatExecutionService;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.v1.dto.ErrorResponse;
import com.dvarahq.server.v1.dto.MessagesRequest;
import com.dvarahq.server.web.AccessLogFilter;
import com.dvarahq.core.exception.ErrorEnvelope;
import com.dvarahq.server.web.GlobalExceptionHandler;
import com.dvarahq.server.web.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The Anthropic Messages API ({@code POST /v1/messages}), so a client built for it — Claude Code,
 * the Anthropic SDKs — can be pointed at the gateway and governed.
 *
 * <p>The request runs the same {@link ChatExecutionService} line as {@code /v1/chat/completions}: policy,
 * PII, guardrails, budget, rate limits, streaming enforcement, metering and audit, on whichever provider
 * routing picks. On a route to Anthropic the body goes on as the caller sent it, with only the governed edits
 * ({@link AnthropicMessagesBody}), and Anthropic's response, plain or streamed, comes back as Anthropic sent
 * it, again with only the governed edits. A response that governance had to hold or rebuild, or one from
 * another provider, is written in Anthropic's shape here (see {@link AnthropicMessages}).</p>
 *
 * <p>The {@code anthropic-version} header is required and must be the version this doorway speaks. The
 * {@code anthropic-beta} header is sent on to an Anthropic provider as it came. Errors come back in
 * Anthropic's envelope ({@code {"type":"error","error":{...}}}) with the status and code the gateway would
 * give on any other doorway; the filters that refuse before a controller runs write the same envelope
 * ({@link ErrorEnvelope}).</p>
 *
 * <p>{@code POST /v1/messages/count_tokens} counts a request's input tokens without calling a model.</p>
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Messages", description = "Anthropic Messages API (governed, multi-provider)")
public class MessagesController {

    private static final Logger log = LoggerFactory.getLogger(MessagesController.class);
    private static final String CACHE_HEADER = "X-Cache";
    /** Status and code mapping is the global handler's, so every doorway answers a failure alike. */
    private static final GlobalExceptionHandler ERRORS = new GlobalExceptionHandler();

    private final long streamingTimeoutMs;
    private final ChatExecutionService executionService;
    private final GatewayMetrics metrics;

    public MessagesController(ProviderDispatcher dispatcher,
                              RequestPipeline requestPipeline,
                              ObjectProvider<ResponseCache> responseCache,
                              TokenUsageRepository tokenUsageRepository,
                              ObjectProvider<WorkspaceUsageListener> usageListeners,
                              ObjectProvider<CostCalculationService> costCalculationService,
                              ObjectProvider<CostEstimator> costEstimator,
                              PiiEnforcer piiEnforcer,
                              RateLimiter rateLimiter,
                              StreamingResponseEnforcer streamingResponseEnforcer,
                              AuditWriter auditWriter,
                              ObjectProvider<PriorityAdmissionController> priorityAdmissionController,
                              GatewayMetrics metrics,
                              TokenEstimator tokenEstimator,
                              ObjectProvider<CallOutcomeListener> outcomeListeners,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${dvara.llm-gateway.resilience.timeout.streaming-timeout-ms:120000}")
                              long streamingTimeoutMs) {
        this.streamingTimeoutMs = streamingTimeoutMs;
        this.metrics = metrics;
        this.executionService = new ChatExecutionService(dispatcher, requestPipeline, responseCache,
                tokenUsageRepository, usageListeners, costCalculationService, costEstimator,
                piiEnforcer, rateLimiter,
                streamingResponseEnforcer, auditWriter, priorityAdmissionController, metrics, tokenEstimator,
                outcomeListeners);
    }

    @PostMapping("/messages")
    @Operation(summary = "Create a message",
            description = "Anthropic Messages API. Translated to the gateway's request and run through the full "
                    + "governance and metering pipeline. Streams Anthropic events when stream=true.")
    @ApiResponse(responseCode = "200", description = "Message object or SSE stream")
    @ApiResponse(responseCode = "400", description = "Invalid request, unsupported field or API version")
    @ApiResponse(responseCode = "502", description = "Upstream provider error")
    public Object messages(@RequestHeader(value = "anthropic-version", required = false) String version,
                           @RequestHeader(value = "anthropic-beta", required = false) String beta,
                           @RequestBody(required = false) byte[] rawBody,
                           HttpServletRequest httpRequest,
                           HttpServletResponse httpResponse) {
        AnthropicMessages.checkVersion(version);
        AnthropicMessagesBody body = AnthropicMessagesBody.parse(rawBody);
        MessagesRequest request = body.bind(MessagesRequest.class);
        requireModelAndMessages(request);
        if (request.getMaxTokens() == null) {
            throw new GatewayException("INVALID_REQUEST", "max_tokens is required");
        }
        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);
        boolean stream = Boolean.TRUE.equals(request.getStream());
        httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, request.getModel());
        httpRequest.setAttribute(AccessLogFilter.ATTR_STREAM, String.valueOf(stream));

        ChatRequest internal = AnthropicMessages.toInternal(request, beta, body);
        body.opaqueBlocks().forEach((type, count) -> metrics.recordAnthropicOpaqueBlocks("request", type, count));
        if (stream) {
            return handleStreaming(internal, traceId, httpRequest, httpResponse);
        }

        ChatExecutionService.Prepared prep = executionService.prepare(internal, httpRequest, httpResponse, traceId);
        FilterContext ctx = prep.ctx();
        try {
            ChatExecutionService.SyncResult result = executionService.executeSync(prep.request(), ctx, httpRequest);
            ChatResponse response = result.response();
            ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .header(TraceIdFilter.HEADER, traceId)
                    .header(CACHE_HEADER, result.cacheHit() ? "HIT" : "MISS");
            if (response.getGatewayHeaders() != null) {
                response.getGatewayHeaders().forEach(builder::header);
            }
            ContextResponseHeaders.apply(builder, ctx, ctx.getPolicyDecision());
            String asSent = asAnthropicSentIt(body, response);
            if (asSent != null) {
                return builder.contentType(MediaType.APPLICATION_JSON).body(asSent);
            }
            return builder.body(AnthropicMessages.toMessage(response, prep.request().getModel()));
        } finally {
            executionService.releasePriority(ctx);
        }
    }

    /**
     * Anthropic's own response, with the governed text written back, when an Anthropic provider answered and
     * the governed answer still lines up with it block for block; null when the answer is to be rebuilt.
     */
    private String asAnthropicSentIt(AnthropicMessagesBody body, ChatResponse response) {
        ObjectNode upstream = body.upstreamResponse();
        if (upstream == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return null;
        }
        MultimodalMessage governed = response.getChoices().get(0).getMessage();
        ObjectNode out = AnthropicMessagesBody.governedResponse(upstream, governed);
        if (out == null) {
            return null;
        }
        Map<String, Integer> opaque = new java.util.LinkedHashMap<>();
        AnthropicMessagesBody.responseMessage(upstream, opaque);
        opaque.forEach((type, count) -> metrics.recordAnthropicOpaqueBlocks("response", type, count));
        return AnthropicMessagesBody.write(out);
    }

    private static void requireModelAndMessages(MessagesRequest request) {
        if (request.getModel() == null || request.getModel().isBlank()) {
            throw new GatewayException("INVALID_REQUEST", "model is required");
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new GatewayException("INVALID_REQUEST", "messages is required");
        }
    }

    /**
     * Anthropic's token count: {@code {"input_tokens": N}} for the request as {@code /v1/messages} would send
     * it. On a route to Anthropic, Anthropic counts; on any other, the gateway estimates. The request checks
     * that govern what it says still run, since its text may go to a provider to be counted, but no model is
     * called and nothing is billed or counted as a call.
     */
    @PostMapping("/messages/count_tokens")
    @Operation(summary = "Count a message's input tokens",
            description = "Anthropic Messages API token count. Counted by Anthropic on an Anthropic route, "
                    + "estimated by the gateway otherwise. No model is called and nothing is billed.")
    @ApiResponse(responseCode = "200", description = "The input token count")
    @ApiResponse(responseCode = "400", description = "Invalid request, unsupported field or API version")
    public Map<String, Object> countTokens(@RequestHeader(value = "anthropic-version", required = false) String version,
                                           @RequestHeader(value = "anthropic-beta", required = false) String beta,
                                           @RequestBody(required = false) byte[] rawBody,
                                           HttpServletRequest httpRequest) {
        AnthropicMessages.checkVersion(version);
        AnthropicMessagesBody body = AnthropicMessagesBody.parse(rawBody);
        MessagesRequest request = body.bind(MessagesRequest.class);
        requireModelAndMessages(request);
        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);
        httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, request.getModel());
        int tokens = executionService.countInputTokens(AnthropicMessages.toInternal(request, beta, body),
                httpRequest, traceId);
        return AnthropicMessages.map("input_tokens", tokens);
    }

    // -------------------------------------------------------------------------
    // Streaming
    // -------------------------------------------------------------------------

    private SseEmitter handleStreaming(ChatRequest internal, String traceId,
                                       HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        ChatExecutionService.Prepared prep = executionService.prepare(internal, httpRequest, httpResponse, traceId);
        ChatRequest streamRequest = prep.request();
        FilterContext ctx = prep.ctx();
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");
        ContextResponseHeaders.apply(httpResponse, ctx, ctx.getPolicyDecision());

        AtomicReference<Iterator<SseChunk>> upstream = new AtomicReference<>();
        SseEmitter emitter = new SseEmitter(streamingTimeoutMs);
        AtomicBoolean completed = new AtomicBoolean(false);
        emitter.onCompletion(() -> completed.set(true));
        emitter.onTimeout(() -> {
            completed.set(true);
            // Releasing the transport returns a read parked on a stalled provider; see ChatCompletionController.
            if (upstream.get() instanceof com.dvarahq.core.model.ReleasableUpstream r) {
                r.releaseTransport();
            }
        });
        emitter.onError(e -> completed.set(true));

        StringBuilder output = new StringBuilder();
        AtomicReference<ChatResponse.Usage> reportedUsage = new AtomicReference<>();
        AtomicBoolean streamFailed = new AtomicBoolean();
        ChatExecutionService.TokenSettlement settlement = ChatExecutionService.TokenSettlement.capture(httpRequest);
        ChatExecutionService.Attribution attribution = ChatExecutionService.Attribution.capture(httpRequest);

        Thread.startVirtualThread(ChatExecutionService.withRequestContext(() -> {
            long streamStart = System.nanoTime();
            Iterator<SseChunk> chunks = null;
            Exception failure = null;
            ChatExecutionService.Attribution who = attribution;
            EventWriter events = new EventWriter(emitter, streamRequest.getModel());
            try {
                chunks = executionService.openStream(streamRequest, workspaceId);
                upstream.set(chunks);
                who = who.withUpstreamFrom(httpRequest);
                // A guard that holds content delivers it, governed, when the stream ends: Anthropic's own
                // events are then sent only where they carry no content.
                boolean held = chunks instanceof HeldDelivery h && h.holdsContent();

                boolean sawDone = false;
                while (!completed.get() && chunks.hasNext()) {   // cancellation first; see ChatCompletionController
                    SseChunk chunk = chunks.next();
                    if (chunk.getUsage() != null) {
                        reportedUsage.set(chunk.getUsage());
                    }
                    accumulate(chunk, output);
                    AnthropicEvent event = chunk.getAnthropicEvent();
                    if (event != null) {
                        countOpaque(event);
                        if (!held) {
                            events.asSent(event);
                        } else if ("message_start".equals(event.name()) || "ping".equals(event.name())) {
                            events.asSent(event);
                        } else if (event.opaque()) {
                            events.opaqueRenumbered(event);
                        }
                        if (chunk.isDone()) {
                            sawDone = true;
                            if (held) {
                                events.finish(chunk.getFinishReason(), reportedUsage.get());
                            }
                            break;
                        }
                        continue;
                    }
                    // Content the guard delivers itself, or a provider that is not Anthropic: in Anthropic's shape.
                    events.messageStart();
                    if (chunk.getThinking() != null) {
                        events.thinking(chunk.getThinking());
                    }
                    if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                        events.text(chunk.getDelta());
                    }
                    if (chunk.getToolCalls() != null) {
                        for (ToolCallDelta call : chunk.getToolCalls()) {
                            events.toolCall(call);
                        }
                    }
                    if (chunk.isDone()) {
                        sawDone = true;
                        events.finish(chunk.getFinishReason(), reportedUsage.get());
                        break;
                    }
                }
                // A stream that ends before its finish is incomplete, not complete; see ChatCompletionController.
                if (!sawDone && !completed.get()) {
                    throw new GatewayException("PROVIDER_ERROR",
                            "The upstream stream ended before it finished; the answer is incomplete");
                }
            } catch (Exception e) {
                if (completed.get()) {
                    log.debug("Messages stream for trace {} ended after the client left: {}", traceId, e.getMessage());
                } else {
                    failure = e;
                    streamFailed.set(true);
                }
            }

            // The guard learns of a cancellation only through close(); see ChatCompletionController.
            if (chunks instanceof AutoCloseable guard) {
                try {
                    guard.close();
                } catch (Exception closeEx) {
                    log.warn("Failed to close the streaming guard for trace {}: {}", traceId, closeEx.getMessage());
                }
            }
            try {
                executionService.persistStreamingUsage(httpRequest, streamRequest, output.toString(), ctx,
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
                    log.warn("Messages streaming error for trace {}: {}", traceId, failure.getMessage());
                    try {
                        if (upstream.get() != null) {
                            events.messageStart();
                        }
                        events.error(failure);
                        emitter.complete();
                    } catch (Exception ignored) {
                        // emitter already closed
                    }
                }
            }
        }));
        return emitter;
    }

    /** What the stream said, for metering when the upstream reports no usage. */
    private static void accumulate(SseChunk chunk, StringBuilder output) {
        if (chunk.getThinking() != null && chunk.getThinking().text() != null) {
            output.append(chunk.getThinking().text());
        }
        if (chunk.getDelta() != null) {
            output.append(chunk.getDelta());
        }
        if (chunk.getToolCalls() != null) {
            for (ToolCallDelta call : chunk.getToolCalls()) {
                if (call.name() != null) {
                    output.append(call.name());
                }
                if (call.argumentsFragment() != null) {
                    output.append(call.argumentsFragment());
                }
            }
        }
    }

    /** Counts a content block of a type the gateway does not read, when its first event arrives. */
    private void countOpaque(AnthropicEvent event) {
        if (!event.opaque() || !"content_block_start".equals(event.name())) {
            return;
        }
        try {
            JsonNode data = com.dvarahq.core.util.JsonMapper.instance().readTree(event.data());
            metrics.recordAnthropicOpaqueBlocks("response", data.path("content_block").path("type").asText(""), 1);
        } catch (Exception e) {
            log.debug("Could not read an opaque block's type: {}", e.getMessage());
        }
    }

    /**
     * Writes Anthropic's stream events.
     *
     * <p>From an Anthropic provider, each event goes as Anthropic sent it ({@link #asSent}). Otherwise, or
     * where governance held the content and delivers it whole, the events are written here:
     * {@code message_start}, {@code ping}, one {@code content_block_start} / {@code _delta} / {@code _stop}
     * run per text or tool-use block, then {@code message_delta} with the stop reason and usage, and
     * {@code message_stop}.</p>
     *
     * <p>Anthropic streams one block at a time. A tool call's fragments that come back after another
     * block has started cannot be put back into their own block, so that stream is failed rather than
     * delivered with a call split in two. Thinking blocks are written as Anthropic sent them:
     * {@code thinking_delta}s, then the {@code signature_delta}; a redacted block whole in its start.</p>
     */
    static final class EventWriter {

        private final SseEmitter emitter;
        private final String model;
        private boolean started;
        /** Anthropic's own message_delta was sent, so the end of the message is only message_stop. */
        private boolean deltaSent;
        private int nextIndex;
        private Integer openIndex;
        /** The tool call the open block carries, or -1 when the open block is text or thinking. */
        private int openTool = -1;
        /** The thinking block the open block carries, or -1 when it is text or a tool call. */
        private int openThinking = -1;
        private final Set<Integer> closedTools = new HashSet<>();
        /** Anthropic's block index → the index written, for blocks sent while other content is held. */
        private final Map<Integer, Integer> renumbered = new java.util.HashMap<>();

        EventWriter(SseEmitter emitter, String model) {
            this.emitter = emitter;
            this.model = model;
        }

        /** {@code message_start} and {@code ping}, once, unless Anthropic's own were sent. */
        void messageStart() throws Exception {
            if (started) {
                return;
            }
            started = true;
            Map<String, Object> message = AnthropicMessages.map(
                    "id", AnthropicMessages.messageId(), "type", "message", "role", "assistant",
                    "model", model, "content", List.of(), "stop_reason", null, "stop_sequence", null,
                    "usage", AnthropicMessages.map("input_tokens", 0, "output_tokens", 0));
            send("message_start", AnthropicMessages.map("type", "message_start", "message", message));
            send("ping", AnthropicMessages.map("type", "ping"));
        }

        /** An event as Anthropic sent it. */
        void asSent(AnthropicEvent event) throws Exception {
            switch (event.name()) {
                case "message_start" -> started = true;
                case "message_delta" -> deltaSent = true;
                case "content_block_start" -> {
                    int index = index(event.data());
                    if (index >= 0) {
                        nextIndex = Math.max(nextIndex, index + 1);
                    }
                }
                default -> { }
            }
            emitter.send(SseEmitter.event().name(event.name()).data(event.data(), MediaType.APPLICATION_JSON));
        }

        /**
         * An event of a block the gateway does not read, sent as it arrives while the content around it is
         * held: as Anthropic sent it, but numbered after the blocks already written.
         */
        void opaqueRenumbered(AnthropicEvent event) throws Exception {
            ObjectNode data = (ObjectNode) com.dvarahq.core.util.JsonMapper.instance().readTree(event.data());
            int upstreamIndex = data.path("index").asInt(-1);
            if ("content_block_start".equals(event.name())) {
                close();
                renumbered.put(upstreamIndex, nextIndex++);
            }
            Integer index = renumbered.get(upstreamIndex);
            if (index != null) {
                data.put("index", index);
            }
            emitter.send(SseEmitter.event().name(event.name())
                    .data(com.dvarahq.core.util.JsonMapper.instance().writeValueAsString(data), MediaType.APPLICATION_JSON));
        }

        private static int index(String data) {
            try {
                return com.dvarahq.core.util.JsonMapper.instance().readTree(data).path("index").asInt(-1);
            } catch (Exception e) {
                return -1;
            }
        }

        void thinking(ThinkingDelta fragment) throws Exception {
            if (openIndex == null || openThinking != fragment.index()) {
                close();
                Map<String, Object> block = fragment.redactedBlock()
                        ? AnthropicMessages.map("type", "redacted_thinking", "data", fragment.redacted())
                        : AnthropicMessages.map("type", "thinking", "thinking", "");
                open(block, -1);
                openThinking = fragment.index();
            }
            if (fragment.text() != null && !fragment.text().isEmpty()) {
                send("content_block_delta", AnthropicMessages.map("type", "content_block_delta", "index", openIndex,
                        "delta", AnthropicMessages.map("type", "thinking_delta", "thinking", fragment.text())));
            }
            if (fragment.signature() != null && !fragment.signature().isEmpty()) {
                send("content_block_delta", AnthropicMessages.map("type", "content_block_delta", "index", openIndex,
                        "delta", AnthropicMessages.map("type", "signature_delta", "signature", fragment.signature())));
            }
        }

        void text(String delta) throws Exception {
            if (openIndex == null || openTool != -1 || openThinking != -1) {
                close();
                open(AnthropicMessages.map("type", "text", "text", ""), -1);
            }
            send("content_block_delta", AnthropicMessages.map("type", "content_block_delta", "index", openIndex,
                    "delta", AnthropicMessages.map("type", "text_delta", "text", delta)));
        }

        void toolCall(ToolCallDelta call) throws Exception {
            boolean opener = call.id() != null || call.name() != null;
            if (openIndex == null || openTool != call.index()) {
                if (!opener || closedTools.contains(call.index())) {
                    throw new GatewayException("PROVIDER_ERROR",
                            "The upstream interleaved tool calls, which the Messages stream cannot carry");
                }
                close();
                open(AnthropicMessages.map("type", "tool_use", "id", call.id(), "name", call.name(),
                        "input", Map.of()), call.index());
            }
            String fragment = call.argumentsFragment();
            if (fragment != null && !fragment.isEmpty()) {
                send("content_block_delta", AnthropicMessages.map("type", "content_block_delta", "index", openIndex,
                        "delta", AnthropicMessages.map("type", "input_json_delta", "partial_json", fragment)));
            }
        }

        void finish(String finishReason, ChatResponse.Usage usage) throws Exception {
            close();
            if (deltaSent && !"content_filter".equals(finishReason)) {
                send("message_stop", AnthropicMessages.map("type", "message_stop"));
                return;
            }
            messageStart();
            send("message_delta", AnthropicMessages.map("type", "message_delta",
                    "delta", AnthropicMessages.map("stop_reason", AnthropicMessages.stopReason(finishReason),
                            "stop_sequence", null),
                    "usage", AnthropicMessages.usage(usage)));
            send("message_stop", AnthropicMessages.map("type", "message_stop"));
        }

        void error(Exception failure) throws Exception {
            String code = failure instanceof GatewayException ge ? ge.getCode() : "PROVIDER_ERROR";
            send("error", AnthropicMessages.map("type", "error", "error", AnthropicMessages.map(
                    "type", "PROVIDER_RESPONSE_TOO_LARGE".equals(code) ? "request_too_large" : "api_error",
                    "message", failure.getMessage(), "code", code.toLowerCase(java.util.Locale.ROOT))));
        }

        private void open(Map<String, Object> block, int tool) throws Exception {
            openIndex = nextIndex++;
            openTool = tool;
            send("content_block_start", AnthropicMessages.map("type", "content_block_start", "index", openIndex,
                    "content_block", block));
        }

        private void close() throws Exception {
            if (openIndex == null) {
                return;
            }
            send("content_block_stop", AnthropicMessages.map("type", "content_block_stop", "index", openIndex));
            if (openTool != -1) {
                closedTools.add(openTool);
            }
            openIndex = null;
            openTool = -1;
            openThinking = -1;
        }

        private void send(String name, Map<String, Object> data) throws Exception {
            emitter.send(SseEmitter.event().name(name)
                    .data(JsonMapper.instance().writeValueAsString(data), MediaType.APPLICATION_JSON));
        }
    }

    // -------------------------------------------------------------------------
    // Errors in Anthropic's envelope
    // -------------------------------------------------------------------------

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> gatewayError(GatewayException ex, HttpServletRequest request,
                                                            HttpServletResponse response) {
        ResponseEntity<ErrorResponse> base = ERRORS.handleGatewayException(ex, request, response);
        return anthropicError(base.getStatusCode(), base.getHeaders(), base.getBody());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException ex, HttpServletResponse response) {
        return anthropicError(HttpStatusCode.valueOf(400), null, ERRORS.handleValidation(ex, response));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex, HttpServletResponse response) {
        return anthropicError(HttpStatusCode.valueOf(400), null, ERRORS.handleUnreadable(ex, response));
    }

    /** Anything else that escapes: a 500, in Anthropic's envelope rather than the container's. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletResponse response) {
        return anthropicError(HttpStatusCode.valueOf(500), null, ERRORS.handleGeneric(ex, response));
    }

    private static ResponseEntity<Map<String, Object>> anthropicError(HttpStatusCode status,
                                                                      org.springframework.http.HttpHeaders headers,
                                                                      ErrorResponse body) {
        ErrorResponse.ErrorDetail detail = body == null ? null : body.getError();
        Map<String, Object> out = ErrorEnvelope.body("/v1/messages", status.value(),
                detail == null ? "" : detail.getMessage(), detail == null ? null : detail.getCode(),
                detail == null ? null : detail.getType(), detail == null ? null : detail.getTraceId(), null);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (headers != null) {
            headers.forEach((name, values) -> {
                if (!"Content-Type".equalsIgnoreCase(name)) {
                    builder.header(name, values.toArray(String[]::new));
                }
            });
        }
        return builder.body(out);
    }

    /** Anthropic's error types, by the status the gateway answers with. */
    static String errorType(int status) {
        return ErrorEnvelope.anthropicType(status);
    }
}
