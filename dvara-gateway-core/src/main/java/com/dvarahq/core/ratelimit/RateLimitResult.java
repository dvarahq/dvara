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

public record RateLimitResult(boolean allowed, long retryAfterSeconds, String reason,
                               RateLimitErrorDetail detail, long reservationId) {

    private static final RateLimitResult ALLOWED_RESULT = new RateLimitResult(true, 0, null, null, 0);

    public RateLimitResult(boolean allowed, long retryAfterSeconds, String reason,
                           RateLimitErrorDetail detail) {
        this(allowed, retryAfterSeconds, reason, detail, 0);
    }

    /**
     * Admitted, with a token reservation the limiter can find again at settlement.
     *
     * <p>{@code reservationId} is opaque to the caller and meaningful only to the limiter that
     * issued it; it is handed back through {@link RateLimiter#reconcileTokens(String, int, int, long)}.
     * A limiter whose settlement does not need one — a token bucket, which holds no per-request
     * entry — returns {@code 0}, and {@code 0} is what {@link #allow()} carries.
     */
    public static RateLimitResult allow(long reservationId) {
        return reservationId == 0 ? ALLOWED_RESULT : new RateLimitResult(true, 0, null, null, reservationId);
    }

    public RateLimitResult(boolean allowed, long retryAfterSeconds, String reason) {
        this(allowed, retryAfterSeconds, reason, null);
    }

    public static RateLimitResult allow() {
        return ALLOWED_RESULT;
    }

    public static RateLimitResult reject(long retryAfterSeconds, String reason) {
        return new RateLimitResult(false, retryAfterSeconds, reason, null);
    }

    public static RateLimitResult reject(long retryAfterSeconds, String reason, RateLimitErrorDetail detail) {
        return new RateLimitResult(false, retryAfterSeconds, reason, detail);
    }
}