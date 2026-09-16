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
package com.dvarahq.core.policy;

/**
 * A non-blocking warning produced by policy evaluation (e.g. WARN_AGENT action).
 *
 * @param type      warning type (e.g. "WARN_AGENT")
 * @param message   human-readable warning message
 * @param policyId  the policy that produced this warning
 * @param ruleId    the rule within the policy
 */
public record PolicyWarning(String type, String message, String policyId, String ruleId) {
}