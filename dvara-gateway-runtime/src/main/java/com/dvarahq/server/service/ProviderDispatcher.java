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

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;
import com.dvarahq.core.model.EmbeddingRequest;
import com.dvarahq.core.model.EmbeddingResponse;
import com.dvarahq.core.model.ResponseFormat;
import com.dvarahq.core.model.SseChunk;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.core.provider.RoutingStrategy;
import com.dvarahq.core.region.DataResidencyPolicy;
import com.dvarahq.core.region.RegionContext;
import com.dvarahq.core.resilience.FallbackResolver;
import com.dvarahq.core.resilience.ProviderHealthRegistry;
import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.routing.CanaryConfig;
import com.dvarahq.core.routing.CanaryMetricsCollector;
import com.dvarahq.core.routing.LatencyTracker;
import com.dvarahq.core.routing.RequestContext;
import com.dvarahq.core.routing.ShadowDispatcher;
import com.dvarahq.server.metrics.GatewayMetrics;
import com.dvarahq.server.web.TraceIdFilter;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.dvarahq.providers.support.CredentialInterceptor;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Selects the appropriate {@link LlmProvider} for a given request and delegates the call.
 * Uses a {@link RoutingStrategy} for provider selection (round-robin, weighted, or
 * model-prefix). Falls back to simple first-match if no routing strategy is configured.
 *
 * <p>When a primary provider fails with a retriable error (PROVIDER_ERROR or
 * PROVIDER_CIRCUIT_OPEN), the dispatcher iterates through fallback providers
 * (skipping unhealthy ones) before giving up.</p>
 *
 * <p>Capability-aware filtering is applied before routing and during failover:
 * providers that cannot handle the requested {@code response_format} are excluded
 * from the candidate pool. This is a correctness constraint, not an optimisation.</p>
 */
