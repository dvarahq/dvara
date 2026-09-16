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
import com.dvarahq.core.ratelimit.RateLimitErrorDetail;
import com.dvarahq.core.ratelimit.RateLimitResult;
import com.dvarahq.core.ratelimit.RateLimiter;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.TokensInheritanceStrategy;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-key token buckets held in this process.
 *
 * <p>Each key has a request bucket and a token bucket of capacity {@code n}, refilling at
 * {@code n} per minute. An idle key can spend its whole allowance at once and is then admitted at
 * {@code n/60} per second, so across an unlucky minute it can be admitted close to {@code 2n}: the
 * full bucket at the start plus a minute of refill. A limiter that keeps a sliding window would
 * refuse that burst, so the same {@code requests-per-minute} means something slightly different
 * here.
 *
 * <p>Counts are per JVM. Replicas behind a load balancer each admit the configured rate, so N
 * replicas admit N times it, and enabling the limiter logs a warning saying so. To share the count,
 * register another {@link RateLimiter} bean; this one stands down when one is present.
 */
public class InProcessRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(InProcessRateLimiter.class);

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /**
     * Entries untouched for two windows are evicted. Two rather than one so a key that goes quiet
     * for slightly over a minute is not charged a fresh full bucket the moment it returns.
     */
    private static final long IDLE_EVICTION_MILLIS = WINDOW.toMillis() * 2;

    /** Idle entries are swept every {@value} calls rather than on a timer, so a library starts no thread of its own. */
    private static final int SWEEP_EVERY = 1024;

    private final boolean enabled;
    private final int defaultRequestLimit;
    private final int defaultTokenLimit;
    /** How big a burst is allowed after idling. Defaults to the rate. */
    private final int defaultRequestBurst;
    private final int defaultTokenBurst;
    private final TimeMeter clock;

    /**
     * Resolved once rather than per call; null when there is no registry. Resolving the error
     * counter up front also exports it at zero from startup, so an alert on its rate has a series
     * to read before the first failure.
     */
    private final Counter allowed;
    private final Counter deniedRequest;
    private final Counter deniedToken;
    private final Counter errors;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong callsSinceSweep = new AtomicLong();

    public InProcessRateLimiter(boolean enabled, int defaultRequestLimit, int defaultTokenLimit,
                                MeterRegistry meterRegistry) {
        this(enabled, defaultRequestLimit, defaultTokenLimit, meterRegistry, TimeMeter.SYSTEM_MILLISECONDS);
    }

    /** The clock is injectable so tests can advance time instead of sleeping through a window. */
    public InProcessRateLimiter(boolean enabled, int defaultRequestLimit, int defaultTokenLimit,
                                MeterRegistry meterRegistry, TimeMeter clock) {
        this(enabled, defaultRequestLimit, defaultTokenLimit, defaultRequestLimit, defaultTokenLimit,
                meterRegistry, clock);
    }

    /**
     * The full form, with burst capacity separate from refill rate. A burst equal to the rate is
     * the default; a smaller burst smooths traffic, a larger one allows a bigger spike after idling.
     */
    public InProcessRateLimiter(boolean enabled, int defaultRequestLimit, int defaultTokenLimit,
                                int requestBurst, int tokenBurst,
                                MeterRegistry meterRegistry, TimeMeter clock) {
        // Zero means the dimension is not enforced: every check below is `limit > 0`, and a caller
        // that limits requests but not tokens passes tokenLimit = 0. A negative limit would behave
        // as zero without saying so, so it is refused.
        if (defaultRequestLimit < 0) {
            throw new IllegalArgumentException(
                    "requests-per-minute must be >= 0 (0 means not enforced), was " + defaultRequestLimit);
        }
        if (defaultTokenLimit < 0) {
            throw new IllegalArgumentException(
                    "tokens-per-minute must be >= 0 (0 means not enforced), was " + defaultTokenLimit);
        }
        this.enabled = enabled;
        this.defaultRequestLimit = defaultRequestLimit;
        this.defaultTokenLimit = defaultTokenLimit;
        this.defaultRequestBurst = requestBurst <= 0 ? defaultRequestLimit : requestBurst;
        this.defaultTokenBurst = tokenBurst <= 0 ? defaultTokenLimit : tokenBurst;
        this.clock = clock;
        this.allowed = meterRegistry == null ? null
                : Counter.builder("gateway_rate_limit_allowed_total").register(meterRegistry);
        this.deniedRequest = meterRegistry == null ? null
                : Counter.builder("gateway_rate_limit_denied_total").tag("reason", "request")
                        .register(meterRegistry);
        this.deniedToken = meterRegistry == null ? null
                : Counter.builder("gateway_rate_limit_denied_total").tag("reason", "token")
                        .register(meterRegistry);
        this.errors = meterRegistry == null ? null
                : Counter.builder("gateway_rate_limit_errors_total").register(meterRegistry);

        if (enabled) {
            log.warn("Rate limiting is on: token bucket, {} requests/min and {} tokens/min,"
                    + " counted per process. Each replica admits this rate on its own, so N replicas"
                    + " admit N times it. To share the count across replicas, register your own"
                    + " RateLimiter bean; this one stands down when another is present.",
                    defaultRequestLimit, defaultTokenLimit);
        }
    }

    /** An override replaces the rate, so the burst must scale with it or the two disagree. */
    private static int burstFor(int effectiveRate, int configuredRate, int configuredBurst) {
        return effectiveRate == configuredRate ? configuredBurst : effectiveRate;
    }

    @Override
    public boolean tryAcquire(String key) {
        return checkLimit(key).allowed();
    }

    @Override
    public boolean tryAcquire(String key, int permits) {
        return check(key, permits, 0, EffectiveRateLimit.NONE).allowed();
    }

    @Override
    public RateLimitResult checkLimit(String key) {
        return check(key, 1, 0, EffectiveRateLimit.NONE);
    }

    @Override
    public RateLimitResult checkLimit(String key, int estimatedTokens) {
        return check(key, 1, estimatedTokens, EffectiveRateLimit.NONE);
    }

    @Override
    public RateLimitResult checkLimit(String key, EffectiveRateLimit override) {
        return check(key, 1, 0, override);
    }

    @Override
    public RateLimitResult checkLimit(String key, int estimatedTokens, EffectiveRateLimit override) {
        return check(key, 1, estimatedTokens, override);
    }

    /**
     * Charges the tokens the request actually used, after the response. Admission charged an
     * estimate; this is the correction. It lets the bucket go into deficit, so a caller that used
     * far more than estimated is refused until the overdraft refills rather than the overage being
     * forgotten. The idle sweep keeps an entry in deficit, so waiting clears it no faster than the
     * refill does.
     */
    @Override
    public void reconcileTokens(String key, int reserved, int actual) {
        if (!enabled) {
            return;
        }
        int delta = actual - reserved;
        if (delta == 0) {
            return;
        }
        try {
            // Create the entry if absent, but do not reconfigure it: reconfiguring with the global
            // defaults would replace a workspace's own limits mid-window. Creation is still wanted,
            // or settling for a key whose entry has been swept would charge nothing.
            Entry entry = entries.computeIfAbsent(key,
                    k -> new Entry(defaultRequestLimit, defaultTokenLimit,
                            defaultRequestBurst, defaultTokenBurst, clock));
            entry.lastSeenMillis = nowMillis();
            if (delta > 0) {
                entry.tokens.consumeIgnoringRateLimits(delta);
            } else {
                // An over-estimate refunds, so a cautious estimator costs the caller nothing.
                entry.tokens.addTokens(-delta);
            }
        } catch (RuntimeException e) {
            // Fail open: a limiter that throws must not take down a request already served.
            countError();
            log.warn("Rate limiter failed to settle token usage for a key (limit not applied): {}",
                    e.toString());
        }
    }

    private RateLimitResult check(String key, int permits, int estimatedTokens, EffectiveRateLimit override) {
        if (!enabled) {
            return RateLimitResult.allow();
        }
        EffectiveRateLimit effective = override == null ? EffectiveRateLimit.NONE : override;
        int requestLimit = effective.requestLimitOr(defaultRequestLimit);
        int tokenLimit = effective.tokenLimitOr(defaultTokenLimit);

        try {
            maybeSweep();
            Entry entry = entryFor(key, requestLimit, tokenLimit);

            // Requests first, so a caller over its request rate is told that rather than about tokens.
            if (requestLimit > 0) {
                ConsumptionProbe probe = entry.requests.tryConsumeAndReturnRemaining(permits);
                if (!probe.isConsumed()) {
                    countDenied("request");
                    return reject(probe, requestLimit, "requests", "requests_per_minute",
                            "Rate limit exceeded");
                }
            }

            if (estimatedTokens > 0 && tokenLimit > 0) {
                ConsumptionProbe probe = entry.tokens.tryConsumeAndReturnRemaining(estimatedTokens);
                if (!probe.isConsumed()) {
                    // Give the request permit back. tryConsumeAndReturnRemaining consumes, so without
                    // this a caller refused on tokens would still spend a request, and one refused
                    // repeatedly would drain its request budget on requests never served. Admission
                    // is all-or-nothing across both dimensions.
                    if (requestLimit > 0) {
                        entry.requests.addTokens(permits);
                    }
                    countDenied("token");
                    return reject(probe, tokenLimit, "tokens", "tokens_per_minute",
                            "Token rate limit exceeded");
                }
            }

            countAllowed();
            return RateLimitResult.allow();
        } catch (RuntimeException e) {
            countError();
            log.warn("Rate limiter failed (allowing the request): {}", e.toString());
            return RateLimitResult.allow();
        }
    }

    private RateLimitResult reject(ConsumptionProbe probe, int limit, String resource,
                                   String limitType, String reason) {
        // Round up: a client that retries exactly when told must not be refused again.
        long retryAfter = Math.max(1,
                (long) Math.ceil(probe.getNanosToWaitForRefill() / 1_000_000_000.0));
        RateLimitErrorDetail detail = RateLimitErrorDetail.builder()
                .limitedResource(resource)
                .limitType(limitType)
                .limit(limit)
                .remaining(Math.max(0, probe.getRemainingTokens()))
                .resetAt(Instant.now().plusNanos(probe.getNanosToWaitForRefill()))
                .retryAfterSeconds(retryAfter)
                .build();
        return RateLimitResult.reject(retryAfter, reason, detail);
    }

    /**
     * The bucket pair for a key, re-rated in place when the effective limit has changed.
     *
     * <p>A workspace can carry its own limits and they can change at runtime. The configuration is
     * replaced rather than the entry dropped, because a fresh entry would hand back a full bucket
     * and anyone able to edit a limit could clear their own consumption by nudging it. With
     * {@link TokensInheritanceStrategy#ADDITIVE} the capacity delta is added to what is available,
     * so raising a limit from 2 to 4 grants two more requests now; scaling proportionally would
     * leave a caller at 0 of 2 on 0 of 4 until the bucket refilled.
     */
    private Entry entryFor(String key, int requestLimit, int tokenLimit) {
        Entry entry = entries.computeIfAbsent(key,
                k -> new Entry(requestLimit, tokenLimit, burstFor(requestLimit, defaultRequestLimit,
                        defaultRequestBurst), burstFor(tokenLimit, defaultTokenLimit, defaultTokenBurst), clock));
        entry.lastSeenMillis = nowMillis();
        // The same burst rule creation uses, so an override round-trip leaves the burst as configured.
        entry.reconfigureIfLimitsChanged(requestLimit, tokenLimit,
                burstFor(requestLimit, defaultRequestLimit, defaultRequestBurst),
                burstFor(tokenLimit, defaultTokenLimit, defaultTokenBurst));
        return entry;
    }

    private void maybeSweep() {
        if (callsSinceSweep.incrementAndGet() % SWEEP_EVERY != 0) {
            return;
        }
        long cutoff = nowMillis() - IDLE_EVICTION_MILLIS;
        for (Iterator<Map.Entry<String, Entry>> it = entries.entrySet().iterator(); it.hasNext(); ) {
            Entry entry = it.next().getValue();
            // An entry still in token deficit is kept however long it has been idle: evicting it would
            // hand the key a full bucket when it returns, so a caller that overspent and then waited
            // would owe nothing. It is swept once the deficit has refilled.
            if (entry.lastSeenMillis < cutoff && !entry.inTokenDeficit()) {
                it.remove();
            }
        }
    }

    private long nowMillis() {
        return clock.currentTimeNanos() / 1_000_000L;
    }

    /** Visible for tests: how many keys are currently held. */
    public int trackedKeyCount() {
        return entries.size();
    }

    private void countAllowed() {
        if (allowed != null) {
            allowed.increment();
        }
    }

    private void countDenied(String reason) {
        Counter c = "token".equals(reason) ? deniedToken : deniedRequest;
        if (c != null) {
            c.increment();
        }
    }

    private void countError() {
        if (errors != null) {
            errors.increment();
        }
    }

    /**
     * Two buckets, not one with two bandwidths: a bucket carrying both would charge a request and a
     * token on the same {@code tryConsume}, and a request costs one request and N tokens.
     */
    private static final class Entry {

        private final TimeMeter clock;
        private volatile int requestLimit;
        private volatile int tokenLimit;
        private final Bucket requests;
        private final Bucket tokens;
        private volatile long lastSeenMillis;

        /** True while usage settled after a response has taken the token bucket below zero. */
        private boolean inTokenDeficit() {
            return tokens.getAvailableTokens() < 0;
        }

        private Entry(int requestLimit, int tokenLimit, TimeMeter clock) {
            this(requestLimit, tokenLimit, requestLimit, tokenLimit, clock);
        }

        private Entry(int requestLimit, int tokenLimit, int requestBurst, int tokenBurst, TimeMeter clock) {
            this.clock = clock;
            this.requestLimit = requestLimit;
            this.tokenLimit = tokenLimit;
            this.requests = bucket(Math.max(1, requestBurst), Math.max(1, requestLimit), clock);
            this.tokens = bucket(Math.max(1, tokenBurst), Math.max(1, tokenLimit), clock);
        }

        /** Capacity is how big a burst may be; refill is the sustained rate. */
        private static Bucket bucket(long capacity, long rate, TimeMeter clock) {
            return Bucket.builder()
                    .addLimit(limit -> limit.capacity(capacity).refillGreedy(rate, WINDOW))
                    .withCustomTimePrecision(clock)
                    .build();
        }

        /**
         * Re-rates the buckets in place, keeping what has been spent. The caller computes the burst
         * by the same rule it uses when creating an entry: an override replaces the rate and the
         * burst scales with it, so a workspace given a tighter limit cannot keep the wider
         * install-level burst, and a key back on the install default gets the configured burst.
         */
        private synchronized void reconfigureIfLimitsChanged(int newRequestLimit, int newTokenLimit,
                                                             int newRequestBurst, int newTokenBurst) {
            if (newRequestLimit != requestLimit) {
                requests.replaceConfiguration(
                        configFor(Math.max(1, newRequestBurst), Math.max(1, newRequestLimit)),
                        TokensInheritanceStrategy.ADDITIVE);
                requestLimit = newRequestLimit;
            }
            if (newTokenLimit != tokenLimit) {
                tokens.replaceConfiguration(
                        configFor(Math.max(1, newTokenBurst), Math.max(1, newTokenLimit)),
                        TokensInheritanceStrategy.ADDITIVE);
                tokenLimit = newTokenLimit;
            }
        }

        private static BucketConfiguration configFor(long capacity, long rate) {
            return BucketConfiguration.builder()
                    .addLimit(limit -> limit.capacity(capacity).refillGreedy(rate, WINDOW))
                    .build();
        }
    }
}