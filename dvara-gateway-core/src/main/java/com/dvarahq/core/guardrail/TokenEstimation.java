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
package com.dvarahq.core.guardrail;

import com.dvarahq.core.model.ToolDefinition;
import com.dvarahq.core.util.JsonMapper;

import java.util.List;

/**
 * Renders the non-text parts of a request into the text an estimator should count.
 *
 * <p>Shared by every {@link TokenEstimator}, because two implementations that disagree about whether
 * tool definitions count are two different answers to "how big is this request" — and the one that
 * counts less is the one that lets an over-long request through.</p>
 *
 * <p>What the provider receives is JSON, so JSON is what gets counted. This is an estimate either
 * way: a provider's own serialization differs in whitespace and key order, which moves the count by a
 * few percent, not by a factor.</p>
 */
public final class TokenEstimation {

    private TokenEstimation() {
    }

    /**
     * The tool definitions as the text they cost, or {@code ""} when there are none.
     *
     * <p>These are sent on <b>every</b> turn of a function-calling conversation, and a handful of
     * JSON schemas is routinely larger than the messages they accompany, so leaving them out would
     * under-count the requests most likely to overflow a context window.</p>
     */
    public static String renderTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return "";
        }
        return render(tools);
    }

    /**
     * An arbitrary value as the text it costs.
     *
     * <p>A tool call's arguments arrive as a JSON string and are counted directly; a tool definition
     * arrives as a parsed object and has to be rendered back. Falls back
     * to {@link String#valueOf} if the value will not serialize — an estimate slightly off is worth
     * more than an exception on the request path, and this is called while governing a live call.</p>
     */
    public static String render(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        try {
            return JsonMapper.instance().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }
}
