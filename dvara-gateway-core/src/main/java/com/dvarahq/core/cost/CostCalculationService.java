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

import java.util.Optional;

/**
 * Calculates and persists per-request costs.
 *
 * <p>This build includes no implementation; another module or the application may register one
 * that resolves pricing, computes the cost and persists a {@link CostRecord}. Callers hold a
 * nullable reference and write no cost row when none is registered. There is deliberately no
 * no-op: a service returning {@link Optional#empty()} would read at the call site exactly like a
 * call priced at nothing.</p>
 */
public interface CostCalculationService {

    Optional<CostRecord> calculateAndPersist(ChatRequest request, ChatResponse response,
                                              String workspaceId, String apiKey, String provider);
}