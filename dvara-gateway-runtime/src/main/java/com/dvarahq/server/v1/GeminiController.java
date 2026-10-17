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
import com.dvarahq.core.exception.ErrorEnvelope;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.filter.StreamStopCheck;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.GeminiGenerateContentBody;
import com.dvarahq.core.model.HeldDelivery;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.model.ToolCallDelta;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.service.ChatExecutionService;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.server.service.StreamStops;
import com.dvarahq.server.v1.dto.ErrorResponse;
import com.dvarahq.server.web.AccessLogFilter;
import com.dvarahq.server.web.GlobalExceptionHandler;
import com.dvarahq.server.web.TraceIdFilter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gemini's {@code generateContent} API, so a client built for it (Gemini CLI, the Google GenAI SDKs) can be
 * pointed at the gateway and governed. The client's base URL is the gateway's address followed by
 * {@code /v1/gemini}; the client adds {@code /v1beta/models/{model}:generateContent} itself.
 *
 * <ul>
 *   <li>{@code POST /v1/gemini/v1beta/models/{model}:generateContent}</li>
 *   <li>{@code POST /v1/gemini/v1beta/models/{model}:streamGenerateContent?alt=sse}</li>
 *   <li>{@code POST /v1/gemini/v1beta/models/{model}:countTokens}, which calls no model</li>
 * </ul>
 *
 * <p>The request runs the same {@link ChatExecutionService} line as {@code /v1/chat/completions}: policy,
 * PII, guardrails, budget, rate limits, streaming enforcement, metering and audit, on whichever provider
 * routing picks for the model in the path. On a route to Gemini the body goes on as the caller sent it, with
 * only the governed edits ({@link GeminiGenerateContentBody}), and Gemini's response, plain or streamed, comes
 * back as Gemini sent it, again with only the governed edits. A response that governance had to hold or
 * rebuild, or one from another provider, is written in Gemini's shape here ({@link GeminiGenerateContent}).
 * Errors come back in Google's envelope ({@code {"error":{"code":...,"message":...,"status":...}}}) with the
 * status the gateway would give on any other doorway; the filters that refuse before a controller runs write
 * the same envelope ({@link ErrorEnvelope}).</p>
 */
@RestController
@RequestMapping(GeminiGenerateContent.BASE + "/v1beta")
@Tag(name = "Gemini", description = "Gemini generateContent API (governed, multi-provider)")
public class GeminiController {

    private static final Logger log = LoggerFactory.getLogger(GeminiController.class);
    private static final String CACHE_HEADER = "X-Cache";
    /** Status and code mapping is the global handler's, so every doorway answers a failure alike. */
    private static final GlobalExceptionHandler ERRORS = new GlobalExceptionHandler();

    private final long streamingTimeoutMs;
    private final ChatExecutionService executionService;
    /** Asked between chunks whether a running stream may go on; usually none are registered. */
    private final StreamStops streamStops;

    public GeminiController(ProviderDispatcher dispatcher,
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
                            ObjectProvider<StreamStopCheck> streamStopChecks,
                            @Value("${dvara.llm-gateway.resilience.timeout.streaming-timeout-ms:120000}")
                            long streamingTimeoutMs) {
        this.streamingTimeoutMs = streamingTimeoutMs;
        this.streamStops = StreamStops.of(streamStopChecks);
        this.executionService = new ChatExecutionService(dispatcher, requestPipeline, responseCache,
                tokenUsageRepository, usageListeners, costCalculationService, costEstimator,
                piiEnforcer, rateLimiter,
                streamingResponseEnforcer, auditWriter, priorityAdmissionController, metrics, tokenEstimator,
                outcomeListeners);
    }

