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
package com.dvarahq.core.cache;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

import java.util.Optional;

/**
 * Caches chat responses keyed by request.
 *
 * <p>The request path looks this up and goes straight to the provider when no bean is registered.
 * The autoconfigure module registers an in-memory implementation when configured; an application
 * may register its own.
 */
public interface ResponseCache {

    Optional<ChatResponse> get(ChatRequest request);

    void put(ChatRequest request, ChatResponse response);


    default void clear() {
        // no-op by default
    }

    /**
     * Removes one entry, so a response the current output policy refuses stops being served.
     *
     * <p>A cached response is run through the output filters on every hit; without eviction a
     * refused response would be re-scanned and re-refused on every request. Default no-op for an
     * implementation that cannot address a single entry: the response is still refused on every
     * hit, just not evicted.</p>
     */
    default void evict(ChatRequest request) {
        // no-op by default
    }
}