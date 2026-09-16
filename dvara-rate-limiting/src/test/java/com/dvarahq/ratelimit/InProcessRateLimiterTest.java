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
import io.github.bucket4j.TimeMeter;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InProcessRateLimiter} — the shared {@link RateLimiterContract} plus what is specific to
 * keeping the counters in this process.
 *
 * <p>Time is injected rather than slept through. Bucket4j takes a {@link TimeMeter}, so a window can
 * be advanced in a nanosecond instead of a minute — which is what makes the refill and eviction
 * cases testable at all.
 */
class InProcessRateLimiterTest extends RateLimiterContract {

    @Override
    protected RateLimiter limiter(boolean enabled, int requestLimit, int tokenLimit) {
        return new InProcessRateLimiter(enabled, requestLimit, tokenLimit, null);
    }

    /** A clock the test moves by hand. */
    private static final class TestClock implements TimeMeter {
        private long nanos = System.currentTimeMillis() * 1_000_000L;

        @Override public long currentTimeNanos() { return nanos; }
        @Override public boolean isWallClockBased() { return true; }

        void advance(Duration d) { nanos += d.toNanos(); }
    }

    private InProcessRateLimiter limiterOn(TestClock clock, int requests, int tokens) {
        return new InProcessRateLimiter(true, requests, tokens, null, clock);
    }

    // --- the token dimension --------------------------------------------------------------

    /**
     * Burst capacity is separate from refill rate.
     *
     * <p>Rate 10 a minute, capacity 3. A cold bucket starts with **3**, not 10 — which is the
     * difference the setting exists to express, and what "how bursty should this limit be" actually
     * means.
     *
     * <p>The fourth request discriminates: with burst defaulting to the rate, as every existing
     * caller gets, it is admitted.
     */
    @Test
    void aSmallerBurstAdmitsLessFromCold() {
        InProcessRateLimiter limiter = new InProcessRateLimiter(true, 10, 0, 3, 0, null,
                io.github.bucket4j.TimeMeter.SYSTEM_MILLISECONDS);

        for (int i = 1; i <= 3; i++) {
            assertThat(limiter.tryAcquire("k")).as("request %d fits the capacity of 3", i).isTrue();
        }
        assertThat(limiter.tryAcquire("k"))
                .as("the 4th exceeds the capacity, though the RATE would allow 10")
                .isFalse();
    }

    @Test
    void anEstimateOverTheTokenBudgetIsRefusedAndSaysSo() {
        RateLimiter limiter = new InProcessRateLimiter(true, 100, 1_000, null);

        assertThat(limiter.checkLimit("k", 400).allowed()).isTrue();
        assertThat(limiter.checkLimit("k", 400).allowed()).isTrue();

        RateLimitResult refused = limiter.checkLimit("k", 400);
        assertThat(refused.allowed())
                .as("1200 estimated tokens against a 1000/min budget must be refused")
                .isFalse();
        assertThat(refused.detail()).isNotNull();
        assertThat(refused.detail().getLimitedResource()).isEqualTo("tokens");
        assertThat(refused.detail().getLimitType()).isEqualTo("tokens_per_minute");
        assertThat(refused.detail().getLimit()).isEqualTo(1_000);
    }

    @Test
    void theRequestLimitIsReportedBeforeTheTokenLimit() {
        // Both are exhausted; the caller is told about requests first, so it fixes the right thing.
        RateLimiter limiter = new InProcessRateLimiter(true, 1, 1, null);
        limiter.checkLimit("k", 1);

        RateLimitResult refused = limiter.checkLimit("k", 5_000);
        assertThat(refused.allowed()).isFalse();
        assertThat(refused.detail().getLimitedResource()).isEqualTo("requests");
    }

    @Test
    void usageRecordedAfterTheResponseCountsAgainstTheBudget() {
        RateLimiter limiter = new InProcessRateLimiter(true, 100, 1_000, null);

        // Admission charged nothing (no estimate), then the real usage lands.
        assertThat(limiter.checkLimit("k").allowed()).isTrue();
        limiter.recordTokenUsage("k", 900);

        assertThat(limiter.checkLimit("k", 200).allowed())
                .as("900 already spent of 1000 leaves no room for a 200-token estimate")
                .isFalse();
    }

