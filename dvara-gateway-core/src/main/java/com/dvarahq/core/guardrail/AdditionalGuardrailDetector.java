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
 * A detector contributed to the guardrail composite from outside the module that assembles it.
 *
 * <p>A marker rather than a plain {@link GuardrailDetector}, for the same mechanical reason as
 * {@link com.dvarahq.core.pii.AdditionalPiiDetector}: the composite is itself registered as a
 * {@code GuardrailDetector}, so collecting {@code ObjectProvider<GuardrailDetector>} would collect
 * the thing being built. Naming the contribution separately breaks the circle.</p>
 *
 * <p>Additive only. The injection patterns and the content filters run regardless, so a build with
 * no contributions scans every request — it scans for <em>fewer</em> things, not for nothing.</p>
 */
public interface AdditionalGuardrailDetector extends GuardrailDetector {
}
