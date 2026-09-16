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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The page slice shared by the repository interfaces' default paging methods.
 *
 * <p>A page is sorted newest first and is a copy, not a {@code subList} view of the list the store
 * handed back.
 */
class PagesTest {

    private record Row(String id, Instant at) {}

    private static Row row(String id, String iso) {
        return new Row(id, iso == null ? null : Instant.parse(iso));
    }

    private static List<Row> unordered() {
        // deliberately not in time order — the order a file or an in-memory map hands back
        return List.of(
                row("b", "2026-02-01T00:00:00Z"),
                row("d", "2026-04-01T00:00:00Z"),
                row("a", "2026-01-01T00:00:00Z"),
                row("c", "2026-03-01T00:00:00Z"));
    }

    @Test
    void pagesAreNewestFirstEvenWhenTheSourceIsNot() {
        List<Row> page = Pages.newestFirst(unordered(), Row::at, 2, 0);

        assertThat(page).extracting(Row::id).containsExactly("d", "c");
    }

    @Test
    void theSecondPageContinuesWhereTheFirstStopped_withNothingSkippedOrRepeated() {
        // The reason order matters for a PAGE and not only for a list: without it the two pages are
        // slices of an arbitrary sequence, and a shift between the calls drops or duplicates a row.
        List<Row> first = Pages.newestFirst(unordered(), Row::at, 2, 0);
        List<Row> second = Pages.newestFirst(unordered(), Row::at, 2, 2);

        assertThat(first).extracting(Row::id).containsExactly("d", "c");
        assertThat(second).extracting(Row::id).containsExactly("b", "a");
        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    @Test
    void anUndatedRowSortsLastRatherThanThrowing() {
        // A store that does not stamp a timestamp should not break pagination, and an undated row is
        // the least likely thing "newest" means.
        List<Row> rows = new ArrayList<>(unordered());
        rows.add(row("undated", null));

        List<Row> all = Pages.newestFirst(rows, Row::at, 10, 0);

        assertThat(all).extracting(Row::id).containsExactly("d", "c", "b", "a", "undated");
    }

    @Test
    void theResultIsACopy_notAWindowOntoTheStoresOwnList() {
        // subList is a view: a store returning its own list handed the caller a page that changes
        // underneath them, and a structural change to the source throws on iteration.
        List<Row> source = new ArrayList<>(unordered());
        List<Row> page = Pages.newestFirst(source, Row::at, 2, 0);

        source.clear();

        assertThat(page).hasSize(2);
        assertThatThrownBy(() -> page.add(row("x", "2026-05-01T00:00:00Z")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void anOffsetPastTheEndIsAnEmptyPageRatherThanAnException() {
        assertThat(Pages.newestFirst(unordered(), Row::at, 10, 99)).isEmpty();
    }

    @Test
    void negativeBoundsAreClamped() {
        assertThat(Pages.newestFirst(unordered(), Row::at, -5, -5)).isEmpty();
        assertThat(Pages.newestFirst(unordered(), Row::at, 1, -5)).extracting(Row::id).containsExactly("d");
    }

    @Test
    void anEmptyOrNullSourceIsAnEmptyPage() {
        assertThat(Pages.newestFirst(List.of(), Row::at, 5, 0)).isEmpty();
        assertThat(Pages.newestFirst(null, Row::at, 5, 0)).isEmpty();
    }

    /**
     * A large limit with a non-zero offset is a page, not an exception.
     *
     * <p>If {@code start + limit} were computed in {@code int},
     * {@code newestFirst(list, ts, Integer.MAX_VALUE, 1)} would overflow to a negative end index.
     * A caller paging in hundreds from the first row would never meet it, but this is a default on
     * five published repository interfaces, so any caller may.
     */
    @Test
    void aHugeLimitPastAnOffsetIsAPageRatherThanAnOverflow() {
        assertThat(Pages.newestFirst(unordered(), Row::at, Integer.MAX_VALUE, 1))
                .extracting(Row::id)
                .containsExactly("c", "b", "a");
        assertThat(Pages.newestFirst(unordered(), Row::at, Integer.MAX_VALUE, 0))
                .hasSize(4);
        assertThat(Pages.newestFirst(unordered(), Row::at, Integer.MAX_VALUE, Integer.MAX_VALUE))
                .as("an offset past the end is an empty page, whatever the limit")
                .isEmpty();
    }
}