    @Test
    void overspendingGoesIntoDeficitRatherThanBeingForgotten() {
        RateLimiter limiter = new InProcessRateLimiter(true, 100, 1_000, null);

        // The estimate was wildly low: charged 10, actually used 5000.
        limiter.checkLimit("k", 10);
        limiter.recordTokenUsage("k", 5_000);

        assertThat(limiter.checkLimit("k", 1).allowed())
                .as("an overdraft must be carried, or under-estimating is free")
                .isFalse();
    }

    @Test
    void zeroAndNegativeUsageAreIgnored() {
        RateLimiter limiter = new InProcessRateLimiter(true, 100, 10, null);
        limiter.recordTokenUsage("k", 0);
        limiter.recordTokenUsage("k", -50);

        assertThat(limiter.checkLimit("k", 10).allowed()).isTrue();
    }

    @Test
    void aDisabledLimiterRecordsNothing() {
        InProcessRateLimiter limiter = new InProcessRateLimiter(false, 1, 1, null);
        limiter.recordTokenUsage("k", 10_000);

        assertThat(limiter.trackedKeyCount())
                .as("a disabled limiter must not allocate state")
                .isZero();
    }

    // --- refill, which only a controllable clock can test ---------------------------------

    @Test
    void theBudgetRefillsAsTheWindowPasses() {
        TestClock clock = new TestClock();
        InProcessRateLimiter limiter = limiterOn(clock, 2, 1_000_000);

        assertThat(limiter.checkLimit("k").allowed()).isTrue();
        assertThat(limiter.checkLimit("k").allowed()).isTrue();
        assertThat(limiter.checkLimit("k").allowed()).isFalse();

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.checkLimit("k").allowed())
                .as("a full window later the allowance is back")
                .isTrue();
    }

    @Test
    void refillIsGradualRatherThanAllAtOnce() {
        TestClock clock = new TestClock();
        InProcessRateLimiter limiter = limiterOn(clock, 60, 1_000_000);
        for (int i = 0; i < 60; i++) {
            limiter.checkLimit("k");
        }
        assertThat(limiter.checkLimit("k").allowed()).isFalse();

        // 60/min is 1/sec, so one second buys exactly one request back. That is token-bucket
        // behaviour, pinned here so a change to it is a visible decision.
        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.checkLimit("k").allowed()).isTrue();
        assertThat(limiter.checkLimit("k").allowed()).isFalse();
    }

    // --- per-workspace overrides, beyond the contract --------------------------------------

    @Test
    void aChangedOverrideDoesNotResetConsumption() {
        // Limits are editable at runtime. If changing one rebuilt the bucket, anyone able to
        // edit a limit could clear their own consumption by nudging it.
        InProcessRateLimiter limiter = new InProcessRateLimiter(true, 100, 1_000_000, null);
        EffectiveRateLimit two = new EffectiveRateLimit(2, 0);
        EffectiveRateLimit four = new EffectiveRateLimit(4, 0);

        assertThat(limiter.checkLimit("k", two).allowed()).isTrue();
        assertThat(limiter.checkLimit("k", two).allowed()).isTrue();
        assertThat(limiter.checkLimit("k", two).allowed()).isFalse();

        // Widening to 4 adds the delta: two more now, and the two already spent stay spent.
        assertThat(limiter.checkLimit("k", four).allowed()).isTrue();
        assertThat(limiter.checkLimit("k", four).allowed()).isTrue();
        assertThat(limiter.checkLimit("k", four).allowed())
                .as("widening the limit must not hand back a full bucket")
                .isFalse();
    }

    // --- bounded memory ---------------------------------------------------------------------

    @Test
    void idleKeysAreEvicted() {
        TestClock clock = new TestClock();
        InProcessRateLimiter limiter = limiterOn(clock, 1_000_000, 1_000_000);

        for (int i = 0; i < 200; i++) {
            limiter.checkLimit("stale-" + i);
        }
        assertThat(limiter.trackedKeyCount()).isEqualTo(200);

        clock.advance(Duration.ofMinutes(5));

        // The sweep is opportunistic — every 1024 calls — rather than scheduled, because this class
        // ships in a published library and scheduled work stays on the control plane.
        for (int i = 0; i < 1024; i++) {
            limiter.checkLimit("live");
        }

        assertThat(limiter.trackedKeyCount())
                .as("keys idle for two windows must not be held forever — this is a long-running process")
                .isEqualTo(1);
    }

    /**
     * An overdraft survives the idle sweep. If the sweep evicted a key in deficit, the key would come
     * back to a full bucket and the overspend {@code reconcileTokens} charged would be forgiven by
     * waiting.
     */
    @Test
    void anIdleKeyInTokenDeficitIsNotSweptIntoAFreshBucket() {
        TestClock clock = new TestClock();
        InProcessRateLimiter limiter = limiterOn(clock, 1_000_000, 1_000);

        // Reserved 10, used 10,000: nine minutes of refill owed on a 1,000/min budget.
        assertThat(limiter.checkLimit("k", 10).allowed()).isTrue();
        limiter.reconcileTokens("k", 10, 10_000);

        // Idle past the two-window eviction age, then enough traffic on another key to run a sweep.
        clock.advance(Duration.ofMinutes(3));
        for (int i = 0; i < 1024; i++) {
            limiter.checkLimit("live");
        }

        assertThat(limiter.checkLimit("k", 1).allowed())
                .as("about six minutes of deficit are still owed; a sweep must not forgive them")
                .isFalse();
    }

    @Test
    void aKeyWhoseDeficitHasRefilledIsSweptLikeAnyOther() {
        TestClock clock = new TestClock();
        InProcessRateLimiter limiter = limiterOn(clock, 1_000_000, 1_000);
        limiter.checkLimit("k", 10);
        limiter.reconcileTokens("k", 10, 10_000);

        clock.advance(Duration.ofMinutes(15));
        for (int i = 0; i < 1024; i++) {
            limiter.checkLimit("live");
        }

        assertThat(limiter.trackedKeyCount())
                .as("the deficit refilled long ago, so keeping the entry would only hold memory")
                .isEqualTo(1);
    }

    // --- concurrency, recovered from the deleted suite --------------------------------------

    @Test
    void concurrentCallersDoNotExceedTheLimit() throws InterruptedException {
        int limit = 50;
        int threads = 40;
        int callsEach = 10;
        RateLimiter limiter = new InProcessRateLimiter(true, limit, 1_000_000, null);

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < callsEach; i++) {
                            if (limiter.checkLimit("hot").allowed()) {
                                allowed.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get())
                .as("400 concurrent callers against a limit of %d must not over-admit", limit)
                .isLessThanOrEqualTo(limit);
        assertThat(allowed.get())
                .as("...nor under-admit: the limit should actually be reached")
                .isGreaterThanOrEqualTo(limit - 1);
    }

    // --- fail-open -------------------------------------------------------------------------

    @Test
    void aLimiterThatCannotCountAllowsRatherThanThrows() {
        // A clock that throws stands in for any internal failure. Enforcement failing closed would
        // turn a limiter defect into a total outage, which is the wrong direction for this control.
        TimeMeter broken = new TimeMeter() {
            @Override public long currentTimeNanos() { throw new IllegalStateException("clock failed"); }
            @Override public boolean isWallClockBased() { return true; }
        };
        RateLimiter limiter = new InProcessRateLimiter(true, 1, 1, null, broken);

        assertThat(limiter.checkLimit("k").allowed()).isTrue();
        assertThat(limiter.checkLimit("k").allowed()).isTrue();
    }

    /**
     * The error counter is registered with the other three, so it exists at zero before anything has
     * failed and an alert on its rate has a series to read from the start.
     */
    @Test
    void theErrorCounterIsExportedAtZeroBeforeAnyFailure() {
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        TimeMeter broken = new TimeMeter() {
            @Override public long currentTimeNanos() { throw new IllegalStateException("clock failed"); }
            @Override public boolean isWallClockBased() { return true; }
        };
        RateLimiter limiter = new InProcessRateLimiter(true, 1, 1, registry, broken);

        io.micrometer.core.instrument.Counter errors =
                registry.find("gateway_rate_limit_errors_total").counter();
        assertThat(errors).as("registered at construction, not on the first failure").isNotNull();
        assertThat(errors.count()).isZero();

        limiter.checkLimit("k");
        assertThat(errors.count()).as("a failure counts on the counter that was already there").isEqualTo(1.0);
    }

    /**
     * A caller told to wait N seconds is not refused when it waits N seconds.
     *
     * <p>{@code Duration.toSeconds()} TRUNCATES, so with 1.9 seconds of refill left the limiter said
     * "1" and a client obeying exactly was refused again — which reads as a broken limiter rather
     * than a rounding error.
     *
     * <p><b>This needs a controlled clock, which is why it is not in the shared contract.</b> A first
     * attempt lived there and asserted {@code retryAfter >= 1}; it passed with truncation restored,
     * because every wait the contract can produce lands on a whole second. The bug only appears when
     * time has partly elapsed, so the test has to move the clock by a fraction.
     */
    @Test
    void retryAfterIsNeverShorterThanTheActualWait() {
        TestClock clock = new TestClock();
        InProcessRateLimiter limiter = limiterOn(clock, 2, 0);   // 2/min -> one token per 30s
        String key = "k";

        assertThat(limiter.tryAcquire(key)).isTrue();
        assertThat(limiter.tryAcquire(key)).isTrue();

        // 10.5s in: 19.5s remain before the next token. Truncation reports 19.
        clock.advance(Duration.ofMillis(10_500));
        RateLimitResult refused = limiter.checkLimit(key);
        assertThat(refused.allowed()).isFalse();

        long told = refused.retryAfterSeconds();
        clock.advance(Duration.ofSeconds(told));
        assertThat(limiter.checkLimit(key).allowed())
                .as("waiting exactly as long as the limiter said must be enough — it said %ds", told)
                .isTrue();
    }

    /**
     * A burst belongs to the install unless a workspace overrides the rate, and it has to survive
     * the override being withdrawn.
     *
     * <p>The entry is re-rated in place rather than rebuilt, so whatever capacity the reconfigure
     * chooses is what the key keeps. Setting it to the rate — right for an override, wrong for the
     * default — left this key admitting eight in a row where the operator configured three, until
     * the next idle sweep recreated it correctly.</p>
     */
    @Test
    void aConfiguredBurstSurvivesAWorkspaceOverrideBeingWithdrawn() {
        InProcessRateLimiter limiter = new InProcessRateLimiter(true, 10, 0, 3, 0, null,
                io.github.bucket4j.TimeMeter.SYSTEM_MILLISECONDS);
        var override = new com.dvarahq.core.ratelimit.EffectiveRateLimit(5, 0);
        var none = com.dvarahq.core.ratelimit.EffectiveRateLimit.NONE;

        limiter.checkLimit("k", 0, override);
        limiter.checkLimit("k", 0, none);

        int admitted = 0;
        while (admitted < 20 && limiter.checkLimit("k", 0, none).allowed()) {
            admitted++;
        }

        assertThat(admitted)
                .as("capacity is the configured burst of 3, not the rate of 10")
                .isLessThanOrEqualTo(3);
    }

    /** The other half of the same rule: an override still narrows the burst with the rate. */
    @Test
    void anOverrideStillScalesTheBurstDownWithTheRate() {
        InProcessRateLimiter limiter = new InProcessRateLimiter(true, 100, 0, 100, 0, null,
                io.github.bucket4j.TimeMeter.SYSTEM_MILLISECONDS);
        var override = new com.dvarahq.core.ratelimit.EffectiveRateLimit(4, 0);

        limiter.checkLimit("k", 0, com.dvarahq.core.ratelimit.EffectiveRateLimit.NONE);

        int admitted = 0;
        while (admitted < 50 && limiter.checkLimit("k", 0, override).allowed()) {
            admitted++;
        }

        assertThat(admitted)
                .as("the override's rate of 4 is the capacity too, not the install's 100")
                .isLessThan(10);
    }
}
