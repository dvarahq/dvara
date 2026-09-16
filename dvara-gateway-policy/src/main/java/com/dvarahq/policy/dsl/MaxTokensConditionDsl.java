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
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The {@code max_tokens} condition: it matches a request that sets {@code max_tokens} above {@code limit}.
 *
 * <p>A request that does not set {@code max_tokens} does not match. That is deliberate: policies are evaluated
 * before the gateway applies its default response cap ({@code dvara.llm-gateway.guardrail.default-max-response-tokens},
 * which a workspace can override), so the number such a request will be sent with is not known here. To bound
 * requests that leave it unset, lower that default. A limit of zero or less is refused when the policy is compiled.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = false)
public class MaxTokensConditionDsl {
    private int limit;
}