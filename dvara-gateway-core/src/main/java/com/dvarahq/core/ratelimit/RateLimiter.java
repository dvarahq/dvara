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
 * The rate-limit seam the data plane calls.
 *
 * <p><b>Implementing the two abstract methods is not enough to be a correct limiter, and the
 * shortfall is silent.</b> Everything else here has a default that degrades rather than fails: the
 * token-aware checks fall back to the request-only check, so tokens go uncounted; the override-aware
 * checks drop the per-workspace caps, so {@code rate-limit.tokens-per-minute} on a workspace does
 * nothing; and {@code reconcileTokens} does nothing at all, so a request costs its estimate for ever.
 * Each default exists so a minimal or test implementation still compiles; none of them is a
 * posture anybody should ship.
 *
 * <p>An implementation meant for production overrides {@link #checkLimit(String, int,
 * EffectiveRateLimit)} and {@link #reconcileTokens(String, int, int)} at minimum, and
 * {@link #reconcileTokens(String, int, int, long)} as well if it keeps a per-request entry rather
 * than a bucket. {@code RateLimiterContract} in the rate-limiting modules' tests is what holds every
 * implementation to the same answers; run a new one against it rather than trusting the defaults.
 */
public interface RateLimiter {

    boolean tryAcquire(String key);

    boolean tryAcquire(String key, int permits);

    default RateLimitResult checkLimit(String key) {
        return tryAcquire(key) ? RateLimitResult.allow() : RateLimitResult.reject(1, "Rate limit exceeded");
    }

    /**
     * Check rate limit with estimated token count for pre-request token budget enforcement.
     * Default: delegates to request-only checkLimit(key).
     */
    default RateLimitResult checkLimit(String key, int estimatedTokens) {
        return checkLimit(key);
    }

    /**
     * Request-only check honoring a per-workspace {@link EffectiveRateLimit} override. A dimension
     * with no override falls back to the limiter's configured global default. The interface default
     * delegates to the request-only check for legacy or test implementations that do not support
     * workspace-specific caps.
     */
    default RateLimitResult checkLimit(String key, EffectiveRateLimit override) {
        return checkLimit(key);
    }

    /**
     * Token-aware check honoring a per-workspace {@link EffectiveRateLimit} override. A dimension
     * with no override falls back to the limiter's configured global default. Default: ignores the
     * override.
     */
    default RateLimitResult checkLimit(String key, int estimatedTokens, EffectiveRateLimit override) {
        return checkLimit(key, estimatedTokens);
    }

    /**
     * Settles a request's token cost against what was reserved for it.
     *
     * <p>{@code checkLimit(key, estimatedTokens, ...)} charges an estimate to admit the request.
     * This charges the difference once the real figure is known, so a call costs exactly
     * {@code actual} rather than {@code estimate + actual}.
     *
     * <p>{@code reserved} is what the caller passed as {@code estimatedTokens}, or {@code 0} where
     * nothing was reserved. Embeddings are the second case: token estimation runs only for chat, so
     * that path reserves nothing and settles the whole actual — which this expresses without a
     * special case.
     *
     * <p>An over-estimate refunds. Deliberately: the alternative is charging for tokens nobody used,
     * and a caller cannot know in advance how long a response will be.
     *
     * <p>Never throws and never refuses. The request has already been served; refusing it now is not
     * available, and a limiter that threw here would fail a request that had already succeeded.
     */
    default void reconcileTokens(String key, int reserved, int actual) {
        // no-op by default
    }

    /**
     * {@link #reconcileTokens(String, int, int)}, naming the reservation being settled.
     *
     * <p>{@code reservationId} is what {@link RateLimitResult#reservationId()} carried when the
     * request was admitted, or {@code 0} where nothing was reserved or the limiter issues none. A
     * limiter that keeps a per-request entry — a sliding window — needs it: a settlement that lands
     * after its own reservation has aged out of the window, which a stream longer than the window
     * makes ordinary, must not refund against whatever the window holds now, and two reservations
     * holding the same estimate cannot be told apart by their value. Such a limiter cannot refund
     * a reservation it is not told about. A token bucket needs nothing beyond the delta, which is
     * why this delegates to the three-argument form by default. Every path that may hold a token
     * reservation calls this form.
     */
    default void reconcileTokens(String key, int reserved, int actual, long reservationId) {
        reconcileTokens(key, reserved, actual);
    }

    /**
     * @deprecated prefer {@link #reconcileTokens}, which knows what was reserved. This charges
     *     {@code tokens} on top of any reservation, so on a path that reserved an estimate it
     *     double-counts. A path that reserves nothing is still correct.
     */
    @Deprecated
    default void recordTokenUsage(String key, int tokens) {
        // Delegates rather than no-ops: reserving nothing and settling the whole amount is what this
        // method means, and a no-op would turn an over-charge into a silent under-charge.
        reconcileTokens(key, 0, tokens);
    }
}
