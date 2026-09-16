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
package com.dvarahq.ratelimit;

import com.dvarahq.core.ratelimit.EffectiveRateLimit;
import com.dvarahq.core.ratelimit.RateLimitResult;
import com.dvarahq.core.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every {@link RateLimiter} must do, regardless of where it keeps its counters.
 *
 * <p>A limiter may keep its counters in this process or in a shared store. Whatever it does with
 * them, a caller of the interface is entitled to the same behaviour, so every implementation
 * extends this class and the interface is tested once.
 *
 * <h2>How to use it</h2>
 *
 * <p>Extend and implement {@link #limiter}. Engine-specific behaviour — eviction, the shape of the
 * backing store, distribution — stays in the subclass; this covers only what a caller of the
 * interface is entitled to assume.
 *
 * <p>Implementations whose {@code limitForPeriod} cannot be honoured exactly (a token bucket admits
 * a full bucket after idle, a sliding window does not) still satisfy this: every assertion here is
 * about a burst from a cold start, which both admit identically.
 */
public abstract class RateLimiterContract {


    /**
     * Build a limiter under test.
     *
     * @param enabled       whether it should enforce at all
     * @param requestLimit  requests admitted per minute, per key
     * @param tokenLimit    tokens admitted per minute, per key
     */
    protected abstract RateLimiter limiter(boolean enabled, int requestLimit, int tokenLimit);

    /** A key unique to the calling test, so a shared backing store does not leak between them. */
    protected String key(String name) {
        return getClass().getSimpleName() + ":" + name + ":" + System.nanoTime();
    }

    // --- settlement, which is where the two implementations disagreed -------------------

    /**
     * A call costs its ACTUAL usage, not its estimate plus its actual.
     *
     * <p>Admission charges an estimate so a request cannot be admitted on optimism;
     * {@code reconcileTokens} then settles the difference. Both implementations consumed the
     * estimate and then consumed the actual on top, so a call cost roughly double — and neither
     * had a test that added the two halves together, which is why it survived in both.
     */
    @Test
    void aCallCostsItsActualUsageNotItsEstimatePlusItsActual() {
        RateLimiter limiter = limiter(true, 1_000, 100);
        String k = key("settle");

        // Reserve 30, actually use 40. Total cost must be 40, leaving 60 of 100.
        com.dvarahq.core.ratelimit.RateLimitResult admitted = limiter.checkLimit(k, 30);
        assertThat(admitted.allowed()).isTrue();
        // Settled through the handle admission returned: a window limiter finds its own
        // reservation by it, a bucket ignores it. The request path always settles this way.
        limiter.reconcileTokens(k, 30, 40, admitted.reservationId());

        assertThat(limiter.checkLimit(k, 60).allowed())
                .as("60 more fits in the 60 that should remain")
                .isTrue();
        assertThat(limiter.checkLimit(k, 1).allowed())
                .as("but nothing beyond it — if the estimate had also been charged, "
                        + "far less than 60 would have been left")
                .isFalse();
    }

    /**
     * An over-estimate refunds.
     *
     * <p>Otherwise a cautious estimator costs the caller real allowance for tokens nobody used, and
     * the only way to avoid it would be to under-estimate — which is the opposite of what a
     * pre-request check is for.
     */
    @Test
    void anOverEstimateIsRefunded() {
        RateLimiter limiter = limiter(true, 1_000, 100);
        String k = key("refund");

        com.dvarahq.core.ratelimit.RateLimitResult admitted = limiter.checkLimit(k, 80);
        assertThat(admitted.allowed()).isTrue();
        limiter.reconcileTokens(k, 80, 20, admitted.reservationId());   // only 20 were really used

        assertThat(limiter.checkLimit(k, 80).allowed())
                .as("80 of the 100 should be free again")
                .isTrue();
    }

    /**
     * A token refusal must not spend a request permit.
     *
     * <p>The request bucket is consumed before the token check, so without a refund a caller refused
     * on tokens drains its REQUEST budget on requests that were never served — and is eventually
     * refused for the wrong reason entirely.
     */
    @Test
    void aTokenRefusalDoesNotSpendARequestPermit() {
        RateLimiter limiter = limiter(true, 3, 10);
        String k = key("atomic");

        // Each of these is refused on tokens, and must cost no request permit.
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.checkLimit(k, 999).allowed()).as("refused on tokens").isFalse();
        }

        assertThat(limiter.checkLimit(k, 1).allowed())
                .as("all 3 request permits must still be available")
                .isTrue();
    }

    // --- the request dimension ------------------------------------------------------------

    @Test
    void itAdmitsUpToTheLimitAndThenRefuses() {
        RateLimiter limiter = limiter(true, 3, 1_000_000);
        String k = key("burst");

        assertThat(limiter.checkLimit(k).allowed()).isTrue();
        assertThat(limiter.checkLimit(k).allowed()).isTrue();
        assertThat(limiter.checkLimit(k).allowed()).isTrue();

        RateLimitResult refused = limiter.checkLimit(k);
        assertThat(refused.allowed())
                .as("the 4th request against a limit of 3 must be refused")
                .isFalse();
        assertThat(refused.retryAfterSeconds())
                .as("a refusal must tell the caller when to come back, or the 429 has no Retry-After")
                .isGreaterThan(0);
    }

    @Test
    void keysAreCountedIndependently() {
        RateLimiter limiter = limiter(true, 1, 1_000_000);
        String a = key("a");
        String b = key("b");

        assertThat(limiter.checkLimit(a).allowed()).isTrue();
        assertThat(limiter.checkLimit(a).allowed()).isFalse();
        assertThat(limiter.checkLimit(b).allowed())
                .as("one key exhausting its limit must not affect another")
                .isTrue();
    }

    @Test
    void disabledAdmitsEverything() {
        RateLimiter limiter = limiter(false, 1, 1);
        String k = key("disabled");

        for (int i = 0; i < 10; i++) {
            assertThat(limiter.checkLimit(k).allowed())
                    .as("request %d — a disabled limiter must not count", i)
                    .isTrue();
        }
    }

    /**
     * The negative control for {@link #disabledAdmitsEverything()}.
     *
     * <p>On its own, "disabled admits everything" is also what a limiter that never counts looks
     * like — which is precisely the state this contract exists to fix. Pairing the two is what distinguishes
     * "switched off" from "does nothing".
     */
    @Test
    void enabledAndDisabledAreDistinguishable() {
        String k = key("control");
        assertThat(limiter(false, 1, 1_000_000).checkLimit(k).allowed()).isTrue();
        assertThat(limiter(false, 1, 1_000_000).checkLimit(k).allowed()).isTrue();

        RateLimiter enforcing = limiter(true, 1, 1_000_000);
        String k2 = key("control-enforcing");
        assertThat(enforcing.checkLimit(k2).allowed()).isTrue();
        assertThat(enforcing.checkLimit(k2).allowed())
                .as("with the same traffic, an enabled limiter must refuse where a disabled one did not")
                .isFalse();
    }

    // --- per-workspace overrides ---------------------------------------------------

    /**
     * The case that shipped broken in {@code SharedCacheRateLimiter}.
     *
     * <p>{@code RateLimitServletFilter} always passes an override, and it is {@link
     * EffectiveRateLimit#NONE} for any workspace that has not set one — which is every workspace on
     * an install that has never used the feature. {@code NONE} means "use your configured default",
     * not "your limit is zero".
     */
    @Test
    void anAbsentOverrideFallsBackToTheConfiguredDefault() {
        RateLimiter limiter = limiter(true, 2, 1_000_000);
        String k = key("none-override");

        assertThat(limiter.checkLimit(k, EffectiveRateLimit.NONE).allowed())
                .as("EffectiveRateLimit.NONE must mean the configured default, not a limit of zero")
                .isTrue();
        assertThat(limiter.checkLimit(k, EffectiveRateLimit.NONE).allowed()).isTrue();
        assertThat(limiter.checkLimit(k, EffectiveRateLimit.NONE).allowed())
                .as("and the default must still apply — the 3rd request against a limit of 2")
                .isFalse();
    }

    @Test
    void aLowerOverrideNarrowsTheLimit() {
        RateLimiter limiter = limiter(true, 100, 1_000_000);
        String k = key("narrow");
        EffectiveRateLimit tighter = new EffectiveRateLimit(1, 0);

        assertThat(limiter.checkLimit(k, tighter).allowed()).isTrue();
        assertThat(limiter.checkLimit(k, tighter).allowed())
                .as("an override of 1 must bite well before the configured 100")
                .isFalse();
    }

    @Test
    void aHigherOverrideRaisesTheLimit() {
        RateLimiter limiter = limiter(true, 1, 1_000_000);
        String k = key("widen");
        EffectiveRateLimit looser = new EffectiveRateLimit(5, 0);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.checkLimit(k, looser).allowed())
                    .as("request %d — an override of 5 must admit past the configured 1", i)
                    .isTrue();
        }
        assertThat(limiter.checkLimit(k, looser).allowed()).isFalse();
    }

    // --- shape of a refusal ---------------------------------------------------------------

    @Test
    void aRefusalCarriesEnoughToBuildThe429() {
        RateLimiter limiter = limiter(true, 1, 1_000_000);
        String k = key("detail");
        limiter.checkLimit(k);

        RateLimitResult refused = limiter.checkLimit(k);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.reason())
                .as("RateLimitServletFilter puts this in the error body")
                .isNotBlank();
        assertThat(refused.retryAfterSeconds()).isGreaterThan(0);
    }


    /**
     * A negative limit is refused; ZERO is not.
     *
     * <p>Zero is legitimate and means "this dimension is not enforced" — every check is
     * {@code limit > 0}, {@link EffectiveRateLimit#NONE} is {@code (0, 0)}, and a caller that limits
     * requests but not tokens passes zero deliberately. A negative behaves identically to zero today
     * and silently, so a fat-fingered minus sign switches enforcement off with nothing said.
     */
    @Test
    void aNegativeLimitIsRefusedButZeroIsNot() {
        assertThatThrownBy(() -> limiter(true, -1, 0))
                .as("a minus sign must not be able to switch enforcement off quietly")
                .isInstanceOf(IllegalArgumentException.class);

        assertThatCode(() -> limiter(true, 10, 0))
                .as("zero means 'not enforced' and is how every requests-only caller is configured")
                .doesNotThrowAnyException();
    }
}