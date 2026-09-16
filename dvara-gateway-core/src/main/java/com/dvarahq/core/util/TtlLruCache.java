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
package com.dvarahq.core.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.TimeUnit;

/**
 * Bounded cache with a per-entry TTL, backed by Caffeine.
 *
 * <p><b>The bound is not strict LRU, despite the name.</b> Caffeine admits and evicts by frequency
 * as well as recency (Window TinyLFU), and does its eviction asynchronously, so which key goes is
 * not predictable, and {@link #size} may briefly exceed the maximum until maintenance runs. Treat
 * the bound as a backstop and the TTL as the mechanism. Callers that need a specific entry gone call
 * {@link #remove}, which is exact and immediate.
 *
 * @param <V> cached value type
 */
public final class TtlLruCache<V> {

    private final Cache<String, V> cache;
    private final int defaultTtlSeconds;

    public TtlLruCache(int maxSize, int defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                // expireAfter rather than expireAfterWrite, because put(key, value, ttlSeconds) sets
                // a TTL per entry and a single write duration cannot express that. The duration is
                // fixed when the entry is written; a read does not extend it.
                .expireAfter(new Expiry<String, V>() {
                    @Override
                    public long expireAfterCreate(String key, V value, long currentTime) {
                        return perEntryNanos.get() != null
                                ? perEntryNanos.get()
                                : TimeUnit.SECONDS.toNanos(TtlLruCache.this.defaultTtlSeconds);
                    }

                    @Override
                    public long expireAfterUpdate(String key, V value, long currentTime,
                                                  long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(String key, V value, long currentTime,
                                                long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /**
     * The TTL for the write in progress on this thread, or null for the default.
     *
     * <p>{@link Expiry} is told the key and the value and nothing else, so a per-entry TTL has to
     * reach it some other way. A thread-local set around the one {@code put} call is the narrowest
     * channel available: it is read synchronously inside that call and cleared in a finally, so it
     * cannot leak into another entry or another thread.
     */
    private static final ThreadLocal<Long> perEntryNanos = new ThreadLocal<>();

    /** The cached value, or {@code null} if absent or expired. */
    public @Nullable V get(String key) {
        return cache.getIfPresent(key);
    }

    /** Stores a value with the default TTL. */
    public void put(String key, V value) {
        cache.put(key, value);
    }

    /** Stores a value with a custom TTL (e.g. a shorter one for failure entries). */
    public void put(String key, V value, int ttlSeconds) {
        perEntryNanos.set(TimeUnit.SECONDS.toNanos(ttlSeconds));
        try {
            cache.put(key, value);
        } finally {
            perEntryNanos.remove();
        }
    }

    /**
     * Drops an entry immediately, without waiting for its TTL.
     *
     * <p>For key erasure, where the TTL is a backstop rather than the mechanism: a destroyed DEK
     * must stop being usable on the pod that destroyed it at once. Expiry alone would leave a window
     * in which a workspace has been told their data is erased while this process can still read it.
     */
    public void remove(String key) {
        cache.invalidate(key);
    }

    /** Drops everything. Same reasoning as {@link #remove(String)}, for a whole-workspace erasure. */
    public void clear() {
        cache.invalidateAll();
    }

    /**
     * The number of live entries.
     *
     * <p>Runs pending maintenance first, so the answer reflects evictions and expiries that are due
     * rather than a count that catches up later. That costs a little on a hot path and this is not
     * one — it is read by tests and by the odd diagnostic.
     */
    public int size() {
        cache.cleanUp();
        return (int) cache.estimatedSize();
    }
}
