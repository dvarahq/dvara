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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Per-request cost record attributed to workspace, API key, model, and provider.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CostRecord {

    private String id;
    private String workspaceId;

    /**
     * The API key's <b>opaque id</b>, not the key and not a prefix of it: the same value the usage
     * row carries, which is what lets cost and volume be joined for one key.
     */
    private String apiKey;
    private String model;
    private String provider;
    private int inputTokens;
    private int outputTokens;
    private BigDecimal inputCost;
    private BigDecimal outputCost;
    private BigDecimal totalCost;
    @Builder.Default
    private String currency = "USD";
    private String pricingId;
    private Map<String, String> tags;
    private Instant timestamp;
}