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

import com.dvarahq.core.cost.CostEstimator;
import com.dvarahq.core.cost.TokenPrices;
import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.guardrail.ContextThresholds;
import com.dvarahq.core.guardrail.ContextWindowGovernor;
import com.dvarahq.core.guardrail.TokenBreakdown;
import com.dvarahq.core.guardrail.TokenEstimator;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.util.JsonMapper;
import com.dvarahq.server.filter.ContextWindowFilter;
import com.dvarahq.server.service.ChatExecutionService;
import com.dvarahq.server.v1.dto.ChatCompletionRequest;
import com.dvarahq.server.web.AccessLogFilter;
import com.dvarahq.server.web.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code POST /v1/tokens/count}: how many input tokens a chat request is, before it is sent, for one model or several.
 *
 * <p>The body is a {@code /v1/chat/completions} body, so a client counts exactly what it is about to send, with an
 * optional {@code models} list to compare the same request across models. For each model the answer gives the input
 * tokens, who counted them (the provider routing picks, where it can count, else the gateway), the vocabulary and
 * whether it is the model's own, where the tokens go part by part, the context window the gateway holds the request
 * to and how much of it the request and its {@code max_tokens} would use, and the cost at the gateway's prices, or
 * {@code pricing_known: false} where it has none, never a cost of zero.
 *
 * <p>No model is called and nothing is billed, metered or counted as a call. The request is routed as a call would be,
 * and the checks that govern what it says run, since its text may go to a provider to be counted, exactly as for
 * {@code /v1/messages/count_tokens}. The key needs the scope a chat call needs.
 */
@RestController
@RequestMapping("/v1")
@Tag(name = "Tokens", description = "Pre-flight token count")
public class TokenCountController {

    /** How many models one call may compare. Each is routed and counted, so the list is bounded. */
    static final int MAX_MODELS = 10;

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final ChatExecutionService executionService;
    private final TokenEstimator tokenEstimator;
    private final ObjectProvider<CostEstimator> costEstimator;
    private final ObjectProvider<ContextWindowFilter> contextWindow;
    private final ObjectProvider<ContextWindowGovernor> governor;

    public TokenCountController(ChatExecutionService executionService,
                                TokenEstimator tokenEstimator,
                                ObjectProvider<CostEstimator> costEstimator,
                                ObjectProvider<ContextWindowFilter> contextWindow,
                                ObjectProvider<ContextWindowGovernor> governor) {
        this.executionService = executionService;
        this.tokenEstimator = tokenEstimator;
        this.costEstimator = costEstimator;
        this.contextWindow = contextWindow;
        this.governor = governor;
    }

    @PostMapping("/tokens/count")
    @Operation(summary = "Count a chat request's input tokens",
            description = "Takes a /v1/chat/completions body, with an optional 'models' list to compare. For each "
                    + "model: input tokens, who counted them, where they go, context-window use and the priced "
                    + "cost. No model is called and nothing is billed.")
    @ApiResponse(responseCode = "200", description = "One count per model")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    public Map<String, Object> count(@RequestBody(required = false) byte[] rawBody, HttpServletRequest httpRequest) {
        ObjectNode body = parse(rawBody);
        List<String> models = models(body);
        ChatCompletionRequest request;
        try {
            request = JsonMapper.instance().treeToValue(body, ChatCompletionRequest.class);
        } catch (Exception e) {
            throw new GatewayException("INVALID_REQUEST", "The request body is not a chat request: "
                    + e.getMessage().lines().findFirst().orElse(""));
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            throw new GatewayException("INVALID_REQUEST", "messages must not be empty");
        }
        String traceId = (String) httpRequest.getAttribute(TraceIdFilter.ATTR);
        httpRequest.setAttribute(AccessLogFilter.ATTR_MODEL, models.getFirst());
        String workspaceId = (String) httpRequest.getAttribute("workspaceId");

        List<Map<String, Object>> counts = new ArrayList<>();
        GatewayException firstError = null;
        for (String model : models) {
            request.setModel(model);
            ChatRequest internal = ChatCompletionController.toInternal(request);
            try {
                ChatExecutionService.InputCount counted = executionService.countInput(internal, httpRequest, traceId);
                counts.add(entry(model, counted, internal.getMaxTokens(), workspaceId));
            } catch (GatewayException e) {
                // Each model answers for itself, so one the gateway cannot route does not hide the others' counts.
                if (firstError == null) {
                    firstError = e;
                }
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("model", model);
                failed.put("error", Map.of("code", e.getCode().toLowerCase(java.util.Locale.ROOT),
                        "message", e.getMessage()));
                counts.add(failed);
            }
        }
        // When every model is refused (always so for one model), the refusal is the answer, with the status a call
        // would get.
        if (firstError != null && counts.stream().allMatch(c -> c.containsKey("error"))) {
            throw firstError;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "token_count");
        out.put("counts", counts);
        return out;
    }

    private Map<String, Object> entry(String model, ChatExecutionService.InputCount counted, Integer maxTokens,
                                      String workspaceId) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("model", model);
        e.put("served_model", counted.model());
        e.put("provider", counted.provider());
        e.put("input_tokens", counted.tokens());
        e.put("counted_by", counted.byProvider() ? "provider" : "gateway");

