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
package com.dvarahq.core.filter;

import com.dvarahq.core.model.ChatRequest;
import com.dvarahq.core.model.ChatResponse;

import java.util.Comparator;
import java.util.List;

/**
 * Ordered pipeline of {@link ChatFilter} instances. Executes pre-dispatch
 * filters in order, then post-dispatch filters in reverse order.
 */
public class RequestPipeline {

    private final List<ChatFilter> filters;

    public RequestPipeline(List<ChatFilter> filters) {
        this.filters = filters.stream()
                .sorted(Comparator.comparingInt(ChatFilter::order))
                .toList();
    }

    /**
     * Execute all pre-dispatch filters in order.
     *
     * @return the final (possibly modified) request
     */
    public ChatRequest preDispatch(ChatRequest request, FilterContext ctx) {
        ChatRequest current = request;
        for (ChatFilter filter : filters) {
            current = filter.preDispatch(current, ctx);
        }
        return current;
    }

    /**
     * Execute all post-dispatch filters in reverse order.
     *
     * @return the final (possibly modified) response
     */
    public ChatResponse postDispatch(ChatRequest request, ChatResponse response, FilterContext ctx) {
        ChatResponse current = response;
        for (int i = filters.size() - 1; i >= 0; i--) {
            current = filters.get(i).postDispatch(request, current, ctx);
        }
        return current;
    }

    public List<ChatFilter> getFilters() {
        return filters;
    }
}