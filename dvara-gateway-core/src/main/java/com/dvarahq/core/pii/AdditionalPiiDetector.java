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
package com.dvarahq.core.pii;

/**
 * A detector layered <b>on top of</b> the always-present pattern-and-checksum one.
 *
 * <p>A marker rather than a plain {@link PiiDetector}, and the reason is mechanical: the composed
 * detector is itself registered as a {@code PiiDetector}, so a component collecting
 * {@code ObjectProvider<PiiDetector>} would collect the thing it is building. Naming the
 * contribution separately is what breaks that circle.</p>
 *
 * <p>Layers are additive and never subtractive. The regex detector runs regardless, so a build with
 * no contributions still detects and still redacts — it detects <em>less</em>, not nothing, which is
 * the property that makes this safe to vary by deployment. The composite deduplicates overlapping
 * spans, higher confidence winning, so two layers finding the same entity report it once.</p>
 */
public interface AdditionalPiiDetector extends PiiDetector {

    /**
     * Which configured layer this contribution is, so the startup warning about a missing layer
     * is accurate: an unrelated contribution must not silence a warning about a layer the operator
     * asked for and is not getting.
     *
     * <p>Matched case-insensitively against the layer names the properties use. The default is
     * blank, meaning "this contribution answers for no configured layer", so a detector that has not
     * declared itself does not suppress any warning.
     */
    default String providesLayer() {
        return "";
    }
}
