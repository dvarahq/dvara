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
package com.dvarahq.policy.guardrail;

import com.dvarahq.core.guardrail.GuardrailAction;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("dvara.llm-gateway.guardrail")
public class GuardrailProperties {

    private boolean enabled = true;

    private GuardrailAction defaultAction = GuardrailAction.LOG;

    private boolean scanResponses = true;

    private double riskScoreThreshold = 0.7;

    /**
     * What to do about a detection an ML classifier produced: {@code BLOCK}, {@code FLAG},
     * {@code LOG}, or {@code INHERIT} to use the same action as every other detection.
     *
     * <p>Default {@code FLAG}. A classifier is statistical and flags a meaningful share of benign
     * prompts, so a workspace set to BLOCK for its regex patterns, which match exact phrases, should
     * not thereby refuse on a probability. The setting lives here rather than beside a classifier's
     * own configuration because what the pipeline does with a verdict is pipeline policy and applies
     * to any classifier, including one an application registers against {@code MlClassifierHook}.</p>
     *
     * <p>An unreadable value inherits and warns, so a typo cannot quietly turn a refusal into a log
     * line or the reverse. Per-workspace override:
     * {@code Workspace.metadata["guardrail.classifier-action"]}.</p>
     */
    private String classifierAction = "FLAG";

    /**
     * Maximum estimated input tokens per request (OWASP LLM10). {@code 0} = disabled, which is the
     * default.
     *
     * <p>This check runs in the guardrail filter (order 500) and the context-window governor runs at
     * 900, so whichever bound is smaller is the only one that can ever fire. A fixed default here
     * would sit below every provider's window and make the governor's pruning strategy and threshold
     * settings unreachable, so the default is zero and the serving provider's own context window is
     * the bound. {@link #maxMessagesPerRequest} and {@link #maxMessageLength} still apply, and the
     * application may add per-workspace token or budget caps. Set a figure here to bound input
     * below what the model allows.</p>
     */
    private int maxInputTokens = 0;

    /** Maximum number of messages per request. 0 = disabled. */
    private int maxMessagesPerRequest = 100;

    /** Maximum character length per individual message. 0 = disabled. */
    private int maxMessageLength = 50_000;

    /** Default max_tokens to apply to requests that don't specify one. 0 = disabled. */
    private int defaultMaxResponseTokens = 4096;

    /** Enable guardrail scanning on streaming responses. */
    private boolean scanStreamingResponses = true;

    /** Number of characters to buffer before triggering a scan during streaming. */
    private int streamingScanWindowSize = 256;

    /** Overlap margin (chars) retained between scan windows to catch boundary-spanning patterns. */
    private int streamingOverlapMargin = 64;
}
