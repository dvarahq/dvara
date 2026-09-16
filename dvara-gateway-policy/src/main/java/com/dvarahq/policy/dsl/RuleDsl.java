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
package com.dvarahq.policy.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class RuleDsl {
    private String id;
    private String description;
    private int priority = 100;
    private ConditionDsl conditions;
    private String action;

    @JsonProperty("deny_message")
    private String denyMessage;

    @JsonProperty("warn_message")
    private String warnMessage;

    /**
     * Optional CEL (Common Expression Language) expression, mutually exclusive with
     * {@link #conditions}. When set, the rule matches when the expression evaluates to
     * {@code true} against the evaluation context exposed by whichever compiler handles
     * expressions. Bad syntax or unknown context fields surface at policy compile time as
     * {@code PolicyCompilationException}, reported as HTTP 400 {@code INVALID_POLICY_DSL} by the
     * same admin-API validation path as the rest of the DSL.
     */
    private String expression;
}