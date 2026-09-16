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
package com.dvarahq.server.filter;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.filter.ChatFilter;
import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.filter.FilterOrder;
import com.dvarahq.core.guardrail.ContextWindowGovernor;
import com.dvarahq.core.guardrail.ContextWindowResult;
import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.provider.LlmProvider;
import com.dvarahq.server.metrics.GatewayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Applies the context-window governor to a request, against the window declared by the provider
 * that will serve it rather than a fixed number.
 */
@Component
public class ContextWindowFilter implements ChatFilter {

    private static final Logger log = LoggerFactory.getLogger(ContextWindowFilter.class);

    /**
     * Used only when no registered provider claims the request's model. That request will get
     * {@code NO_PROVIDER} from the dispatcher anyway; this just keeps an unknown model from being
     * treated as unlimited.
     */
    static final int FALLBACK_MAX_CONTEXT_TOKENS = 128_000;

    private final ContextWindowGovernor governor;
    private final GatewayMetrics metrics;
    private final List<LlmProvider> providers;

    public ContextWindowFilter(ContextWindowGovernor governor, GatewayMetrics metrics,
                               ObjectProvider<LlmProvider> providers) {
        this.governor = governor;
        this.metrics = metrics;
        // A snapshot, as the dispatcher takes: providers are registered at startup. ObjectProvider
        // rather than a List parameter so a context with no provider configured still starts.
        this.providers = providers.orderedStream().toList();
    }

    @Override public int order() { return FilterOrder.CONTEXT_WINDOW; }

    @Override
    public ChatRequest preDispatch(ChatRequest request, FilterContext ctx) {
        int maxTokens = resolveMaxContextTokens(request);
        ContextWindowResult result = governor.evaluate(request, maxTokens, ctx.getWorkspaceId());

        if (result.warningThresholdBreached() && !result.hardThresholdBreached()) {
            metrics.recordContextWindowWarning(ctx.getWorkspaceId(), request.getModel());
            ctx.setAttribute("context.warning", true);
            ctx.setAttribute("context.utilization", result.utilizationPct());
        }

        if (result.hardThresholdBreached()) {
            if (result.prunedRequest() != null) {
                metrics.recordContextWindowPruned(ctx.getWorkspaceId(), request.getModel(), "pruned");
                return result.prunedRequest();
            }
            throw new GatewayException("CONTEXT_WINDOW_EXCEEDED",
                    "Request exceeds context window: " + result.estimatedTokens()
                            + " tokens estimated, max " + result.maxTokens());
        }
        return request;
    }

    /**
     * The window of the provider that will serve this request, resolved through
     * {@link LlmProvider#supports(ChatRequest)}, the same predicate the dispatcher routes on. The
     * model-downgrade and route-resolution filters have already run, so the model here is the one
     * that will be sent.
     *
     * <p>Takes the smallest window among the candidates, since a request that fails over may land on
     * any of them. A provider declaring a non-positive window states no limit and is skipped.
     */
    private int resolveMaxContextTokens(ChatRequest request) {
        int smallest = Integer.MAX_VALUE;
        for (LlmProvider provider : providers) {
            if (!supportsQuietly(provider, request)) {
                continue;
            }
            int declared = provider.capabilities().maxContextTokens();
            if (declared > 0 && declared < smallest) {
                smallest = declared;
            }
        }
        return smallest == Integer.MAX_VALUE ? FALLBACK_MAX_CONTEXT_TOKENS : smallest;
    }

    /**
     * {@code supports} is provider code. A provider that throws is not a candidate, which is what
     * the dispatcher will conclude too, so it must not turn into a failed request here.
     */
    private boolean supportsQuietly(LlmProvider provider, ChatRequest request) {
        try {
            return provider.supports(request);
        } catch (RuntimeException e) {
            log.debug("Provider {} threw while matching model {}: {}",
                    provider.name(), request.getModel(), e.getMessage());
            return false;
        }
    }
}