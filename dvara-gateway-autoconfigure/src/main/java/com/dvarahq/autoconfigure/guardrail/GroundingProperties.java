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
package com.dvarahq.autoconfigure.guardrail;

import com.dvarahq.core.guardrail.GuardrailAction;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The install-wide grounding posture, bound from {@code dvara.llm-gateway.guardrail.grounding.*}.
 *
 * <p>It means the same thing as the per-workspace {@code Workspace.metadata["grounding.enabled"]}
 * key. On a build with no grounding detector, a request carrying grounding sources while grounding
 * is on is refused with {@code GROUNDING_UNAVAILABLE} rather than served unchecked, which is the
 * designed behaviour for a control that is switched on and cannot run. Grounding is off by default.
 */
@Data
@ConfigurationProperties("dvara.llm-gateway.guardrail.grounding")
public class GroundingProperties {

    /** Whether responses are checked against the source documents the request carries. */
    private boolean enabled = false;

    /** What an ungrounded claim does: {@code BLOCK}, {@code FLAG} or {@code LOG}. */
    private GuardrailAction action = GuardrailAction.LOG;

    /** Maximum source documents considered per request; the excess is dropped. */
    private int maxSources = 50;

    /** Maximum characters per source document; oversized sources are dropped. */
    private int maxSourceLength = 10_000;
}
