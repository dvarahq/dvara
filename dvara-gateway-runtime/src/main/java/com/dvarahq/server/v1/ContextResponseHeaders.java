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
 * <p>Two come from the policy decision instead. {@code X-Policy-Warning: true} is set when it carries any
 * warning. {@code X-Budget-Warning: true} is meant for a warning from a rule that tests budget use, but
 * through the 1.9 releases it is still set for every warning, as before, so a client that reads it keeps
 * working. From 1.10.0 it is set only for a budget warning; read {@code X-Policy-Warning} for any warning.
 */
final class ContextResponseHeaders {

    static final String X_BUDGET_WARNING = "X-Budget-Warning";
    static final String X_POLICY_WARNING = "X-Policy-Warning";

    /** Through the 1.9 releases {@code X-Budget-Warning} keeps its old meaning, any warning. False from 1.10.0. */
    static final boolean BUDGET_HEADER_ON_EVERY_WARNING = true;

    private ContextResponseHeaders() {
    }

    /** For a buffered response. */
    static void apply(ResponseEntity.BodyBuilder builder, FilterContext ctx, PolicyDecision decision) {
        ctx.responseHeaders().forEach(builder::header);
        if (decision != null && decision.hasWarnings()) {
            builder.header(X_POLICY_WARNING, "true");
            if (BUDGET_HEADER_ON_EVERY_WARNING || hasBudgetWarning(decision)) {
                builder.header(X_BUDGET_WARNING, "true");
            }
        }
    }

    /** For a stream, where the headers have to be on the servlet response before the first event. */
    static void apply(jakarta.servlet.http.HttpServletResponse response, FilterContext ctx,
                      PolicyDecision decision) {
        ctx.responseHeaders().forEach(response::setHeader);
        if (decision != null && decision.hasWarnings()) {
            response.setHeader(X_POLICY_WARNING, "true");
            if (BUDGET_HEADER_ON_EVERY_WARNING || hasBudgetWarning(decision)) {
                response.setHeader(X_BUDGET_WARNING, "true");
            }
        }
    }

    private static boolean hasBudgetWarning(PolicyDecision decision) {
        return decision.warnings().stream().anyMatch(com.dvarahq.core.policy.PolicyWarning::isBudget);
    }
}
