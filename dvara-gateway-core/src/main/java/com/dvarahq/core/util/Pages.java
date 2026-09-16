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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * One page of an in-memory list, newest first.
 *
 * <p>The repository interfaces' {@code findPage} defaults call this, so every in-memory or
 * file-backed store pages the same way. Two things matter here beyond the order:
 *
 * <ul>
 *   <li><b>Paging an unordered source is not just wrong-order.</b> If the underlying order shifts
 *       between two requests, a row can be skipped or served twice, and the reader sees neither.</li>
 *   <li><b>{@code subList} is a view.</b> A store that returns its own list would hand the caller a
 *       window onto it, so the result is a copy.</li>
 * </ul>
 */
public final class Pages {

    private Pages() {}

    /**
     * The {@code limit} items starting at {@code offset}, newest first by {@code timestamp}.
     *
     * <p>A null timestamp sorts <b>last</b> rather than throwing: a store that does not stamp one
     * should not break pagination, and an undated row is the least likely to be what "newest" means.
     * Offset and limit are clamped, so an offset past the end is an empty page rather than an
     * exception. The result is a copy.
     *
     * <p>The end index is computed in {@code long}, so {@code limit = Integer.MAX_VALUE} with a
     * non-zero offset does not overflow.
     */
    public static <T> List<T> newestFirst(List<T> all, Function<T, Instant> timestamp,
                                          int limit, int offset) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        List<T> ordered = new ArrayList<>(all);
        ordered.sort(Comparator.comparing(timestamp, Comparator.nullsLast(Comparator.reverseOrder())));
        int start = Math.min(Math.max(0, offset), ordered.size());
        long requestedEnd = (long) start + Math.max(0, limit);
        int end = (int) Math.min(requestedEnd, ordered.size());
        return List.copyOf(ordered.subList(start, end));
    }
}
