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
package com.dvarahq.server.metrics;

import com.dvarahq.core.region.RegionContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Central gateway metrics registry. All Prometheus-exposed metrics are created
 * and recorded through this component.
 *
 * <p>Metric naming follows Prometheus conventions (snake_case, base unit suffix).</p>
 *
 * <h3>Counters</h3>
 * <ul>
 *   <li>{@code gateway_requests_total} — total requests (workspace, model, provider, status)</li>
 *   <li>{@code gateway_tokens_total} — token usage (workspace, model, direction=input|output)</li>
 *   <li>{@code gateway_provider_errors_total} — provider errors (provider, error_code)</li>
 *   <li>{@code gateway_retries_total} — retry attempts (provider)</li>
 *   <li>{@code gateway_fallbacks_total} — fallback activations (from_provider, to_provider)</li>
 *   <li>{@code gateway_grounding_check_total} — grounding checks run (grounded, action, mode)</li>
 *   <li>{@code gateway_grounding_unchecked_total} — responses grounding was on for but could not check
 *       (workspace, reason=no_sources|sources_over_limits, mode)</li>
 * </ul>
 *
 * <h3>Histograms</h3>
 * <ul>
 *   <li>{@code gateway_latency_seconds} — request latency (workspace, model, provider, status)</li>
 * </ul>
 */
@Component
public class GatewayMetrics {

    private final MeterRegistry registry;
    private final String region;

    public GatewayMetrics(MeterRegistry registry, RegionContext regionContext) {
        this.registry = registry;
        this.region = regionContext.currentRegion().orElse("unknown");
    }

    /**
     * The meters every request records, found once for each set of tags rather than built and
     * looked up in the registry on every call. A key is one set of tag values, so these maps hold
     * exactly the series the registry already holds.
     */
    private record RequestTags(String workspace, String model, String provider, String status) {}

    private record TokenTags(String workspace, String model, String direction) {}

    private record CostTags(String workspace, String model, String provider) {}

    private final java.util.concurrent.ConcurrentHashMap<RequestTags, Counter> requestCounters =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<RequestTags, Timer> latencyTimers =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<TokenTags, Counter> tokenCounters =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<CostTags, Counter> costCounters =
            new java.util.concurrent.ConcurrentHashMap<>();

