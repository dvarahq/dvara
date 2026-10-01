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

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a caller of the Anthropic Messages API sent that has no place in the gateway's own request, carried
 * so that an Anthropic provider can send it on unchanged.
 *
 * <p>Only an Anthropic provider reads it. When {@link #body()} is set, that provider sends the caller's body
 * on as it came, with only the governed edits (see {@link AnthropicMessagesBody}). Any other provider is
 * sent the gateway's translation. Extended thinking changes the answer, so a request that asks for it is
 * refused on another provider ({@link #anthropicOnly()}). What only tunes how Anthropic serves the call — a
 * top-level field the gateway does not model, a block type it does not read, {@code cache_control}, the beta
 * header — is left out for another provider ({@link #ignoredByOthers()}).</p>
 *
 * @param thinking the request's {@code thinking} object, or null
 * @param fields   top-level request fields the gateway does not model, by name; never null
 * @param beta     the {@code anthropic-beta} header, or null
 * @param body     the request body as it came, or null when the request was not a Messages API body
 */
public record AnthropicPassthrough(Map<String, Object> thinking, Map<String, Object> fields, String beta,
                                   @JsonIgnore AnthropicMessagesBody body) {

    public AnthropicPassthrough {
        fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
        beta = beta == null || beta.isBlank() ? null : beta.trim();
    }

    public AnthropicPassthrough(Map<String, Object> thinking, Map<String, Object> fields, String beta) {
        this(thinking, fields, beta, null);
    }

    /** Whether the request turns thinking on. {@code {"type": "disabled"}} does not. */
    public boolean asksForThinking() {
        return thinking != null && !"disabled".equals(String.valueOf(thinking.get("type")));
    }

    /** What in this request only an Anthropic provider can serve, in plain words; empty when any provider can. */
    public List<String> anthropicOnly() {
        return asksForThinking() ? List.of("extended thinking") : List.of();
    }

    /**
     * What another provider is not given, by name: each top-level field the gateway does not model, and what
     * the body's view leaves out ({@link AnthropicMessagesBody#ignoredByOthers()}).
     */
    public List<String> ignoredByOthers() {
        List<String> out = new ArrayList<>(fields.keySet());
        if (body != null) {
            out.addAll(body.ignoredByOthers());
        }
        return out;
    }

    /** Whether there is anything here at all. */
    public boolean carriesNothing() {
        return thinking == null && fields.isEmpty() && beta == null && body == null;
    }
}
