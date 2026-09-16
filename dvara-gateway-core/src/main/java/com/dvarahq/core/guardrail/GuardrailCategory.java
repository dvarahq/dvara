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
 * Categories of guardrail detections.
 *
 * <p>A category is what a workspace names to give one kind of hit its own action —
 * {@code guardrail.content.<category>.action} — and what the audit payload and the
 * {@code gateway_guardrail_blocked_total} counter are labelled with. So a category no detector
 * produces is a setting an operator can write and nothing can apply.
 */
public enum GuardrailCategory {
    INJECTION,
    JAILBREAK,
    PROFANITY,
    VIOLENCE,
    SEXUAL,
    COMPETITOR_MENTION,
    TOPIC_RESTRICTION,
    CONTENT_POLICY,

    /**
     * <b>No detector produces this.</b> Hallucination detection is a separate path end to end
     * ({@code GroundingDetectionFilter}, the {@code HALLUCINATION_DETECTED} error code, the
     * {@code grounding.action} setting and the {@code gateway_grounding_check_total} counter) and
     * never builds a {@code GuardrailDetection}. The name stays in the vocabulary because it is the
     * one an audit reader would expect. A workspace that sets
     * {@code guardrail.content.hallucination.action} is configuring something that cannot fire, and
     * {@code GuardrailScanService} warns about it.
     */
    HALLUCINATION,
    CUSTOM
}