        Optional<TokenBreakdown> parts = tokenEstimator.breakdown(counted.governed());
        if (parts.isPresent()) {
            TokenBreakdown b = parts.get();
            e.put("tokenizer", Map.of("encoding", b.encoding(), "exact", b.exact()));
            Map<String, Object> breakdown = new LinkedHashMap<>();
            breakdown.put("system", b.system());
            breakdown.put("user", b.user());
            breakdown.put("assistant", b.assistant());
            breakdown.put("tools", b.tools());
            breakdown.put("tool_results", b.toolResults());
            breakdown.put("images", b.images());
            breakdown.put("overhead", b.overhead());
            breakdown.put("per_message", b.perMessage());
            // The parts are the gateway's count. Where the provider counted, its total can differ from their sum.
            breakdown.put("total", b.total());
            e.put("breakdown", breakdown);
        }

        e.put("max_tokens", maxTokens);
        ContextWindowFilter window = contextWindow.getIfAvailable();
        if (window != null) {
            int tokens = window.windowFor(counted.governed());
            ContextWindowGovernor g = governor.getIfAvailable();
            ContextThresholds thresholds = g != null ? g.thresholds(workspaceId) : ContextThresholds.DEFAULTS;
            double used = tokens > 0 ? percent(counted.tokens(), tokens) : 0.0;
            long withOutput = (long) counted.tokens() + (maxTokens != null && maxTokens > 0 ? maxTokens : 0);
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("window_tokens", tokens);
            context.put("utilization_pct", used);
            context.put("warning_threshold_pct", thresholds.warningPct());
            context.put("hard_threshold_pct", thresholds.hardPct());
            context.put("over_warning_threshold", used >= thresholds.warningPct());
            context.put("over_hard_threshold", used >= thresholds.hardPct());
            context.put("overflows", withOutput > tokens);
            e.put("context", context);
        }

        CostEstimator costs = costEstimator.getIfAvailable();
        Optional<TokenPrices> prices = costs == null ? Optional.empty() : safePrices(costs, counted);
        e.put("pricing_known", prices.isPresent());
        if (prices.isPresent()) {
            BigDecimal input = cost(counted.tokens(), prices.get().inputPerMillion());
            e.put("input_cost_usd", input);
            if (maxTokens != null && maxTokens > 0) {
                BigDecimal output = cost(maxTokens, prices.get().outputPerMillion());
                e.put("max_output_cost_usd", output);
                e.put("max_total_cost_usd", input.add(output));
            }
        }
        return e;
    }

    /** A price source that fails reports no price: the count is still the answer. */
    private static Optional<TokenPrices> safePrices(CostEstimator costs, ChatExecutionService.InputCount counted) {
        try {
            Optional<TokenPrices> prices = costs.prices(counted.model(), counted.provider());
            return prices == null ? Optional.empty() : prices.filter(p -> p.inputPerMillion() != null
                    && p.outputPerMillion() != null);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static BigDecimal cost(long tokens, BigDecimal perMillion) {
        return BigDecimal.valueOf(tokens).multiply(perMillion)
                .divide(ONE_MILLION, MathContext.DECIMAL64)
                .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private static double percent(long part, long whole) {
        return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private static ObjectNode parse(byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            throw new GatewayException("INVALID_REQUEST", "The request body is empty");
        }
        JsonNode node;
        try {
            node = JsonMapper.instance().readTree(rawBody);
        } catch (Exception e) {
            throw new GatewayException("INVALID_REQUEST", "The request body is not valid JSON");
        }
        if (!(node instanceof ObjectNode object)) {
            throw new GatewayException("INVALID_REQUEST", "The request body must be a JSON object");
        }
        return object;
    }

    /**
     * The models to count for: {@code models} when given, else {@code model}. Removed from the body, which is then
     * an ordinary chat body. Repeats are counted once, in the order given.
     */
    private static List<String> models(ObjectNode body) {
        Set<String> models = new LinkedHashSet<>();
        JsonNode list = body.get("models");
        if (list != null && !list.isNull()) {
            if (!list.isArray()) {
                throw new GatewayException("INVALID_REQUEST", "models must be an array of model names");
            }
            for (JsonNode m : list) {
                if (!m.isTextual() || m.asText().isBlank()) {
                    throw new GatewayException("INVALID_REQUEST", "models must be an array of model names");
                }
                models.add(m.asText().trim());
            }
            body.remove("models");
            if (models.isEmpty()) {
                throw new GatewayException("INVALID_REQUEST", "models must name at least one model");
            }
        } else {
            JsonNode model = body.get("model");
            if (model == null || !model.isTextual() || model.asText().isBlank()) {
                throw new GatewayException("INVALID_REQUEST", "model is required, or models to compare several");
            }
            models.add(model.asText().trim());
        }
        if (models.size() > MAX_MODELS) {
            throw new GatewayException("INVALID_REQUEST",
                    "models names " + models.size() + " models; at most " + MAX_MODELS + " can be compared at once");
        }
        return new ArrayList<>(models);
    }
}