@Service
public class ProviderDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ProviderDispatcher.class);

    // OTel GenAI semantic-convention `gen_ai.system` values, keyed on the lower-cased
    // DVARA provider name. Providers with an enumerated semconv value are mapped; the
    // OpenAI-compatible long-tail (qwen/moonshot/chatglm/ollama/mock, …) falls back to
    // the lower-cased brand slug.
    private static final Map<String, String> GEN_AI_SYSTEM = Map.ofEntries(
            Map.entry("openai", "openai"),
            Map.entry("anthropic", "anthropic"),
            Map.entry("bedrock", "aws.bedrock"),
            Map.entry("azure-openai", "az.ai.openai"),
            Map.entry("gemini", "gcp.gemini"),
            Map.entry("cohere", "cohere"),
            Map.entry("mistral", "mistral_ai"),
            Map.entry("deepseek", "deepseek"),
            Map.entry("groq", "groq"),
            Map.entry("grok", "xai"));

    private final List<LlmProvider> providers;
    private final RoutingStrategy routingStrategy;
    private final ProviderHealthRegistry healthRegistry;
    private final FallbackResolver fallbackResolver;
    private final GatewayMetrics metrics;
    private final RegionContext regionContext;
    private final DataResidencyPolicy dataResidencyPolicy;
    private final ObservationRegistry observationRegistry;
    /** Null when nothing records latency. Recording into a sink that discards is not recording. */
    private final LatencyTracker latencyTracker;
    private final CanaryMetricsCollector canaryMetricsCollector;
    /** Null when nothing here can price a call; a canary comparison then records $0. */
    private final CostEstimator costEstimator;
    /** Null when no module dispatches shadow traffic. A configured shadow then says so at startup. */
    private final ShadowDispatcher shadowDispatcher;

    // OTel GenAI semantic-convention attributes (gen_ai.*) are emitted on the provider
    // spans in addition to the existing provider/model/token attributes. Default on;
    // an operator can suppress them (e.g. cardinality concerns) without losing the
    // legacy attributes. Field default true so direct (non-Spring) construction in
    // tests defaults to enabled.
    @Value("${management.tracing.genai-semconv.enabled:true}")
    private boolean genaiSemconvEnabled = true;

    public ProviderDispatcher(List<LlmProvider> providers,
                              RoutingStrategy routingStrategy,
                              ProviderHealthRegistry healthRegistry,
                              FallbackResolver fallbackResolver,
                              GatewayMetrics metrics,
                              RegionContext regionContext,
                              DataResidencyPolicy dataResidencyPolicy,
                              ObservationRegistry observationRegistry,
                              @org.springframework.lang.Nullable LatencyTracker latencyTracker,
                              CanaryMetricsCollector canaryMetricsCollector,
                              org.springframework.beans.factory.ObjectProvider<CostEstimator> costEstimator,
                              @org.springframework.lang.Nullable ShadowDispatcher shadowDispatcher) {
        this.providers = providers;
        this.routingStrategy = routingStrategy;
        this.healthRegistry = healthRegistry;
        this.fallbackResolver = fallbackResolver;
        this.metrics = metrics;
        this.regionContext = regionContext;
        this.dataResidencyPolicy = dataResidencyPolicy;
        this.observationRegistry = observationRegistry;
        this.latencyTracker = latencyTracker;
        this.canaryMetricsCollector = canaryMetricsCollector;
        this.costEstimator = costEstimator.getIfAvailable();
        this.shadowDispatcher = shadowDispatcher;
    }

    public ChatResponse chat(ChatRequest request) {
        RequestContext routingCtx = RequestContext.builder().build();
        LlmProvider primary = selectChat(request, routingCtx);
        final ChatRequest req = applyResolvedModel(request, routingCtx);
        recordIntelligentRoutingMetric(routingCtx);
        setProviderAttribute(primary.name());
        Observation obs = Observation.createNotStarted("gateway.provider.chat", observationRegistry)
                .lowCardinalityKeyValue("provider", primary.name())
                .lowCardinalityKeyValue("model", req.getModel());
        addGenAiRequestAttrs(obs, primary.name(), req.getModel(), "chat", req.getMaxTokens(), req.getTemperature());
        addSessionId(obs);
        obs.start();
        try {
            ChatResponse response;
            long startNanos = System.nanoTime();
            try {
                response = primary.chat(req);
                long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                if (latencyTracker != null) {
                    latencyTracker.record(primary.name(), req.getModel(), latencyMs);
                }
                recordCanaryMetrics(primary.name(), req, response, latencyMs, false, routingCtx);
            } catch (GatewayException e) {
                if (isFallbackEligible(e)) {
                    long errorLatencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                    recordCanaryMetrics(primary.name(), req, null, errorLatencyMs, true, routingCtx);
                    response = attemptFallback(req, primary, e, p -> {
                        long fbStart = System.nanoTime();
                        ChatResponse fbResponse = p.chat(req);
                        long fbLatencyMs = (System.nanoTime() - fbStart) / 1_000_000;
                        if (latencyTracker != null) {
                            latencyTracker.record(p.name(), req.getModel(), fbLatencyMs);
                        }
                        return fbResponse;
                    });
                } else {
                    long errorLatencyMs = (System.nanoTime() - startNanos) / 1_000_000;
                    recordCanaryMetrics(primary.name(), req, null, errorLatencyMs, true, routingCtx);
                    throw e;
                }
            }
            if (response.getUsage() != null) {
                obs.highCardinalityKeyValue("input_tokens", String.valueOf(response.getUsage().getPromptTokens()));
                obs.highCardinalityKeyValue("output_tokens", String.valueOf(response.getUsage().getCompletionTokens()));
            }
            addGenAiResponseAttrs(obs, response);

            // Shadow dispatch (async, never blocks primary response)
            dispatchShadowIfConfigured(req, response, startNanos, routingCtx);

            return response;
        } catch (Exception e) {
            obs.error(e);
            throw e;
        } finally {
            obs.stop();
        }
    }

    public Iterator<SseChunk> streamChat(ChatRequest request) {
        RequestContext routingCtx = RequestContext.builder().build();
        LlmProvider primary = selectChat(request, routingCtx);
        final ChatRequest req = applyResolvedModel(request, routingCtx);
        recordIntelligentRoutingMetric(routingCtx);
        setProviderAttribute(primary.name());
        Observation obs = Observation.createNotStarted("gateway.provider.stream", observationRegistry)
                .lowCardinalityKeyValue("provider", primary.name())
                .lowCardinalityKeyValue("model", req.getModel());
        addGenAiRequestAttrs(obs, primary.name(), req.getModel(), "chat", req.getMaxTokens(), req.getTemperature());
        addSessionId(obs);
        return obs.observe(() -> {
            try {
                return primary.streamChat(req);
            } catch (GatewayException e) {
                if (isFallbackEligible(e)) {
                    return attemptFallback(req, primary, e, p -> p.streamChat(req));
                }
                throw e;
            }
        });
    }

    /**
     * Resolves a batch-capable provider for the Batch API surface. A batch's file
     * upload, submit, poll, and results all must hit the <em>same</em> provider (the file id is
     * provider-scoped), so the surface resolves the provider once (optionally by an explicit
     * {@code providerHint} = provider name) and reuses it across the lifecycle, persisting the
     * resolved name on the batch job.
     *
     * @throws GatewayException {@code NO_CAPABLE_PROVIDER} if no registered provider advertises
     *                          {@code capabilities().supportsBatch()}, or the hint names one that
     *                          isn't batch-capable.
     */
    public LlmProvider selectBatchProvider(String providerHint) {
        List<LlmProvider> capable = providers.stream()
                .filter(p -> p.capabilities().supportsBatch())
                .toList();
        if (capable.isEmpty()) {
            throw new GatewayException("NO_CAPABLE_PROVIDER",
                    "No registered provider supports the Batch API. Configure an OpenAI or Azure OpenAI "
                            + "provider (dvara.llm-gateway.providers.openai.api-key).");
        }
        if (providerHint != null && !providerHint.isBlank()) {
            return capable.stream()
                    .filter(p -> p.name().equalsIgnoreCase(providerHint.trim()))
                    .findFirst()
                    .orElseThrow(() -> new GatewayException("NO_CAPABLE_PROVIDER",
                            "Provider '" + providerHint + "' is not a registered batch-capable provider."));
        }
        return capable.get(0);
    }

    public EmbeddingResponse embed(EmbeddingRequest request) {
        LlmProvider provider = providers.stream()
                .filter(p -> p.supportsEmbedding(request.getModel()))
                .findFirst()
                .orElseThrow(() -> new GatewayException("NO_PROVIDER",
                        "No provider configured for embedding model: " + request.getModel()
                        + ". Configure dvara.llm-gateway.providers.openai.api-key to enable OpenAI embeddings."));
        Observation obs = Observation.createNotStarted("gateway.provider.embed", observationRegistry)
                .lowCardinalityKeyValue("provider", provider.name())
                .lowCardinalityKeyValue("model", request.getModel());
        addGenAiRequestAttrs(obs, provider.name(), request.getModel(), "embeddings", null, null);
        return obs.observe(() -> {
            setProviderAttribute(provider.name());   // the embed path never stamped its provider
            try {
                return provider.embed(request);
            } catch (GatewayException e) {
                if (isFallbackEligible(e)) {
                    // For embeddings, find other embedding-capable providers
                    List<LlmProvider> fallbacks = providers.stream()
                            .filter(p -> p.supportsEmbedding(request.getModel()))
                            .filter(p -> !p.name().equals(provider.name()))
                            .filter(p -> healthRegistry.isAvailable(p.name()))
                            .toList();
                    for (LlmProvider fallback : fallbacks) {
                        try {
                            log.info("Embedding fallback from [{}] to [{}]", provider.name(), fallback.name());
                            // The same two moves as attemptFallback: the row's provider follows the
                            // fallback, and the failed primary's credential does not.
                            setProviderAttribute(fallback.name());
                            clearCredentialFingerprint();
                            return fallback.embed(request);
                        } catch (GatewayException fe) {
                            log.warn("Embedding fallback provider [{}] also failed: {}", fallback.name(), fe.getMessage());
                        }
                    }
                }
                throw e;
            }
        });
    }

    public List<LlmProvider> allProviders() {
        return List.copyOf(providers);
    }

    private ChatRequest applyResolvedModel(ChatRequest request, RequestContext ctx) {
        String model = ctx.getResolvedModel();
        if (model == null) return request;
        // toBuilder() rather than a hand-rolled copy, so every field (topP, tools, toolChoice...) carries over.
        return request.toBuilder().model(model).build();
    }

    private LlmProvider selectChat(ChatRequest request, RequestContext ctx) {
        List<LlmProvider> capable = capabilityFilter(providers, request);
        if (capable.isEmpty() && hasResponseFormatRequirement(request)) {
            String formatType = responseFormatTypeName(request.getResponseFormat());
            List<String> providerNames = providers.stream().map(LlmProvider::name).toList();
            throw new GatewayException("NO_CAPABLE_PROVIDER",
                    "No provider supports response_format: " + formatType
                    + ". Providers on route: " + providerNames
                    + ". Capable providers: []");
        }
        if (capable.isEmpty() && hasToolsRequirement(request)) {
            // tools were requested but no provider on the route supports
            // function calling. Reject explicitly rather than drop the tools.
            List<String> providerNames = providers.stream().map(LlmProvider::name).toList();
            throw new GatewayException("NO_CAPABLE_PROVIDER",
                    "No provider supports " + toolsRequirementName(request) + ". Providers on route: "
                    + providerNames + ". Capable providers: []");
        }

        // Filter out unhealthy providers (circuit breakers open) before the routing strategy
        // selects, so a strategy that does not check health itself cannot pick one and pay an
        // extra failed round-trip before attemptFallback recovers.
        List<LlmProvider> healthy = capable.stream()
                .filter(p -> healthRegistry.isAvailable(p.name()))
                .toList();
        LlmProvider selected;
        if (healthy.isEmpty() && !capable.isEmpty()) {
            // All capable providers unhealthy. Hand the full capable list to
            // the strategy anyway so it can produce its own NO_PROVIDER
            // error with a familiar message, rather than silently changing
            // the error code from the original semantic.
            selected = routingStrategy.route(request, capable, ctx);
        } else {
            selected = routingStrategy.route(request, healthy, ctx);
        }

        // Expose the matched route id so post-dispatch filters can scope on
        // it (e.g. registered output schemas with routeId). Mirrors the
        // setProviderAttribute / setCanaryVariantAttribute pattern.
        if (ctx.getMatchedRoute() != null) {
            setRouteIdAttribute(ctx.getMatchedRoute().getId());
        }
        return selected;
    }

    private void setRouteIdAttribute(String routeId) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.getRequest().setAttribute("gateway.route.id", routeId);
        }
    }

    private boolean isFallbackEligible(GatewayException e) {
        // PROVIDER_RATE_LIMITED: the upstream credential is near quota — try the next provider on
        // the route before surfacing a 429 to the client, exactly like a provider error or open circuit.
        return "PROVIDER_ERROR".equals(e.getCode()) || "PROVIDER_CIRCUIT_OPEN".equals(e.getCode())
                || "PROVIDER_RATE_LIMITED".equals(e.getCode());
    }

    private <T> T attemptFallback(ChatRequest request, LlmProvider failedProvider,
                                  GatewayException originalException, ProviderCall<T> call) {
        List<LlmProvider> fallbacks = fallbackResolver.resolve(request, failedProvider, providers);

        // Apply capability filtering to fallback candidates
        List<LlmProvider> capableFallbacks = capabilityFilter(fallbacks, request);

        if (capableFallbacks.isEmpty() && !fallbacks.isEmpty() && hasResponseFormatRequirement(request)) {
            // Fallbacks exist but none support the required capability
            String formatType = responseFormatTypeName(request.getResponseFormat());
            throw new GatewayException("FAILOVER_CAPABILITY_MISMATCH",
                    "Failover blocked: no fallback provider supports response_format: " + formatType
                    + ". Primary provider [" + failedProvider.name() + "] failed: " + originalException.getMessage());
        }
        if (capableFallbacks.isEmpty() && !fallbacks.isEmpty() && hasToolsRequirement(request)) {
            // fallbacks exist but none support function calling.
            throw new GatewayException("FAILOVER_CAPABILITY_MISMATCH",
                    "Failover blocked: no fallback provider supports " + toolsRequirementName(request) + "."
                    + " Primary provider [" + failedProvider.name() + "] failed: " + originalException.getMessage());
        }

        for (LlmProvider fallback : capableFallbacks) {
            if (!healthRegistry.isAvailable(fallback.name())) {
                log.debug("Skipping unhealthy fallback provider [{}]", fallback.name());
                continue;
            }
            try {
                log.info("Falling back from [{}] to [{}] for model [{}]",
                        failedProvider.name(), fallback.name(), request.getModel());
                metrics.recordFallback(failedProvider.name(), fallback.name());
                setProviderAttribute(fallback.name());
                // The provider identity moves with the fallback; so must the credential it went out
                // under. The interceptor stamps a fingerprint during the call and nothing else clears
                // it, so a fallback that does not go through the interceptor (Gemini, Bedrock, Ollama)
                // would inherit the failed primary's — a usage row naming one provider and another's
                // credential. A fallback that does go through it re-stamps its own.
                clearCredentialFingerprint();
                return call.execute(fallback);
            } catch (GatewayException e) {
                log.warn("Fallback provider [{}] also failed: {}", fallback.name(), e.getMessage());
                metrics.recordProviderError(fallback.name(), e.getCode());
            }
        }

        throw originalException;
    }

    // -------------------------------------------------------------------------
    // Capability filtering
    // -------------------------------------------------------------------------

    List<LlmProvider> capabilityFilter(List<LlmProvider> candidates, ChatRequest request) {
        List<LlmProvider> result = candidates;

        ResponseFormat format = request.getResponseFormat();
        if (format instanceof ResponseFormat.JsonSchema) {
            result = result.stream()
                    .filter(p -> p.capabilities().supportsStructuredOutputs())
                    .toList();
        } else if (format instanceof ResponseFormat.JsonObject) {
            result = result.stream()
                    .filter(p -> p.capabilities().supportsJsonMode())
                    .toList();
        }

        // a request carrying function-calling tools needs a tool-capable
        // provider. Filtering here (instead of silent-dropping at buildBody)
        // guarantees tools are either translated by a capable provider or the
        // request is explicitly rejected below — never quietly ignored.
        if (hasToolsRequirement(request)) {
            result = result.stream()
                    .filter(p -> p.capabilities().supportsToolCalls())
                    .toList();
            // on a stream the call has to come back through the provider's stream decoder,
            // and only a provider that declares it puts every fragment on the chunk. The same filter
            // serves the primary and every fallback, so a failover cannot land on a provider whose
            // stream would drop the call the primary would have relayed.
            if (request.isStream()) {
                result = result.stream()
                        .filter(p -> p.capabilities().supportsStreamingToolCalls())
                        .toList();
            }
        }

        return result;
    }

    /** What the refusal names: function calling, or function calling on a stream. */
    private static String toolsRequirementName(ChatRequest request) {
        return request.isStream() ? "tool calls on a stream (streamed function calling)"
                : "tool calls (function calling)";
    }

    private static boolean hasResponseFormatRequirement(ChatRequest request) {
        ResponseFormat format = request.getResponseFormat();
        return format != null && !(format instanceof ResponseFormat.Text);
    }

    /**
     * Whether this request needs a provider that can carry tools — either because it offers
     * definitions, or because its history replays a call only a tool-capable provider can represent.
     * A client is not obliged to resend {@code tools} on a later turn, and a provider that cannot
     * carry the call would drop it and answer plain chat with a 200.
     */
    private static boolean hasToolsRequirement(ChatRequest request) {
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            return true;
        }
        return request.getMessages() != null && request.getMessages().stream()
                .anyMatch(m -> m != null && m.getToolCalls() != null && !m.getToolCalls().isEmpty());
    }

    static String responseFormatTypeName(ResponseFormat format) {
        if (format instanceof ResponseFormat.JsonSchema) return "json_schema";
        if (format instanceof ResponseFormat.JsonObject) return "json_object";
        if (format instanceof ResponseFormat.Text) return "text";
        return "unknown";
    }

    private void setProviderAttribute(String providerName) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.getRequest().setAttribute("gateway.provider", providerName);
        }
    }

    private void clearCredentialFingerprint() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.getRequest().removeAttribute(CredentialInterceptor.FINGERPRINT_ATTRIBUTE);
        }
    }

    private void addSessionId(Observation obs) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            String sessionId = (String) attrs.getRequest().getAttribute(TraceIdFilter.SESSION_ATTR);
            if (sessionId != null) {
                obs.highCardinalityKeyValue("session_id", sessionId);
            }
        }
    }

    /** Maps a DVARA provider name to its OTel GenAI {@code gen_ai.system} value (lower-cased fallback). */
    private static String genAiSystem(String providerName) {
        String key = providerName == null ? "" : providerName.toLowerCase(Locale.ROOT);
        return GEN_AI_SYSTEM.getOrDefault(key, key);
    }

    /**
     * Adds OTel GenAI semantic-convention <em>request</em> attributes to the span,
     * alongside (never replacing) the existing provider/model attributes. Gated by
     * {@code management.tracing.genai-semconv.enabled} (default true).
     *
     * <p>{@code gen_ai.system}, {@code gen_ai.request.model} and
     * {@code gen_ai.operation.name} are low-cardinality (bounded sets). The
     * request-specific {@code max_tokens}/{@code temperature} are emitted as
     * high-cardinality so they land on the span without inflating metric tag
     * cardinality (they feed traces, not meters).
     */
    private void addGenAiRequestAttrs(Observation obs, String providerName, String model,
                                      String operation, Integer maxTokens, Double temperature) {
        if (!genaiSemconvEnabled) return;
        obs.lowCardinalityKeyValue("gen_ai.system", genAiSystem(providerName));
        if (model != null) obs.lowCardinalityKeyValue("gen_ai.request.model", model);
        obs.lowCardinalityKeyValue("gen_ai.operation.name", operation);
        if (maxTokens != null) obs.highCardinalityKeyValue("gen_ai.request.max_tokens", String.valueOf(maxTokens));
        if (temperature != null) obs.highCardinalityKeyValue("gen_ai.request.temperature", String.valueOf(temperature));
    }

    /**
     * Adds OTel GenAI semantic-convention <em>response</em> attributes (all
     * high-cardinality: usage tokens, response id/model, finish reasons). Gated by
     * the same flag. No-op on a null response.
     */
    private void addGenAiResponseAttrs(Observation obs, ChatResponse response) {
        if (!genaiSemconvEnabled || response == null) return;
        if (response.getUsage() != null) {
            obs.highCardinalityKeyValue("gen_ai.usage.input_tokens",
                    String.valueOf(response.getUsage().getPromptTokens()));
            obs.highCardinalityKeyValue("gen_ai.usage.output_tokens",
                    String.valueOf(response.getUsage().getCompletionTokens()));
        }
        if (response.getModel() != null) {
            obs.highCardinalityKeyValue("gen_ai.response.model", response.getModel());
        }
        if (response.getId() != null) {
            obs.highCardinalityKeyValue("gen_ai.response.id", response.getId());
        }
        if (response.getChoices() != null && !response.getChoices().isEmpty()) {
            String finishReasons = response.getChoices().stream()
                    .map(ChatResponse.Choice::getFinishReason)
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.joining(","));
            if (!finishReasons.isEmpty()) {
                obs.highCardinalityKeyValue("gen_ai.response.finish_reasons", finishReasons);
            }
        }
    }

    private void recordCanaryMetrics(String providerName, ChatRequest request,
                                      ChatResponse response, long latencyMs, boolean error,
                                      RequestContext routingCtx) {
        Optional.ofNullable(routingCtx.getMatchedRoute()).ifPresent(routeConfig -> {
            CanaryConfig canary = routeConfig.getCanaryConfig();
            if (canary == null) return;

            String variant;
            if (providerName.equals(canary.getBaselineProvider())) {
                variant = "baseline";
            } else if (providerName.equals(canary.getCandidateProvider())) {
                variant = "candidate";
            } else {
                return;
            }

            double cost = 0.0;
            if (!error && response != null && costEstimator != null) {
                cost = costEstimator.calculateActualCost(request, response);
            }

            canaryMetricsCollector.record(routeConfig.getId(), variant, latencyMs, cost, error);
            metrics.recordCanaryRequest(routeConfig.getId(), variant, request.getModel());

            setCanaryVariantAttribute(variant);
        });
    }

    private void setCanaryVariantAttribute(String variant) {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            attrs.getRequest().setAttribute("gateway.canary.variant", variant);
        }
    }

    private void dispatchShadowIfConfigured(ChatRequest request, ChatResponse response, long startNanos,
                                              RequestContext routingCtx) {
        long primaryLatencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        Optional.ofNullable(routingCtx.getMatchedRoute()).ifPresent(routeConfig -> {
            if (routeConfig.getShadowConfig() != null && shadowDispatcher != null) {
                try {
                    shadowDispatcher.dispatchShadow(request, response, primaryLatencyMs, routeConfig);
                } catch (Exception e) {
                    log.debug("Shadow dispatch failed: {}", e.getMessage());
                }
            }
        });
    }

    private void recordIntelligentRoutingMetric(RequestContext ctx) {
        if (ctx.getResolvedComplexity() != null && ctx.getResolvedModel() != null) {
            metrics.recordIntelligentRouting(ctx.getResolvedComplexity(), ctx.getResolvedModel());
        }
    }

    @FunctionalInterface
    private interface ProviderCall<T> {
        T execute(LlmProvider provider);
    }
}