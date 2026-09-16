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
package com.dvarahq.core.metering;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Immutable record of token usage for a single request.
 * Persisted per workspace/key/model/timestamp for consumption tracking.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageRecord {

    private String id;

    private String workspaceId;

    /**
     * The API key's <b>opaque id</b>, not the key and not a prefix of it: the same value cost rows
     * carry. Nothing in this field is a secret.
     */
    private String apiKey;
    private String model;
    private String provider;
    private int inputTokens;
    private int outputTokens;
    private int totalTokens;
    private boolean estimated;
    /**
     * HMAC fingerprint of the upstream credential this request used — never the key itself.
     *
     * <p><b>Null is meaningful, not missing.</b> A semantic-cache HIT made no upstream call, and a
     * provider needing no secret resolved none; neither is attributable egress. Only a non-null value
     * says "this workspace sent traffic to a provider under this credential", which is the exact
     * statement an abuse report has to be answered with.
     */
    private String credentialFingerprint;

    /**
     * Semantic-cache status, and a billing input: {@code "HIT"} means served from cache — no upstream
     * call and no upstream cost — while {@code "MISS"} means an upstream call happened, or the cache
     * was never consulted. <b>Null is persisted as {@code "MISS"}.</b>
     */
    private String cacheStatus;
    private Instant timestamp;
}