    public void recordRequest(String workspace, String model, String provider, String status,
                              Duration latency) {
        RequestTags tags = new RequestTags(safe(workspace), safe(model), safe(provider), safe(status));
        requestCounters.computeIfAbsent(tags, t -> Counter.builder("gateway_requests_total")
                .description("Total gateway requests")
                .tag("workspace", t.workspace())
                .tag("model", t.model())
                .tag("provider", t.provider())
                .tag("status", t.status())
                .tag("region", region)
                .register(registry))
                .increment();

        latencyTimers.computeIfAbsent(tags, t -> Timer.builder("gateway_latency_seconds")
                .description("Request latency")
                .tag("workspace", t.workspace())
                .tag("model", t.model())
                .tag("provider", t.provider())
                .tag("status", t.status())
                .tag("region", region)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry))
                .record(latency);
    }

    public void recordTokens(String workspace, String model, int inputTokens, int outputTokens) {
        if (inputTokens > 0) {
            tokenCounter(workspace, model, "input").increment(inputTokens);
        }
        if (outputTokens > 0) {
            tokenCounter(workspace, model, "output").increment(outputTokens);
        }
    }

    private Counter tokenCounter(String workspace, String model, String direction) {
        return tokenCounters.computeIfAbsent(new TokenTags(safe(workspace), safe(model), direction),
                t -> Counter.builder("gateway_tokens_total")
                        .description("Total tokens processed")
                        .tag("workspace", t.workspace())
                        .tag("model", t.model())
                        .tag("direction", t.direction())
                        .register(registry));
    }

    public void recordProviderError(String provider, String errorCode) {
        Counter.builder("gateway_provider_errors_total")
                .description("Total provider errors")
                .tag("provider", safe(provider))
                .tag("error_code", safe(errorCode))
                .register(registry)
                .increment();
    }


    /** At most this many values of a caller-named tag get a series of their own; the rest are {@code other}. */
    private static final int CALLER_TAG_LIMIT = 50;
    private final java.util.Set<String> opaqueTypes = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<String> droppedFields = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Anthropic Messages API content the gateway passed on without reading: block types (and message roles,
     * as {@code role:<name>}) it does not know. A new type showing up here is one governance cannot see into.
     *
     * @param direction {@code request} or {@code response}
     */
    public void recordAnthropicOpaqueBlocks(String direction, String type, int count) {
        Counter.builder("gateway_anthropic_opaque_blocks_total")
                .description("Anthropic Messages API content blocks passed on without being read by governance")
                .tag("direction", safe(direction))
                .tag("type", bounded(type, opaqueTypes))
                .register(registry)
                .increment(count);
    }

    /**
     * Something a Messages API caller sent for Anthropic that a provider which is not Anthropic was not given: a
     * top-level field, {@code block:<type>}, {@code role:<name>}, {@code cache_control}.
     */
    public void recordAnthropicFieldDropped(String field) {
        Counter.builder("gateway_anthropic_fields_dropped_total")
                .description("Anthropic-only request content left out for a provider that is not Anthropic")
                .tag("field", bounded(field, droppedFields))
                .register(registry)
                .increment();
    }

    /** A caller's string as a tag: cut to a safe form, with the number of series bounded. */
    private static String bounded(String value, java.util.Set<String> seen) {
        String tag = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_:.]", "_");
        if (tag.length() > 48) {
            tag = tag.substring(0, 48);
        }
        if (!seen.contains(tag)) {
            if (seen.size() < CALLER_TAG_LIMIT) {
                seen.add(tag);
            } else {
                tag = "other";
            }
        }
        return tag;
    }

    public void recordFallback(String fromProvider, String toProvider) {
        Counter.builder("gateway_fallbacks_total")
                .description("Total fallback activations")
                .tag("from_provider", safe(fromProvider))
                .tag("to_provider", safe(toProvider))
                .register(registry)
                .increment();
    }

    public void recordCost(String workspace, String model, String provider, double costUsd) {
        costCounters.computeIfAbsent(new CostTags(safe(workspace), safe(model), safe(provider)),
                t -> Counter.builder("gateway_cost_dollars_total")
                        .description("Total cost in USD")
                        .tag("workspace", t.workspace())
                        .tag("model", t.model())
                        .tag("provider", t.provider())
                        .register(registry))
                .increment(costUsd);
    }




    /**
     * A WARN_AGENT warning from a rule that tests budget use. A warning from any other rule is counted only by
     * {@link #recordPolicyWarning}. Budget enforcement is not part of this module; a module that provides it
     * records its own counters.
     */
    public void recordBudgetWarning(String workspace, String budgetId) {
        Counter.builder("gateway_budget_warning_total")
                .description("Total budget warning events (WARN_AGENT policy rules on budget use)")
                .tag("workspace", safe(workspace))
                .tag("budget_id", safe(budgetId))
                .register(registry)
                .increment();
    }

    /** Every WARN_AGENT policy warning, by the policy that raised it and its type (budget or other). */
    public void recordPolicyWarning(String workspace, String policyId, String type) {
        Counter.builder("gateway_policy_warning_total")
                .description("Total policy warning events (WARN_AGENT)")
                .tag("workspace", safe(workspace))
                .tag("policy_id", safe(policyId))
                .tag("type", safe(type))
                .register(registry)
                .increment();
    }

    public void recordCanaryRequest(String routeId, String variant, String model) {
        Counter.builder("gateway_canary_requests_total")
                .description("Total canary A/B test requests")
                .tag("route_id", safe(routeId))
                .tag("variant", safe(variant))
                .tag("model", safe(model))
                .register(registry)
                .increment();
    }

    public void recordGuardrailBlocked(String workspace, String category) {
        Counter.builder("gateway_guardrail_blocked_total")
                .description("Total requests blocked by guardrail")
                .tag("workspace", safe(workspace))
                .tag("category", safe(category))
                .register(registry)
                .increment();
    }

    public void recordGuardrailFlagged(String workspace, String category) {
        Counter.builder("gateway_guardrail_flagged_total")
                .description("Total requests flagged by guardrail")
                .tag("workspace", safe(workspace))
                .tag("category", safe(category))
                .register(registry)
                .increment();
    }

    public void recordSchemaValidation(String workspace, String model, String result) {
        Counter.builder("gateway_schema_validations_total")
                .description("Total output schema validations")
                .tag("workspace", safe(workspace))
                .tag("model", safe(model))
                .tag("result", safe(result))
                .register(registry)
                .increment();
    }


    public void recordContextWindowWarning(String workspace, String model) {
        Counter.builder("gateway_context_window_warnings_total")
                .description("Total context window warnings")
                .tag("workspace", safe(workspace))
                .tag("model", safe(model))
                .register(registry)
                .increment();
    }

    public void recordContextWindowPruned(String workspace, String model, String strategy) {
        Counter.builder("gateway_context_window_pruned_total")
                .description("Total context window prunings")
                .tag("workspace", safe(workspace))
                .tag("model", safe(model))
                .tag("strategy", safe(strategy))
                .register(registry)
                .increment();
    }











    /**
     * Every classification a hook performed, flagged or not: the denominator for a flag rate, which
     * {@code gateway_ml_guardrail_total} (detections only) cannot supply.
     */
    public void recordMlClassifierScan(String provider, boolean flagged) {
        Counter.builder("gateway_ml_classifier_scans_total")
                .description("Total ML classifier invocations, by whether they produced a detection")
                .tag("provider", safe(provider))
                .tag("flagged", String.valueOf(flagged))
                .register(registry)
                .increment();
    }

    public void recordMlGuardrail(String provider, String category, String action) {
        Counter.builder("gateway_ml_guardrail_total")
                .description("Total ML guardrail detections")
                .tag("provider", safe(provider))
                .tag("category", safe(category))
                .tag("action", safe(action))
                .register(registry)
                .increment();
    }

    public void recordIntelligentRouting(String complexity, String selectedModel) {
        Counter.builder("gateway_intelligent_routing_total")
                .description("Total intelligent routing decisions")
                .tag("complexity", safe(complexity))
                .tag("selected_model", safe(selectedModel))
                .register(registry)
                .increment();
    }

    private static final org.slf4j.Logger groundingLog = org.slf4j.LoggerFactory.getLogger(GatewayMetrics.class);

    /** Workspaces already warned about unchecked grounding, so the warning is written once each. */
    private final java.util.Set<String> warnedUncheckedGrounding = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A response that grounding is switched on for, but that was not checked: the request carried no source documents
     * ({@code reason=no_sources}), or every source it carried was dropped by {@code grounding.max-sources} or
     * {@code grounding.max-source-length} ({@code reason=sources_over_limits}). Grounding can only judge a response
     * against sources, so such a response passes unjudged. Counted in {@code gateway_grounding_unchecked_total}, beside
     * {@code gateway_grounding_check_total}, so the share of traffic grounding actually checks can be measured. The
     * first one for each workspace is also logged at WARN.
     *
     * @param mode {@code non_streaming} or {@code streaming}
     */
    public void recordGroundingUnchecked(String workspace, String reason, String mode) {
        Counter.builder("gateway_grounding_unchecked_total")
                .description("Responses grounding was switched on for but could not check, because no usable source "
                        + "document came with the request")
                .tag("workspace", safe(workspace))
                .tag("reason", safe(reason))
                .tag("mode", safe(mode))
                .register(registry)
                .increment();
        if (warnedUncheckedGrounding.add(safe(workspace))) {
            groundingLog.warn("Grounding is on for workspace {}, but a response was not checked ({}): grounding judges a "
                    + "response only against the source documents a request sends in metadata[\"grounding.sources\"], "
                    + "and this one had none it could use. Such responses pass unchecked; "
                    + "gateway_grounding_unchecked_total counts them. Logged once per workspace.", safe(workspace), reason);
        }
    }

    /** A non-streamed grounding check. */
    public void recordGroundingCheck(String grounded, String action) {
        recordGroundingCheck(grounded, action, "non_streaming");
    }

    /**
     * A grounding check that ran, with its verdict and action.
     *
     * @param mode {@code non_streaming} or {@code streaming}; every series carries it, because one metric name must
     *             keep one set of tag keys
     */
    public void recordGroundingCheck(String grounded, String action, String mode) {
        Counter.builder("gateway_grounding_check_total")
                .description("Total grounding/hallucination checks")
                .tag("grounded", safe(grounded))
                .tag("action", safe(action))
                .tag("mode", safe(mode))
                .register(registry)
                .increment();
    }

    public void recordPluginGuardrail(String plugin, String category, String action) {
        Counter.builder("gateway_plugin_guardrail_total")
                .description("Total plugin guardrail detections")
                .tag("plugin", safe(plugin))
                .tag("category", safe(category))
                .tag("action", safe(action))
                .register(registry)
                .increment();
    }

    private static String safe(String value) {
        return value != null ? value : "unknown";
    }
}