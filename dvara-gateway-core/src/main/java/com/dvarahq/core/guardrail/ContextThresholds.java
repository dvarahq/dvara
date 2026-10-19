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
 * The share of a model's context window at which a request is warned about and at which it is refused or pruned,
 * in percent.
 *
 * @param warningPct a request at or above this share is let through with a warning
 * @param hardPct    a request at or above this share is pruned, or refused when the workspace prunes nothing
 */
public record ContextThresholds(int warningPct, int hardPct) {

    /** What applies when a workspace sets nothing: a warning at 70 %, the hard limit at 90 %. */
    public static final ContextThresholds DEFAULTS = new ContextThresholds(70, 90);
}
