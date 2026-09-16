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
package com.dvarahq.server.v1;

import com.dvarahq.core.filter.FilterContext;
import com.dvarahq.core.policy.PolicyDecision;
import org.springframework.http.ResponseEntity;

/**
 * Applies the response headers the filter pipeline asked for. The controllers do not know what
 * any of these headers mean: a filter names a header and a value, and this copies them onto
 * whichever response is being built. A budget filter, if one is registered, supplies its headers
 * the same way.
 *
 * <p>{@code X-Budget-Warning} is the exception: despite its name it is not a budget header, but is
 * set when the policy decision carries warnings.
 */
final class ContextResponseHeaders {

    static final String X_BUDGET_WARNING = "X-Budget-Warning";

    private ContextResponseHeaders() {
    }

    /** For a buffered response. */
    static void apply(ResponseEntity.BodyBuilder builder, FilterContext ctx, PolicyDecision decision) {
        ctx.responseHeaders().forEach(builder::header);
        if (decision != null && decision.hasWarnings()) {
            builder.header(X_BUDGET_WARNING, "true");
        }
    }

    /** For a stream, where the headers have to be on the servlet response before the first event. */
    static void apply(jakarta.servlet.http.HttpServletResponse response, FilterContext ctx,
                      PolicyDecision decision) {
        ctx.responseHeaders().forEach(response::setHeader);
        if (decision != null && decision.hasWarnings()) {
            response.setHeader(X_BUDGET_WARNING, "true");
        }
    }
}
