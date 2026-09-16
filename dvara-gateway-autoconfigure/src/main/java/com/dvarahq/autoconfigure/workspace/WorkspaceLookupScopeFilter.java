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
package com.dvarahq.autoconfigure.workspace;

import com.dvarahq.core.workspace.WorkspaceLookupScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Brackets each request with a {@link WorkspaceLookupScope}.
 *
 * <p>Many components read {@code Workspace.metadata} independently on the request path. The memo
 * that collapses those reads into one needs a lifetime, and a request is the only one that is both
 * long enough to be useful and short enough to have no staleness.
 *
 * <p>Ordered ahead of everything: the first workspace read happens in authentication, before any
 * filter that governs the request, so a scope opened later would miss the reads it exists to catch.
 *
 * <p>The {@code finally} removes the ThreadLocal on every path, including a thrown request.
 * Virtual threads make a leak unlikely anyway, but that is a property of a configuration flag, and
 * correctness should not rest on one.
 */
public class WorkspaceLookupScopeFilter extends OncePerRequestFilter implements Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        WorkspaceLookupScope.open();
        try {
            chain.doFilter(request, response);
        } finally {
            WorkspaceLookupScope.close();
        }
    }

    /**
     * Runs on async dispatches too.
     *
     * <p>{@code OncePerRequestFilter} skips them by default, and a streaming response is dispatched
     * asynchronously — so without this the tail of every SSE request would run with no scope. That is
     * not incorrect (no scope is a pass-through) but it is exactly the long-lived request where the
     * repeated reads cost most.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }
}