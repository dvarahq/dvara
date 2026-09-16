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

/**
 * A single guardrail detection within scanned text.
 *
 * <p>{@code fromClassifier} is set by the detector that called the hook, so the audit and metric
 * paths do not have to recognise classifiers by rule-id prefix.</p>
 *
 * @param category       detection category (INJECTION, JAILBREAK, etc.)
 * @param label          human-readable label for the specific pattern matched
 * @param matchedText    the text fragment that triggered the detection
 * @param riskScore      risk score from 0.0 (low) to 1.0 (critical)
 * @param confidence     confidence of the detection from 0.0 to 1.0
 * @param ruleId         identifier of the rule/pattern that triggered the detection
 * @param fromClassifier whether an {@link MlClassifierHook} produced this detection, rather than a
 *                       pattern, a dictionary or a rule
 */
public record GuardrailDetection(
        GuardrailCategory category,
        String label,
        String matchedText,
        double riskScore,
        double confidence,
        String ruleId,
        boolean fromClassifier) {

    /** A detection from anything other than the ML classifier; {@code fromClassifier} is {@code false}. */
    public GuardrailDetection(GuardrailCategory category, String label, String matchedText,
                              double riskScore, double confidence, String ruleId) {
        this(category, label, matchedText, riskScore, confidence, ruleId, false);
    }

    /** The same detection, marked as the ML classifier's. */
    public GuardrailDetection asClassifierDetection() {
        return new GuardrailDetection(category, label, matchedText, riskScore, confidence, ruleId, true);
    }
}