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
package com.dvarahq.core.ratelimit;

/**
 * A per-workspace rate-limit override. A value {@code > 0} overrides the corresponding global
 * {@code per-key} default; a value {@code <= 0} means "no override — use the global default". This lets
 * a {@link WorkspaceRateLimitResolver} carry a partial override (e.g. a bespoke request cap but the default
 * token cap) without the limiter needing to know the global defaults twice.
 */
public record EffectiveRateLimit(int requestsPerMinute, int tokensPerMinute) {

    /** No override on either dimension — the limiter falls back to its configured global defaults. */
    public static final EffectiveRateLimit NONE = new EffectiveRateLimit(0, 0);

    public boolean hasRequestOverride() {
        return requestsPerMinute > 0;
    }

    public boolean hasTokenOverride() {
        return tokensPerMinute > 0;
    }

    public boolean isEmpty() {
        return !hasRequestOverride() && !hasTokenOverride();
    }

    /** The effective request cap: this override if set, otherwise {@code globalDefault}. */
    public int requestLimitOr(int globalDefault) {
        return hasRequestOverride() ? requestsPerMinute : globalDefault;
    }

    /** The effective token cap: this override if set, otherwise {@code globalDefault}. */
    public int tokenLimitOr(int globalDefault) {
        return hasTokenOverride() ? tokensPerMinute : globalDefault;
    }
}