    /**
     * Every method of a model, named after the last colon, as Google's API names them. The model may span
     * several path segments, so a route name such as {@code openai/gpt-4o} can be asked for: the Google GenAI
     * SDKs put a model name with a slash in the path as it is.
     */
    @PostMapping("/models/{*target}")
    @Operation(summary = "Generate content, stream it, or count its tokens",
            description = "Gemini generateContent API: {model}:generateContent, {model}:streamGenerateContent "
                    + "(alt=sse) and {model}:countTokens. Run through the full governance and metering pipeline; "
                    + "a token count calls no model and bills nothing.")
    @ApiResponse(responseCode = "200", description = "GenerateContentResponse, an SSE stream of them, or a token count")
    @ApiResponse(responseCode = "400", description = "Invalid request or unsupported field")
    @ApiResponse(responseCode = "404", description = "A method this gateway does not serve")
    @ApiResponse(responseCode = "502", description = "Upstream provider error")
    public Object models(@PathVariable("target") String target,
                         @RequestParam(value = "alt", required = false) String alt,
                         @RequestBody(required = false) byte[] rawBody,
                         HttpServletRequest httpRequest,
                         HttpServletResponse httpResponse) {
        target = target.startsWith("/") ? target.substring(1) : target;
        int colon = target.lastIndexOf(':');
        String model = colon < 0 ? target : target.substring(0, colon);
        String method = colon < 0 ? "" : target.substring(colon + 1);
        return switch (method) {
            case "generateContent" -> generate(model, rawBody, false, httpRequest, httpResponse);
            case "streamGenerateContent" -> {
                if (!"sse".equals(alt)) {
                    throw new GatewayException("UNSUPPORTED_CAPABILITY",
                            "streamGenerateContent is served as server-sent events only; add alt=sse");
                }
                yield generate(model, rawBody, true, httpRequest, httpResponse);
            }
            case "countTokens" -> countTokens(model, rawBody, httpRequest);
            default -> throw new GatewayException("NOT_FOUND",
                    "This gateway serves generateContent, streamGenerateContent and countTokens, not '"
                            + method + "'");
        };
    }

