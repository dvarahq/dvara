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
package com.dvarahq.core.model;

import java.util.Map;

/**
 * What a caller of the Anthropic Messages API sent that has no place in the gateway's own request, carried
 * so that an Anthropic provider can send it on unchanged.
 *
 * <p>Only an Anthropic provider reads it. Extended thinking changes the answer, so a request that asks for
 * it is refused on any other provider (see {@link #asksForThinking()}). The other fields and the beta
 * header only tune how Anthropic serves the call, so another provider leaves them out.</p>
 *
 * @param thinking the request's {@code thinking} object, or null
 * @param fields   top-level request fields the gateway does not model, by name; never null
 * @param beta     the {@code anthropic-beta} header, or null
 */
public record AnthropicPassthrough(Map<String, Object> thinking, Map<String, Object> fields, String beta) {

    public AnthropicPassthrough {
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        beta = beta == null || beta.isBlank() ? null : beta.trim();
    }

    /** Whether the request turns thinking on. {@code {"type": "disabled"}} does not. */
    public boolean asksForThinking() {
        return thinking != null && !"disabled".equals(String.valueOf(thinking.get("type")));
    }

    /** Whether there is anything here at all. */
    public boolean carriesNothing() {
        return thinking == null && fields.isEmpty() && beta == null;
    }
}
