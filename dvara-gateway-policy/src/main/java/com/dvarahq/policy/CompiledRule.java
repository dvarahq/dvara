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
package com.dvarahq.policy;

import com.dvarahq.policy.rule.PolicyRule;

/**
 * One rule after compilation. {@code message} is the text the rule reports when it fires: the
 * rule's {@code warn_message} for WARN_AGENT, its {@code deny_message} for DENY, or null when the
 * author wrote neither, in which case the engine supplies a default naming the rule.
 */
public record CompiledRule(String ruleId, int priority, String action, String message, PolicyRule matcher) {
}