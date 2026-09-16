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

import com.dvarahq.core.policy.PolicyDecision;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Mutable context object passed through the governance filter chain, carrying request-scoped state.
 */
@Data
@Builder
public class FilterContext {

    private String workspaceId;
    /**
     * The opaque id of the authenticated API key ({@code ApiKey.id}), or null for an anonymous
     * request. Never the bearer token: this is what budget caps are keyed on and what audit
     * payloads may carry.
     */
    private String apiKey;
    private String traceId;
    private PolicyDecision policyDecision;
    private String priorityTier;
    private String downgradedModel;
    private String resolvedModel;

    /**
     * ID of the route the routing engine matched on the request, or {@code null}
     * if no route matched and the request fell through to the default
     * model-prefix matcher. Set by the controller after dispatch completes,
     * so post-dispatch filters can scope behaviour to the selected route.
     */
    private String selectedRouteId;

    /** Arbitrary attributes for cross-filter communication. */
    @Builder.Default
    private Map<String, Object> attributes = new java.util.LinkedHashMap<>();

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * Prefix marking an attribute whose value is copied onto the HTTP response as a header.
     *
     * <p>This is how a filter from another module contributes a response header without this build
     * knowing what the header means. The name after the prefix is the header name, and the value is
     * its value. A filter setting one is asserting the header is safe to send; nothing here
     * validates it.
     */
    public static final String RESPONSE_HEADER_PREFIX = "response-header.";

    /** Sets a header for the controller to apply to whatever response it is building. */
    public void setResponseHeader(String name, String value) {
        attributes.put(RESPONSE_HEADER_PREFIX + name, value);
    }

    /** The headers filters asked for, in insertion order. Empty when none did. */
    public Map<String, String> responseHeaders() {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        attributes.forEach((key, value) -> {
            if (key.startsWith(RESPONSE_HEADER_PREFIX) && value != null) {
                headers.put(key.substring(RESPONSE_HEADER_PREFIX.length()), String.valueOf(value));
            }
        });
        return headers;
    }
}