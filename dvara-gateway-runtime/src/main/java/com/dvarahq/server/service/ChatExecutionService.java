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
package com.dvarahq.server.service;

import com.dvarahq.core.id.Ids;
import com.dvarahq.providers.support.CredentialInterceptor;
import com.dvarahq.core.audit.AuditEvent;
import com.dvarahq.core.audit.AuditWriter;
import com.dvarahq.core.cache.ResponseCache;
import com.dvarahq.core.cost.CostCalculationService;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.RequestPipeline;
import com.dvarahq.core.guardrail.StreamingResponseEnforcer;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.metering.TokenUsageRecord;
import com.dvarahq.core.metering.TokenUsageRepository;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.pii.PiiEnforcer;
import com.dvarahq.core.policy.PolicyDecision;
import com.dvarahq.core.policy.PolicyWarning;
import com.dvarahq.core.ratelimit.RateLimiter;
import com.dvarahq.core.routing.PriorityAdmissionController;
import com.dvarahq.core.routing.PriorityTier;
import com.dvarahq.core.metering.CallOutcomeListener;
import com.dvarahq.core.metering.WorkspaceUsageListener;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.web.AccessLogFilter;
import com.dvarahq.server.web.ApiKeyAuthFilter;
import com.dvarahq.server.web.RateLimitServletFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * The shared chat-execution body: governance preamble, dispatch, cache, and metering. The chat,
 * Responses and embeddings endpoints all run this same path so they cannot drift. Each endpoint
 * keeps its own wire-DTO mapping and its own SSE emit loop; only the execution below is shared.
 */
