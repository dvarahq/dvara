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
package com.dvarahq.core.workspace.settings;

/**
 * One entry of a workspace setting that is a nested map inside the JSONB map, stored typed so a
 * value of the wrong shape is refused at write time rather than cast and hoped for on the request
 * path.
 *
 * <p>Distinct from {@link ContentRule}, which is an ordered <em>list</em> of values. These are
 * keyed by a label, and a label repeats only for {@link Kind#DENY_INTENT}, where one intent carries
 * several phrases.
 */
public record WorkspaceSettingEntry(String workspaceId, Kind kind, String label, String value) {

    public enum Kind {
        /** label → regex, the content filter's per-workspace denylist. */
        CONTENT_DENYLIST,
        /** label → regex, extra prompt-injection patterns. */
        INJECTION_PATTERN,
        /**
         * category → action override. In the metadata map these are flat keys shaped
         * {@code guardrail.content.<category>.action}, which nothing can enumerate without knowing
         * every category name in advance.
         */
        CATEGORY_ACTION,
        /** intent → one exemplar phrase; several rows per intent. */
        DENY_INTENT
    }
}