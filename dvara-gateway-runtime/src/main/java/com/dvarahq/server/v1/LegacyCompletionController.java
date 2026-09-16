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

import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.MultimodalMessage;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.service.ChatExecutionService;
import com.dvarahq.server.service.ProviderDispatcher;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.server.v1.dto.CompletionRequest;
import com.dvarahq.server.v1.dto.CompletionResponse;
import com.dvarahq.server.web.AccessLogFilter;
import com.dvarahq.server.web.TraceIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The legacy text-completion doorway, {@code POST /v1/completions}.
 *
 * <p>A translation layer, like {@link ResponsesController}: the prompt becomes one user message on
 * an internal {@link ChatRequest}, and everything from there (the governance pipeline, the
 * response cache, metering and cost) is {@link ChatExecutionService}, the same execution line
 * {@code /v1/chat/completions} uses, so a filter added to the pipeline governs all three doorways.
 *
 * <p>What the legacy shape cannot carry is refused, never dropped: {@code stream}, {@code n}
 * above one and {@code best_of} above one return {@code UNSUPPORTED_CAPABILITY} (400).
 * {@code user} is accepted and ignored, as OpenAI documents it.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Completions", description = "Legacy text completion endpoint")
public class LegacyCompletionController {

    private static final String CACHE_HEADER = "X-Cache";

    // Built from the injected deps rather than injected as a bean, for the reason the other two
    // doorways give: the @WebMvcTest slice stays wired to the same mocks.
    private final ChatExecutionService executionService;

    public LegacyCompletionController(ProviderDispatcher dispatcher,
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
                                      org.springframework.beans.factory.ObjectProvider<CallOutcomeListener> outcomeListeners) {
        this.executionService = new ChatExecutionService(dispatcher, requestPipeline, responseCache,
                tokenUsageRepository, usageListeners, costCalculationService, costEstimator,
                piiEnforcer, rateLimiter,
                streamingResponseEnforcer, auditWriter, priorityAdmissionController, metrics, tokenEstimator,
                outcomeListeners);
    }

    @PostMapping("/completions")
    @Operation(summary = "Create text completion (legacy)",
            description = "Legacy text completion endpoint. The prompt is wrapped as a chat message and "
                    + "runs the same governance, cache and metering line as /v1/chat/completions.")
    @ApiResponse(responseCode = "200", description = "Text completion response")
    @ApiResponse(responseCode = "400", description = "Invalid request, unsupported field, or no provider available")
    public ResponseEntity<CompletionResponse> completions(
            @Valid @RequestBody CompletionRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);
        // Before any refusal, as the other doorways do, so the access log, the response audit and the
        // request metrics attribute a refused request to its model and its requested mode too.
        httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, request.getModel());
        httpRequest.setAttribute(AccessLogFilter.ATTR_STREAM, String.valueOf(Boolean.TRUE.equals(request.getStream())));
        rejectUnsupported(request);
        ChatRequest internal = toInternal(request);

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
            return builder.body(toCompletionResponse(response));
        } finally {
            executionService.releasePriority(ctx);
        }
    }

    /**
     * Fields the legacy shape cannot honour are refused, never silently dropped.
     *
     * <p>The prompt must be a single string. The legacy shape also allows an array of strings (several
     * prompts, several completions) and token arrays (encoded input); this doorway returns one choice
     * for one message, so either would be answered with a fabricated prompt — the array's Java
     * rendering — and that is a refusal, not a translation.
     */
    static void rejectUnsupported(CompletionRequest request) {
        if (!(request.getPrompt() instanceof String)) {
            throw unsupported("prompt must be a single string on /v1/completions; arrays of prompts and token arrays are not supported");
        }
        if (Boolean.TRUE.equals(request.getStream())) {
            throw unsupported("stream is not supported on /v1/completions; use /v1/chat/completions with stream=true");
        }
        if (request.getN() != null && request.getN() != 1) {
            throw unsupported("n must be 1 on /v1/completions; one choice is returned");
        }
        if (request.getBestOf() != null && request.getBestOf() != 1) {
            throw unsupported("best_of must be 1 on /v1/completions");
        }
        if (request.getLogprobs() != null && request.getLogprobs() > 0) {
            throw unsupported("logprobs are not supported on /v1/completions; the response carries none");
        }
    }

    private static GatewayException unsupported(String message) {
        return new GatewayException("UNSUPPORTED_CAPABILITY", message);
    }

    static ChatRequest toInternal(CompletionRequest request) {
        String promptText = (String) request.getPrompt();   // rejectUnsupported guarantees the type
        return ChatRequest.builder()
                .model(request.getModel())
                .messages(List.of(MultimodalMessage.user(promptText)))
                .maxTokens(request.getMaxTokens())
                .temperature(request.getTemperature())
                .topP(request.getTopP())
                .stop(ChatInputs.stopSequences(request.getStop()))
                .seed(request.getSeed())
                .build();
    }

    private static CompletionResponse toCompletionResponse(ChatResponse chatResponse) {
        String completionText = chatResponse.getChoices().isEmpty() ? ""
                : chatResponse.getChoices().get(0).getMessage().getContent().stream()
                        .filter(b -> b instanceof com.dvarahq.core.model.ContentBlock.TextBlock)
                        .map(b -> ((com.dvarahq.core.model.ContentBlock.TextBlock) b).text())
                        .findFirst().orElse("");
        return CompletionResponse.builder()
                .id(chatResponse.getId())
                .object("text_completion")
                .created(chatResponse.getCreated())
                .model(chatResponse.getModel())
                .choices(List.of(
                        CompletionResponse.Choice.builder()
                                .text(completionText)
                                .index(0)
                                .finishReason(chatResponse.getChoices().isEmpty() ? "stop"
                                        : chatResponse.getChoices().get(0).getFinishReason())
                                .build()
                ))
                .usage(chatResponse.getUsage() == null ? null
                        : CompletionResponse.Usage.builder()
                                .promptTokens(chatResponse.getUsage().getPromptTokens())
                                .completionTokens(chatResponse.getUsage().getCompletionTokens())
                                .totalTokens(chatResponse.getUsage().getTotalTokens())
                                .build())
                .build();
    }

}