@org.springframework.stereotype.Component
public class ChatExecutionService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ChatExecutionService.class);


    // A bean so EmbeddingController can inject it. ChatCompletionController and ResponsesController
    // construct their own so their @WebMvcTest slices wire the same mocks as the controller under
    // test; the class holds no mutable state, so several instances behave identically.


    /** set before postDispatch; read by filters that report on the upstream call. */
    public static final String ATTR_UPSTREAM_LATENCY_MS = "upstream_latency_ms";
    public static final String ATTR_UPSTREAM_COST_USD = "upstream_cost_usd";

    private static long totalTokensOf(ChatResponse response) {
        return response != null && response.getUsage() != null ? response.getUsage().getTotalTokens() : 0L;
    }

    private final ProviderDispatcher dispatcher;
    private final RequestPipeline requestPipeline;
    /** Null when no cache is registered; every request is then a miss. */
    private final ResponseCache responseCache;
    private final TokenUsageRepository tokenUsageRepository;
    /**
     * Every listener that wants to hear about a usage row, often none. A list rather than one bean
     * with a no-op default, so an application or another module can register its own alongside.
     */
    private final List<WorkspaceUsageListener> usageListeners;
    /** Null when nothing here can price a call, and then no cost row is written. */
    private final CostCalculationService costCalculationService;
    /** a PURE cost computation, unlike costCalculationService which also persists. */
    private final CostEstimator costEstimator;
    private final PiiEnforcer piiEnforcer;
    private final RateLimiter rateLimiter;
    private final StreamingResponseEnforcer streamingResponseEnforcer;
    private final AuditWriter auditWriter;
    /** Null when nothing admits by priority. A slot is then never taken, so never released. */
    private final PriorityAdmissionController priorityAdmissionController;
    private final GatewayMetrics metrics;
    private final TokenEstimator tokenEstimator;
    /** @see #usageListeners — same shape, same reason. */
    private final List<CallOutcomeListener> outcomeListeners;

    public ChatExecutionService(ProviderDispatcher dispatcher,
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
        this.dispatcher = dispatcher;
        this.requestPipeline = requestPipeline;
        this.responseCache = responseCache.getIfAvailable();
        this.tokenUsageRepository = tokenUsageRepository;
        this.usageListeners = usageListeners.orderedStream().toList();
        this.costCalculationService = costCalculationService.getIfAvailable();
        this.costEstimator = costEstimator.getIfAvailable();
        this.piiEnforcer = piiEnforcer;
        this.rateLimiter = rateLimiter;
        this.streamingResponseEnforcer = streamingResponseEnforcer;
        this.auditWriter = auditWriter;
        this.priorityAdmissionController = priorityAdmissionController.getIfAvailable();
        this.metrics = metrics;
        this.tokenEstimator = tokenEstimator;
        this.outcomeListeners = outcomeListeners.orderedStream().toList();
    }

    /** The (post-preDispatch) request plus the governance context, from {@link #prepare}. */
    public record Prepared(ChatRequest request, FilterContext ctx) {}

    /** The result of {@link #executeSync}: the response and whether it came from cache. */
    public record SyncResult(ChatResponse response, boolean cacheHit) {}

    /**
     * Governance preamble — identical for the sync and streaming paths, and for any endpoint
     * that maps into the internal {@link ChatRequest}: inject the server-resolved workspace, run
     * {@code preDispatch}, mirror the policy/downgrade/priority attributes onto the request,
     * audit any budget warnings, and set context-window warning headers. Returns the possibly
     * rewritten request + the context, from which callers read {@code policyDecision} and the
     * response headers filters asked for. There is no budget field on it: a filter that enforces
     * budgets, if one is registered, leaves its result and its headers as attributes.
     */
    public Prepared prepare(ChatRequest internal, HttpServletRequest httpRequest,
                            HttpServletResponse httpResponse, String traceId) {
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");
        // The key's opaque id, not the bearer token: budget caps are keyed on ApiKey.id, and this is
        // the value every downstream filter sees as FilterContext.apiKey.
        String apiKeyId = (String) httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR);
        injectResolvedWorkspace(internal, workspaceId);

        FilterContext ctx = FilterContext.builder()
                .workspaceId(workspaceId).apiKey(apiKeyId).traceId(traceId).build();
        try {
            internal = requestPipeline.preDispatch(internal, ctx);

            PolicyDecision policyDecision = ctx.getPolicyDecision();
            setPolicyAttributes(httpRequest, policyDecision);
            if (policyDecision != null && policyDecision.hasWarnings()) {
                auditBudgetWarning(httpRequest, policyDecision, workspaceId);
            }
            if (ctx.getDowngradedModel() != null) {
                httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, ctx.getDowngradedModel());
            }
            httpRequest.setAttribute("priorityTier", ctx.getPriorityTier());
            applyContextWindowHeaders(httpResponse, ctx);
            return new Prepared(internal, ctx);
        } catch (RuntimeException e) {
            // The callers wrap executeSync in try/finally { releasePriority }, but this method runs
            // before that try. Priority admission happens at order 600 and a later filter can still
            // refuse, so a refusal after admission would otherwise hold the slot for ever. A refusal
            // before admission leaves no tier on the context and releasePriority is a no-op for it.
            releasePriority(ctx);
            throw e;
        }
    }

    /**
     * Non-streaming execution: PII-keyed cache lookup, dispatch, post-dispatch pipeline, cache
     * store, and metering (tokens / usage row / cost / threshold cascade). Returns the response
     * and whether it was a cache hit; the caller maps it to its wire DTO and sets headers.
     * Wrap this in a {@code try/finally { releasePriority(ctx) }} exactly as the caller owns the
     * response lifecycle.
     */
    public SyncResult executeSync(ChatRequest internalRequest, FilterContext ctx, HttpServletRequest httpRequest) {
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");
        boolean bypassCache = "no-cache".equalsIgnoreCase(httpRequest.getHeader("X-Cache-Control"));

        // PII-stripped form used as the cache key on BOTH get and put so lookup and store hash
        // to the same key. The dispatcher below still calls upstream with the original request.
        ChatRequest cacheRequest = piiEnforcer.stripForCache(internalRequest, workspaceId);

        if (!bypassCache) {
            Optional<ChatResponse> cached =
                    responseCache == null ? Optional.empty() : responseCache.get(cacheRequest);
            if (cached.isPresent()) {
                // Drop the cached response's gateway headers first. They describe the upstream call
                // that filled the cache, not this one, so after this point anything on gatewayHeaders
                // was produced by this request and a controller can emit them unconditionally.
                // toBuilder, not a setter: the cache may hand back a shared instance.
                ChatResponse fromCache = cached.get().toBuilder().gatewayHeaders(null).build();

                // Run the output pipeline on the hit, so a guardrail, PII pattern or output schema
                // added since the entry was stored still applies. It runs on the tokenised form,
                // before detokenize, exactly as the miss path runs it before the cache write, so the
                // filters see the same bytes on a hit as on the miss that stored it. The route id is
                // lifted as on the miss path, or route-scoped post-dispatch filters would not apply.
                ctx.setSelectedRouteId((String) httpRequest.getAttribute("gateway.route.id"));
                ChatResponse governed;
                try {
                    governed = requestPipeline.postDispatch(internalRequest, fromCache, ctx);
                    // a hit has no upstream latency or cost; the listener still hears the call
                    outcomeListeners.forEach(listener ->
                            listener.callCompleted(ctx, 0L, 0.0, totalTokensOf(governed), false, false));
                } catch (RuntimeException refused) {
                    // Evict, so the cache does not go on storing a response the current policy will
                    // never allow. Eviction is best-effort and never masks the refusal.
                    try {
                        if (responseCache != null) {
                            responseCache.evict(cacheRequest);
                        }
                        log.info("Semantic cache entry evicted after the output pipeline refused it "
                                + "on a hit: workspace={} model={}", workspaceId, internalRequest.getModel());
                    } catch (RuntimeException evictFailed) {
                        log.warn("Cache evict failed after a refused hit (workspace={}): {}",
                                workspaceId, evictFailed.getMessage());
                    }
                    throw refused;
                }

                // The cache holds the tokenised form; detokenize only the response this caller
                // receives. Request-scoped: restores only tokens this request carried.
                ChatResponse response = piiEnforcer.detokenizeResponse(governed, internalRequest, workspaceId);
                recordTokens(httpRequest, response);
                persistTokenUsage(Attribution.capture(httpRequest), internalRequest, response, false, "HIT");
                // A hit made no upstream call, so no cost is booked; served volume is still counted
                // through the token-usage row above (cache_status=HIT).
                httpRequest.setAttribute(AccessLogFilter.ATTR_CACHE_STATUS, "HIT");
                enrichTokenAttributes(httpRequest, response);
                return new SyncResult(response, true);
            }
        }

        long dispatchStart = System.nanoTime();
        ChatResponse response = dispatcher.chat(internalRequest);
        long latencyMs = (System.nanoTime() - dispatchStart) / 1_000_000L;

        // Lift the matched route id onto the context so route-scoped post-dispatch filters see it.
        ctx.setSelectedRouteId((String) httpRequest.getAttribute("gateway.route.id"));

        // The upstream call's latency and cost, for post-dispatch filters and the outcome listeners,
        // which report on the call rather than govern it. Cost comes from CostEstimator rather than
        // costCalculationService, which also persists: calling that here would write the cost row
        // before postDispatch and leave one behind when a filter throws.
        double upstreamCostUsd = response == null || costEstimator == null
                ? 0.0 : costEstimator.calculateActualCost(internalRequest, response);
        ctx.setAttribute(ATTR_UPSTREAM_LATENCY_MS, latencyMs);
        ctx.setAttribute(ATTR_UPSTREAM_COST_USD, upstreamCostUsd);

        response = requestPipeline.postDispatch(internalRequest, response, ctx);
        // the upstream reported its own token counts on this path, so nothing here is an estimate
        for (CallOutcomeListener listener : outcomeListeners) {
            listener.callCompleted(ctx, latencyMs, upstreamCostUsd, totalTokensOf(response),
                    response == null, false);
        }

        if (responseCache != null) {
            responseCache.put(cacheRequest, response);
        }
        // restore PII tokens for the client ONLY after the cache write, so the
        // cache always holds the tokenised form (no raw PII served on future hits).
        // Request-scoped: restores only tokens the upstream request carried.
        response = piiEnforcer.detokenizeResponse(response, internalRequest, workspaceId);
        recordTokens(httpRequest, response);
        Attribution who = Attribution.capture(httpRequest);
        persistTokenUsage(who, internalRequest, response, false, "MISS");
        calculateCost(who, internalRequest, response);
        httpRequest.setAttribute(AccessLogFilter.ATTR_CACHE_STATUS, "MISS");
        enrichTokenAttributes(httpRequest, response);
        return new SyncResult(response, false);
    }

    /**
     * Shared streaming setup: dispatch the stream and wrap it in the guardrail/PII streaming
     * enforcer. The caller owns the SSE emit loop (each endpoint emits its own wire shape) and,
     * once the loop has ended and before it completes the emitter, calls
     * {@link #persistStreamingUsage} + {@link #releasePriority}.
     */
    public Iterator<SseChunk> openStream(ChatRequest streamRequest, String workspaceId) {
        Iterator<SseChunk> rawChunks = dispatcher.streamChat(streamRequest);
        try {
            return streamingResponseEnforcer.wrap(rawChunks, workspaceId, streamRequest);
        } catch (RuntimeException e) {
            // wrap() resolves the workspace posture AFTER the provider stream is open. If that
            // lookup throws, the caller never receives the raw iterator and cannot release its
            // response, so it is closed here before the failure propagates.
            if (rawChunks instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception closeEx) {
                    e.addSuppressed(closeEx);
                }
            }
            throw e;
        }
    }

    public void releasePriority(FilterContext ctx) {
        // A tier on the context means a slot was taken, which cannot happen without a controller.
        // This is called from a finally, and releasing a slot that was never acquired would drift
        // the controller's count negative and over-admit.
        if (priorityAdmissionController != null && ctx.getPriorityTier() != null) {
            priorityAdmissionController.release(PriorityTier.valueOf(ctx.getPriorityTier()));
        }
    }

    /**
     * Streaming counterpart to {@link #persistTokenUsage}. Bills on the usage block the terminal
     * {@link SseChunk} carries when the upstream reported one ({@code estimated=false}), and
     * estimates from the accumulated output content — the delivered text, and the names and
     * arguments of streamed tool calls — plus the request inputs otherwise
     * ({@code estimated=true}). Skips only when no content was delivered AND nothing was reported.
     *
     * <p>It also tells the outcome listeners what the call cost, because this is the streaming
     * path's only tail and streaming has no {@code postDispatch} to carry it.
     *
     * @param latencyMs wall time from opening the stream to its last chunk, the same quantity the
     *                  sync path measures for the whole upstream call
     * @param error     whether the stream terminated abnormally; recorded even when tokens were
     *                  already emitted, so a half-delivered answer shows both output and an error
     */
    public void persistStreamingUsage(HttpServletRequest httpRequest, ChatRequest streamRequest,
                                      String outputText, FilterContext ctx, long latencyMs,
                                      boolean error) {
        persistStreamingUsage(httpRequest, streamRequest, outputText, ctx, latencyMs, error, null);
    }

    /**
     * @param reportedUsage the upstream's own usage block if the stream carried one, else
     *                      null — in which case the tokens are estimated and the row says so.
     */
    public void persistStreamingUsage(HttpServletRequest httpRequest, ChatRequest streamRequest,
                                      String outputText, FilterContext ctx, long latencyMs,
                                      boolean error, ChatResponse.Usage reportedUsage) {
        // Captures at call time, which is only safe before the emitter has completed. The
        // controllers capture before their emit loop starts and use the form below.
        persistStreamingUsage(httpRequest, streamRequest, outputText, ctx, latencyMs, error,
                reportedUsage, TokenSettlement.capture(httpRequest));
    }

    /**
     * Captures the attribution at call time; the controllers use the form below. If the container
     * has already recycled the request (a client that left, the emitter timeout), its attributes
     * are gone, and the row is attributed from the settlement instead: that snapshot was taken while
     * the request was live, and its limiter key is the key's id. A row is never written under an
     * identity nobody carried.
     */
    public void persistStreamingUsage(HttpServletRequest httpRequest, ChatRequest streamRequest,
                                      String outputText, FilterContext ctx, long latencyMs,
                                      boolean error, ChatResponse.Usage reportedUsage,
                                      TokenSettlement settlement) {
        Attribution who = Attribution.capture(httpRequest);
        if (who.apiKeyId() == null || who.apiKeyId().isBlank()) {
            who = new Attribution(settlement.limiterKey(), ctx.getWorkspaceId(), who.provider(),
                    who.credentialFingerprint());
        }
        persistStreamingUsage(httpRequest, streamRequest, outputText, ctx, latencyMs, error,
                reportedUsage, settlement, who);
    }

    /**
     * @param settlement  the limiter key and the reservation to settle against, captured before the
     *                    emit loop started — see {@link TokenSettlement}.
     * @param who         who the row and the cost record are attributed to, captured once the stream
     *                    was open — see {@link Attribution}. The tail reads nothing off
     *                    {@code httpRequest}; it only writes the token attributes for the access log,
     *                    and tolerates a request the container has already recycled.
     */
    public void persistStreamingUsage(HttpServletRequest httpRequest, ChatRequest streamRequest,
                                      String outputText, FilterContext ctx, long latencyMs,
                                      boolean error, ChatResponse.Usage reportedUsage,
                                      TokenSettlement settlement, Attribution who) {
        if ((outputText == null || outputText.isEmpty()) && reportedUsage == null) {
            // Nothing was served and nothing is known: a stream that failed before its first token
            // is an error the metering path does not record, and recording it here alone would put
            // an outlier in the listener's tally that appears in no other. A stream that delivered
            // no text but reported usage — a refusal by deferred enforcement, where the upstream
            // was consumed whole and then withheld — is billed on what it reported.
            return;
        }
        // Use the number the upstream reported, when it reported one. `estimated` means exactly
        // that: no upstream usage block.
        boolean estimated = reportedUsage == null || reportedUsage.getTotalTokens() <= 0;
        int inputTokens = estimated
                ? tokenEstimator.estimateTokens(streamRequest) : reportedUsage.getPromptTokens();
        int outputTokens = estimated
                ? tokenEstimator.estimateTokens(outputText) : reportedUsage.getCompletionTokens();
        int totalTokens = estimated ? inputTokens + outputTokens : reportedUsage.getTotalTokens();
        ChatResponse syntheticResponse = ChatResponse.builder()
                .model(streamRequest.getModel())
                .usage(ChatResponse.Usage.builder()
                        .promptTokens(inputTokens)
                        .completionTokens(outputTokens)
                        .totalTokens(totalTokens)
                        .build())
                .build();
        // Settle the caller's token window against the reservation made at admission, as the sync
        // path does. When the upstream sent no usage block the settled figure is the estimate.
        recordTokens(settlement, syntheticResponse);
        // The access log, metrics and audit filters record a stream on its async dispatch, which
        // the controller triggers only after this tail has run; this is where they get tokens.
        enrichTokenAttributes(httpRequest, syntheticResponse);
        persistTokenUsage(who, streamRequest, syntheticResponse, estimated, "MISS");
        calculateCost(who, streamRequest, syntheticResponse);

        // The same pure estimator the sync path uses, not costCalculationService, which would
        // write a second cost row on top of the one calculateCost above has persisted.
        double streamCost = costEstimator == null
                ? 0.0 : costEstimator.calculateActualCost(streamRequest, syntheticResponse);
        for (CallOutcomeListener listener : outcomeListeners) {
            listener.callCompleted(ctx, latencyMs, streamCost, totalTokens, error, estimated);
        }
    }

    /**
     * Metering for {@code /v1/embeddings}, on the same ledger as chat: a usage row and a cost
     * record. {@code estimated=false} because an embeddings response carries a real usage block;
     * {@code cacheStatus=MISS} because embeddings do not go through the response cache. An
     * embedding has no completion, so prompt tokens are the total.
     */
    public void persistEmbeddingUsage(HttpServletRequest httpRequest, ChatRequest governedRequest,
                                      int promptTokens) {
        if (promptTokens <= 0) {
            return;
        }
        ChatResponse synthetic = ChatResponse.builder()
                .model(governedRequest.getModel())
                .usage(ChatResponse.Usage.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(0)
                        .totalTokens(promptTokens)
                        .build())
                .build();
        Attribution who = Attribution.capture(httpRequest);
        persistTokenUsage(who, governedRequest, synthetic, false, "MISS");
        calculateCost(who, governedRequest, synthetic);
    }

    private void persistTokenUsage(Attribution who, ChatRequest internalRequest,
                                   ChatResponse response, boolean estimated, String cacheStatus) {
        ChatResponse.Usage usage = response.getUsage();
        int inputTokens = usage != null ? usage.getPromptTokens() : 0;
        int outputTokens = usage != null ? usage.getCompletionTokens() : 0;
        int totalTokens = usage != null ? usage.getTotalTokens() : 0;
        if (totalTokens <= 0 && !estimated) return;

        String apiKeyId = who.apiKeyId();
        String provider = who.provider();
        String workspaceId = who.workspaceId();
        String credentialFingerprint = who.credentialFingerprint();

        tokenUsageRepository.save(TokenUsageRecord.builder()
                .id(Ids.newId())
                .workspaceId(workspaceId != null ? workspaceId : "unknown")
                .apiKey(attributionId(apiKeyId))   // the key id; never the live key
                .model(internalRequest.getModel())
                .provider(provider != null ? provider : "unknown")
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(totalTokens)
                .estimated(estimated)
                .cacheStatus(cacheStatus)
                .credentialFingerprint(credentialFingerprint)
                .timestamp(Instant.now())
                .build());

        // A registered listener may, for example, emit a threshold event when a workspace crosses a
        // usage percentage. The list is usually empty.
        usageListeners.forEach(listener -> listener.usageRecorded(workspaceId));
    }

    private void calculateCost(Attribution who, ChatRequest internalRequest, ChatResponse response) {
        String apiKeyId = who.apiKeyId();
        String provider = who.provider();
        String workspaceId = who.workspaceId();
        if (costCalculationService == null) {
            return;
        }
        costCalculationService.calculateAndPersist(internalRequest, response, workspaceId,
                        attributionId(apiKeyId), provider)   // the key id; never the live key
                .ifPresent(costRecord ->
                        metrics.recordCost(workspaceId, internalRequest.getModel(), provider,
                                costRecord.getTotalCost().doubleValue()));
    }

    private void enrichTokenAttributes(HttpServletRequest httpRequest, ChatResponse response) {
        if (response.getUsage() == null) return;
        try {
            httpRequest.setAttribute(AccessLogFilter.ATTR_TOKENS_PROMPT,
                    String.valueOf(response.getUsage().getPromptTokens()));
            httpRequest.setAttribute(AccessLogFilter.ATTR_TOKENS_COMPLETION,
                    String.valueOf(response.getUsage().getCompletionTokens()));
            httpRequest.setAttribute(AccessLogFilter.ATTR_TOKENS_TOTAL,
                    String.valueOf(response.getUsage().getTotalTokens()));
        } catch (RuntimeException recycled) {
            // A stream the container completed first (disconnect, timeout) may reach this tail after
            // the request was recycled; the access log then goes without tokens, and the row and the
            // cost record — attributed from the snapshot — are still written.
            log.debug("Could not put the stream's tokens on a recycled request: {}", recycled.getMessage());
        }
    }

    /**
     * Who a usage row and a cost record are attributed to, captured while the request is certainly
     * live. The same hazard as {@link TokenSettlement}: on a client disconnect or the emitter
     * timeout the container completes the request before the streaming tail runs and may recycle
     * it. The key and workspace are on the request before the emit loop; the provider and the
     * credential fingerprint are stamped while the stream is opened. A controller captures the
     * first two before the loop and the rest right after {@code openStream}, and the tail never
     * reads the request again.
     */
    public record Attribution(String apiKeyId, String workspaceId, String provider, String credentialFingerprint) {
        /**
         * The half that is known once the stream is open — the provider the dispatcher stamped and
         * the credential the interceptor recorded while opening it — read from the request at that
         * point and kept. A request the container has recycled in the meantime (a client that left
         * while the upstream was being opened) keeps the values already captured.
         */
        public Attribution withUpstreamFrom(HttpServletRequest httpRequest) {
            try {
                String p = (String) httpRequest.getAttribute(AccessLogFilter.ATTR_PROVIDER);
                String f = (String) httpRequest.getAttribute(CredentialInterceptor.FINGERPRINT_ATTRIBUTE);
                return new Attribution(apiKeyId, workspaceId, p != null ? p : provider, f != null ? f : credentialFingerprint);
            } catch (RuntimeException recycled) {
                return this;
            }
        }

        public static Attribution capture(HttpServletRequest httpRequest) {
            return new Attribution(
                    (String) httpRequest.getAttribute(ApiKeyAuthFilter.API_KEY_ID_ATTR),
                    (String) httpRequest.getAttribute("workspaceId"),
                    (String) httpRequest.getAttribute(AccessLogFilter.ATTR_PROVIDER),
                    // Which upstream credential this call went out under, stamped by
                    // CredentialInterceptor. Null on a cache hit and on providers that need no
                    // secret: neither is attributable egress.
                    (String) httpRequest.getAttribute(CredentialInterceptor.FINGERPRINT_ATTRIBUTE));
        }
    }

    /**
     * What settling the caller's token window needs, captured while the request is certainly live.
     * On a client disconnect or the emitter timeout the container completes the request before
     * the streaming tail runs and may recycle it, clearing its attributes. A controller captures
     * this before its emit loop starts and hands it to {@link #persistStreamingUsage}.
     * {@link Attribution} does the same for who the row belongs to.
     */
    public record TokenSettlement(String limiterKey, int reserved, long reservationId) {
        public static TokenSettlement capture(HttpServletRequest httpRequest) {
            String apiKey = (String) httpRequest.getAttribute(RateLimitServletFilter.API_KEY_ATTR);
            Object reserved = httpRequest.getAttribute(RateLimitServletFilter.RESERVED_TOKENS_ATTR);
            Object reservationId = httpRequest.getAttribute(RateLimitServletFilter.RESERVATION_ID_ATTR);
            return new TokenSettlement(ApiKeyAuthFilter.requiredLimiterKey(httpRequest),
                    reserved instanceof Integer r ? r : 0,
                    reservationId instanceof Long id ? id : 0L);
        }
    }

    private void recordTokens(HttpServletRequest httpRequest, ChatResponse response) {
        recordTokens(TokenSettlement.capture(httpRequest), response);
    }

    private void recordTokens(TokenSettlement settlement, ChatResponse response) {
        if (response.getUsage() != null && response.getUsage().getTotalTokens() > 0) {
            rateLimiter.reconcileTokens(settlement.limiterKey(), settlement.reserved(),
                    response.getUsage().getTotalTokens(), settlement.reservationId());
        }
    }

    private void auditBudgetWarning(HttpServletRequest httpRequest, PolicyDecision policyDecision, String workspaceId) {
        for (PolicyWarning warning : policyDecision.warnings()) {
            metrics.recordBudgetWarning(workspaceId, warning.policyId());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("warning_type", warning.type());
            payload.put("message", warning.message());
            payload.put("policy_id", warning.policyId());
            payload.put("rule_id", warning.ruleId());
            payload.put("workspace_id", workspaceId);
            payload.put("path", httpRequest.getRequestURI());

            auditWriter.write(new AuditEvent(
                    Ids.newId(),
                    Instant.now(),
                    workspaceId,
                    "BUDGET_CAP_WARNING",
                    payload));
        }
    }

    private void setPolicyAttributes(HttpServletRequest httpRequest, PolicyDecision decision) {
        if (decision == null) return;
        httpRequest.setAttribute("policy.decision", decision.allowed() ? "ALLOWED" : "DENIED");
        if (decision.policyId() != null) {
            httpRequest.setAttribute("policy.policyId", decision.policyId());
        }
        if (decision.ruleId() != null) {
            httpRequest.setAttribute("policy.ruleId", decision.ruleId());
        }
    }

    private void applyContextWindowHeaders(HttpServletResponse httpResponse, FilterContext ctx) {
        Boolean contextWarning = ctx.getAttribute("context.warning");
        if (Boolean.TRUE.equals(contextWarning)) {
            httpResponse.setHeader("X-Context-Window-Warning", "true");
            Object utilization = ctx.getAttribute("context.utilization");
            if (utilization != null) {
                httpResponse.setHeader("X-Context-Window-Utilization", utilization + "%");
            }
        }
    }

    /**
     * Makes {@code metadata.workspace_id} on the internal request the server-resolved workspace ID
     * (from the hashed API key) and nothing else. Always overwrites rather than fill-when-absent so a
     * client can't cast itself into another workspace's canary scope by supplying {@code workspace_id}
     * in the body.
     *
     * <p>When no workspace was resolved (a request with no key, where keys are not required), a
     * {@code workspace_id} the caller sent is removed: the response cache, canary routing and
     * mock-provider telemetry read it, and an anonymous caller must not be able to name a real
     * workspace and read or fill its cache entries.
     *
     * <p>Copies into a fresh map first (the request-body map may be immutable). See
     * {@code CanaryRoutingStrategy}.
     */
    public static void injectResolvedWorkspace(ChatRequest request, String workspaceId) {
        Map<String, Object> existing = request.getMetadata();
        if (workspaceId == null) {
            if (existing != null && existing.containsKey("workspace_id")) {
                Map<String, Object> stripped = new LinkedHashMap<>(existing);
                stripped.remove("workspace_id");
                request.setMetadata(stripped);
            }
            return;
        }
        Map<String, Object> mutable = existing != null
                ? new LinkedHashMap<>(existing)
                : new LinkedHashMap<>();
        mutable.put("workspace_id", workspaceId);
        request.setMetadata(mutable);
    }

    /**
     * Wraps a streaming body so it runs with the request thread's {@code RequestContextHolder}
     * attributes bound. {@code ProviderDispatcher.setProviderAttribute} records which upstream
     * served a call through that ThreadLocal, and the streaming controllers dispatch on a virtual
     * thread where it is unset; without this the stream would record {@code provider=unknown}.
     *
     * <p>Must be called on the request thread: it captures the attributes at wrap time and binds
     * them only inside the returned body, unbinding in a finally so nothing outlives the stream.
     */
    public static Runnable withRequestContext(Runnable body) {
        RequestAttributes captured = RequestContextHolder.getRequestAttributes();
        if (captured == null) {
            return body; // no servlet request (internal call, test) — nothing to propagate
        }
        return () -> {
            RequestContextHolder.setRequestAttributes(captured);
            try {
                body.run();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        };
    }


    /**
     * The value telemetry rows carry in their {@code api_key} column: the key's opaque id. The id
     * is stable, unique and not a secret, and it is what per-key budget caps are keyed on, so a cap
     * can match a row. Every served request has one; a row with none would be a call nobody can be
     * shown to have made, so it is refused rather than written under a made-up identity.
     */
    private static String attributionId(String apiKeyId) {
        if (apiKeyId == null || apiKeyId.isBlank()) {
            throw new IllegalStateException("A completed call has no API key id to attribute it to;"
                    + " every served request carries one once ApiKeyAuthFilter has run.");
        }
        return apiKeyId;
    }

}