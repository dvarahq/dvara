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

    public void recordRequest(String workspace, String model, String provider, String status,
                              Duration latency) {
        Counter.builder("gateway_requests_total")
                .description("Total gateway requests")
                .tag("workspace", safe(workspace))
                .tag("model", safe(model))
                .tag("provider", safe(provider))
                .tag("status", safe(status))
                .tag("region", region)
                .register(registry)
                .increment();

        Timer.builder("gateway_latency_seconds")
                .description("Request latency")
                .tag("workspace", safe(workspace))
                .tag("model", safe(model))
                .tag("provider", safe(provider))
                .tag("status", safe(status))
                .tag("region", region)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(latency);
    }

    public void recordTokens(String workspace, String model, int inputTokens, int outputTokens) {
        if (inputTokens > 0) {
            Counter.builder("gateway_tokens_total")
                    .description("Total tokens processed")
                    .tag("workspace", safe(workspace))
                    .tag("model", safe(model))
                    .tag("direction", "input")
                    .register(registry)
                    .increment(inputTokens);
        }
        if (outputTokens > 0) {
            Counter.builder("gateway_tokens_total")
                    .description("Total tokens processed")
                    .tag("workspace", safe(workspace))
                    .tag("model", safe(model))
                    .tag("direction", "output")
                    .register(registry)
                    .increment(outputTokens);
        }
    }

    public void recordProviderError(String provider, String errorCode) {
        Counter.builder("gateway_provider_errors_total")
                .description("Total provider errors")
                .tag("provider", safe(provider))
                .tag("error_code", safe(errorCode))
                .register(registry)
                .increment();
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
        Counter.builder("gateway_cost_dollars_total")
                .description("Total cost in USD")
                .tag("workspace", safe(workspace))
                .tag("model", safe(model))
                .tag("provider", safe(provider))
                .register(registry)
                .increment(costUsd);
    }




    /**
     * A WARN_AGENT policy warning, despite the name. Budget enforcement is not part of this module;
     * a module that provides it records its own counters.
     */
    public void recordBudgetWarning(String workspace, String budgetId) {
        Counter.builder("gateway_budget_warning_total")
                .description("Total budget warning events (WARN_AGENT policy)")
                .tag("workspace", safe(workspace))
                .tag("budget_id", safe(budgetId))
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

    public void recordGroundingCheck(String grounded, String action) {
        Counter.builder("gateway_grounding_check_total")
                .description("Total grounding/hallucination checks")
                .tag("grounded", safe(grounded))
                .tag("action", safe(action))
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