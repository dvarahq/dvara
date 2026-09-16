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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TtlLruCacheTest {

    @Test
    void get_returnsNullForMissingKey() {
        var cache = new TtlLruCache<String>(10, 300);
        assertThat(cache.get("missing")).isNull();
    }

    @Test
    void putAndGet_returnsCachedValue() {
        var cache = new TtlLruCache<String>(10, 300);
        cache.put("key", "value");
        assertThat(cache.get("key")).isEqualTo("value");
    }

    @Test
    void expiredEntry_returnsNull() {
        var cache = new TtlLruCache<String>(10, 0); // TTL 0 = expire immediately
        cache.put("key", "value");
        assertThat(cache.get("key")).isNull();
    }

    @Test
    void customTtl_respectsPerEntryTtl() {
        var cache = new TtlLruCache<String>(10, 300);
        cache.put("normal", "a");
        cache.put("short", "b", 0); // expires immediately

        assertThat(cache.get("normal")).isEqualTo("a");
        assertThat(cache.get("short")).isNull();
    }

    @Test
    void theBoundIsRespected_thoughNotByEvictingAnyParticularKey() {
        // Caffeine admits and evicts by frequency as well as recency, so which key goes when the
        // bound is exceeded is not a promise, and no caller relies on one. Every consumer treats the
        // bound as a backstop with the TTL as the mechanism, and a caller that needs a specific entry
        // gone calls remove(), which is exact. What is pinned is the bound itself.
        var cache = new TtlLruCache<String>(2, 300);
        cache.put("a", "1");
        cache.put("b", "2");
        cache.put("c", "3");

        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void removeIsExact_soErasureDoesNotDependOnEvictionOrder() {
        // The reason the bound above need not name a victim: anything that must be gone now says so.
        // PII key erasure is the case -- a destroyed DEK has to stop being readable on this pod at
        // once, not whenever the cache decides.
        var cache = new TtlLruCache<String>(10, 300);
        cache.put("keep", "1");
        cache.put("destroy", "2");

        cache.remove("destroy");

        assertThat(cache.get("destroy")).isNull();
        assertThat(cache.get("keep")).isEqualTo("1");
    }

    @Test
    void aPerEntryTtlAppliesToThatEntryOnly_andDoesNotLeakToTheNextWrite() {
        // The per-entry TTL reaches Caffeine's Expiry through a thread-local, because Expiry is told
        // only the key and the value. It is set around one put and cleared in a finally, so a
        // following write on the same thread must take the default rather than inherit the last one.
        var cache = new TtlLruCache<String>(10, 300);
        cache.put("short", "a", 0);
        cache.put("after", "b");

        assertThat(cache.get("short")).isNull();
        assertThat(cache.get("after"))
                .as("the previous write's TTL must not have carried over")
                .isEqualTo("b");
    }

    @Test
    void anExpiredEntryIsNotJustInvisible_itIsGone() {
        // An expired entry must be reclaimed, not merely hidden from get(): for a cached data key,
        // an entry kept until somebody asks for it is memory held past the lifetime the caller
        // stated.
        var cache = new TtlLruCache<String>(10, 0);
        cache.put("key", "value");

        assertThat(cache.size())
                .as("expired entries are reclaimed, not merely hidden from get()")
                .isZero();
    }

    @Test
    void size_reflectsEntryCount() {
        var cache = new TtlLruCache<String>(10, 300);
        assertThat(cache.size()).isZero();
        cache.put("a", "1");
        assertThat(cache.size()).isEqualTo(1);
        cache.put("b", "2");
        assertThat(cache.size()).isEqualTo(2);
    }
}