    private Object generate(String model, byte[] rawBody, boolean stream,
                            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        GeminiGenerateContentBody body = GeminiGenerateContentBody.parse(rawBody);
        requireContents(body);
        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);
        httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, model);
        httpRequest.setAttribute(AccessLogFilter.ATTR_STREAM, String.valueOf(stream));

        ChatRequest internal = GeminiGenerateContent.toInternal(model, body, stream);
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
            String asSent = asGeminiSentIt(body, response);
            if (asSent != null) {
                return builder.contentType(MediaType.APPLICATION_JSON).body(asSent);
            }
            // Rebuilt from the governed answer; the parts of Gemini's own response it does not read are kept.
            ObjectNode upstream = body.upstreamResponse();
            return builder.body(GeminiGenerateContent.toResponse(response, prep.request().getModel(),
                    upstream == null ? null : GeminiGenerateContentBody.unreadParts(upstream)));
        } finally {
            executionService.releasePriority(ctx);
        }
    }

    /**
     * Gemini's own response, with the governed text written back, when a Gemini provider answered and the
     * governed answer still lines up with it part for part; null when the answer is to be rebuilt.
     */
    private static String asGeminiSentIt(GeminiGenerateContentBody body, ChatResponse response) {
        ObjectNode upstream = body.upstreamResponse();
        if (upstream == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            return null;
        }
        MultimodalMessage governed = response.getChoices().get(0).getMessage();
        ObjectNode out = GeminiGenerateContentBody.governedResponse(upstream, governed);
        return out == null ? null : GeminiGenerateContentBody.write(out);
    }

    private static void requireContents(GeminiGenerateContentBody body) {
        if (body.messages().stream().noneMatch(m -> !"system".equals(m.getRole()))) {
            throw new GatewayException("INVALID_REQUEST", "contents is required");
        }
    }

    /**
     * Gemini's token count: {@code {"totalTokens": N}} for the request as {@code generateContent} would send
     * it. On a route to Gemini, Gemini counts; on any other, the gateway estimates. The checks that govern
     * what the request says still run, since its text may go to a provider to be counted, but no model is
     * called and nothing is billed or counted as a call.
     */
    private Map<String, Object> countTokens(String model, byte[] rawBody, HttpServletRequest httpRequest) {
        GeminiGenerateContentBody body = countBody(rawBody);
        requireContents(body);
        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);
        httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, model);
        int tokens = executionService.countInputTokens(GeminiGenerateContent.toInternal(model, body, false),
                httpRequest, traceId);
        return GeminiGenerateContent.map("totalTokens", tokens);
    }

    /** A count's body is either the contents alone or a whole {@code generateContentRequest}. */
    private static GeminiGenerateContentBody countBody(byte[] rawBody) {
        GeminiGenerateContentBody body = GeminiGenerateContentBody.parse(rawBody);
        ObjectNode raw = body.raw();
        if (raw.get("generateContentRequest") instanceof ObjectNode inner) {
            inner.remove("model");
            return GeminiGenerateContentBody.parse(GeminiGenerateContentBody.write(inner)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return body;
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
        StreamStops.Probe stopProbe = streamStops.probe(httpRequest, streamRequest.getModel());

        // The upstream is opened before the answer starts, so a provider that refuses the call (a bad
        // parameter, an unknown model, an outage) gives the caller that status and the provider's reason, in
        // Google's error shape, instead of a 200 stream that ends in an error event the client may not read.
        long openStart = System.nanoTime();
        Iterator<SseChunk> opened;
        try {
            opened = executionService.openStream(streamRequest, workspaceId);
        } catch (RuntimeException refused) {
            try {
                executionService.persistStreamingUsage(httpRequest, streamRequest, "", ctx,
                        (System.nanoTime() - openStart) / 1_000_000L, true, null, settlement,
                        attribution.withUpstreamFrom(httpRequest));
            } catch (Exception persistEx) {
                log.warn("Failed to record the refused stream for trace {}: {}", traceId, persistEx.getMessage());
            }
            executionService.releasePriority(ctx);
            throw refused;
        }
        upstream.set(opened);
        ChatExecutionService.Attribution openedBy = attribution.withUpstreamFrom(httpRequest);

        Thread.startVirtualThread(ChatExecutionService.withRequestContext(() -> {
            long streamStart = openStart;
            Iterator<SseChunk> chunks = opened;
            Exception failure = null;
            ChatExecutionService.Attribution who = openedBy;
            ChunkWriter writer = new ChunkWriter(emitter, streamRequest.getModel());
            try {
                // A guard that holds content delivers it, governed, when the stream ends: Gemini's own events
                // are then not sent at all, since every one of them may carry content.
                boolean held = chunks instanceof HeldDelivery h && h.holdsContent();

                boolean sawDone = false;
                while (!completed.get() && chunks.hasNext()) {   // cancellation first; see ChatCompletionController
                    SseChunk chunk = chunks.next();
                    // Before the chunk is written: a stream that must end does not send it.
                    stopProbe.checkpoint();
                    if (chunk.getUsage() != null) {
                        reportedUsage.set(chunk.getUsage());
                    }
                    accumulate(chunk, output);
                    if (chunk.getGeminiParts() != null) {
                        // Parts the gateway does not read. Gemini's own event, which follows, carries them
                        // too; only when that event is held back are they written here, so never lost.
                        if (held) {
                            writer.unread(chunk.getGeminiParts());
                        }
                        continue;
                    }
                    if (!held && chunk.getGeminiData() != null) {
                        writer.asSent(chunk.getGeminiData());
                    } else {
                        writer.chunk(chunk, reportedUsage.get());
                    }
                    if (chunk.isDone()) {
                        sawDone = true;
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
                    log.debug("Gemini stream for trace {} ended after the client left: {}", traceId, e.getMessage());
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
                    com.dvarahq.server.web.CallFailures.recordStream(httpRequest, failure);
                } catch (RuntimeException recycled) {
                    log.debug("Could not record the stream error for trace {}: {}", traceId, recycled.getMessage());
                }
                if (!completed.get()) {
                    if (failure instanceof StreamStops.StreamStoppedException stopped) {
                        log.info("Gemini stream for trace {} stopped: {}", traceId, stopped.getCode());
                    } else {
                        log.warn("Gemini streaming error for trace {}: {}", traceId, failure.getMessage());
                    }
                    try {
                        writer.error(failure, traceId);
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

    /**
     * Writes Gemini's stream: one {@code data:} event per {@code GenerateContentResponse}, with no event name,
     * as Google's API sends them.
     *
     * <p>From a Gemini provider, each event goes as Gemini sent it ({@link #asSent}). Otherwise, or where
     * governance held the content and delivers it whole, each chunk is written here as one response with the
     * chunk's thought and text; the parts of Gemini's events the gateway does not read are still sent, as they
     * arrive ({@link #unread}). Gemini sends a function call whole, in one part, while other providers stream
     * its arguments in pieces; calls are therefore gathered and written whole with the final response, which
     * also carries the finish reason and usage.</p>
     */
    static final class ChunkWriter {

        private final SseEmitter emitter;
        private final String model;
        /** Calls by index, in order of appearance: id, name and the arguments so far. */
        private final Map<Integer, String[]> calls = new LinkedHashMap<>();

        ChunkWriter(SseEmitter emitter, String model) {
            this.emitter = emitter;
            this.model = model;
        }

        /** Parts the gateway does not read, as their own event, exactly as Gemini sent them. */
        @SuppressWarnings("unchecked")
        void unread(String parts) throws Exception {
            List<Map<String, Object>> list = JsonMapper.instance().readValue(parts, List.class);
            send(GeminiGenerateContent.candidate(list, null, null, model));
        }

        /** An event as Gemini sent it. */
        void asSent(String data) throws Exception {
            write(data);
        }

        /** A chunk in Gemini's shape; nothing when it carries nothing to say. */
        void chunk(SseChunk chunk, ChatResponse.Usage usage) throws Exception {
            List<Map<String, Object>> parts = new ArrayList<>();
            if (chunk.getThinking() != null && chunk.getThinking().text() != null
                    && !chunk.getThinking().text().isEmpty()) {
                parts.add(GeminiGenerateContent.thought(chunk.getThinking().text(), chunk.getThinking().signature()));
            }
            if (chunk.getDelta() != null && !chunk.getDelta().isEmpty()) {
                parts.add(GeminiGenerateContent.map("text", chunk.getDelta()));
            }
            if (chunk.getToolCalls() != null) {
                for (ToolCallDelta call : chunk.getToolCalls()) {
                    String[] held = calls.computeIfAbsent(call.index(), i -> new String[] {null, null, ""});
                    if (call.id() != null) {
                        held[0] = call.id();
                    }
                    if (call.name() != null) {
                        held[1] = call.name();
                    }
                    if (call.argumentsFragment() != null) {
                        held[2] = held[2] + call.argumentsFragment();
                    }
                }
            }
            if (chunk.isDone()) {
                for (String[] call : calls.values()) {
                    parts.add(GeminiGenerateContent.functionCall(call[0], call[1], call[2]));
                }
                send(GeminiGenerateContent.candidate(parts,
                        GeminiGenerateContent.finishReason(chunk.getFinishReason()), usage, model));
            } else if (!parts.isEmpty()) {
                send(GeminiGenerateContent.candidate(parts, null, null, model));
            }
        }

        /**
         * The stream's last event: Google's error object, so a client stops reading and reports it rather than
         * taking the answer as complete. The status follows the one the same refusal would have had up front.
         */
        void error(Exception failure, String traceId) throws Exception {
            int status;
            String code;
            String message;
            if (failure instanceof StreamStops.StreamStoppedException stopped) {
                StreamStopCheck.Stop stop = stopped.stop();
                status = stop.status();
                code = stop.code();
                message = stop.message();
            } else {
                code = failure instanceof GatewayException ge ? ge.getCode() : "PROVIDER_ERROR";
                status = "PROVIDER_RESPONSE_TOO_LARGE".equals(code) ? 413 : 502;
                message = failure.getMessage();
            }
            send(ErrorEnvelope.body(GeminiGenerateContent.BASE + "/", status, message,
                    code == null ? null : code.toLowerCase(java.util.Locale.ROOT), null, traceId, null));
        }

        private void send(Map<String, Object> data) throws Exception {
            write(JsonMapper.instance().writeValueAsString(data));
        }

        /**
         * One event, {@code data: <json>} and a blank line. The space after the colon is required: the Google
         * GenAI SDKs read an event only when it starts {@code data: } (a space after the colon), and the
         * event builder writes none of its own.
         */
        private void write(String json) throws Exception {
            emitter.send(SseEmitter.event().data(" " + json, MediaType.TEXT_PLAIN));
        }
    }

    // -------------------------------------------------------------------------
    // Errors in Google's envelope
    // -------------------------------------------------------------------------

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Map<String, Object>> gatewayError(GatewayException ex, HttpServletRequest request,
                                                            HttpServletResponse response) {
        if ("NOT_FOUND".equals(ex.getCode())) {
            return googleError(HttpStatusCode.valueOf(404), null, "not_found", ex.getMessage(),
                    (String) request.getAttribute(TraceIdFilter.ATTR));
        }
        ResponseEntity<ErrorResponse> base = ERRORS.handleGatewayException(ex, request, response);
        ErrorResponse.ErrorDetail detail = base.getBody() == null ? null : base.getBody().getError();
        return googleError(base.getStatusCode(), base.getHeaders(), detail == null ? null : detail.getCode(),
                detail == null ? "" : detail.getMessage(), detail == null ? null : detail.getTraceId());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex, HttpServletResponse response) {
        ErrorResponse.ErrorDetail detail = ERRORS.handleUnreadable(ex, response).getError();
        return googleError(HttpStatusCode.valueOf(400), null, detail.getCode(), detail.getMessage(), detail.getTraceId());
    }

    /** Anything else that escapes: a 500, in Google's envelope rather than the container's. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletResponse response) {
        ErrorResponse.ErrorDetail detail = ERRORS.handleGeneric(ex, response).getError();
        return googleError(HttpStatusCode.valueOf(500), null, detail.getCode(), detail.getMessage(), detail.getTraceId());
    }

    private static ResponseEntity<Map<String, Object>> googleError(HttpStatusCode status, HttpHeaders headers,
                                                                   String code, String message, String traceId) {
        Map<String, Object> out = ErrorEnvelope.body(GeminiGenerateContent.BASE + "/", status.value(),
                message == null ? "" : message, code, null, traceId, null);
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
}
