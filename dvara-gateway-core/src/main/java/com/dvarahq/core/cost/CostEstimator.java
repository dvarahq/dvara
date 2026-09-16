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
package com.dvarahq.core.cost;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

/**
 * Prices a call: what one is about to cost, and what one did cost.
 *
 * <p>This build includes no implementation; another module or the application may register one
 * over a price catalogue. Callers hold a nullable reference and null-guard every call, so without
 * one a request is served, its tokens are recorded, and no cost figure is produced.
 *
 * <p>There is deliberately no no-op: an estimator returning {@code 0.0} reads at the call site
 * exactly like a call that is genuinely free, and a per-call ceiling fed that zero would report a
 * number rather than report nothing.
 */
public interface CostEstimator {

    double estimateRequestCost(ChatRequest request);

    double calculateActualCost(ChatRequest request, ChatResponse response);

    default double estimateRequestCostForProvider(ChatRequest request, String provider) {
        return estimateRequestCost(request);
    }

    /**
     * Whether a pricing row is available for the model, so callers can tell a
     * genuine $0 estimate from an unpriced-model $0 (which {@link
     * #estimateRequestCost} returns for an unknown model). Default {@code true}
     * for implementations that do not track a pricing catalogue; one that does
     * should override it with a real lookup.
     */
    default boolean hasPricing(String model) {
        return true;
    }
}