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
 * A per-workspace list the content filter reads: competitor names to flag, or topics to refuse.
 *
 * <p>These two kinds are the ones {@code ContentFilterDetector} consults. A kind nothing in this
 * build reads does not belong here: a workspace could configure it and nothing would act on it.
 */
public record ContentRule(String workspaceId, Kind kind, String value) {

    public ContentRule {
        value = value == null ? null : value.trim();
    }

    public enum Kind {

        /** Names to flag when a response mentions one. */
        COMPETITOR_KEYWORD,

        /** Subjects a workspace will not discuss. */
        TOPIC_RESTRICTION;

        /** The key this kind is read from in a workspace's metadata map. */
        public String metadataKey() {
            return switch (this) {
                case COMPETITOR_KEYWORD -> "guardrail.content.competitor.keywords";
                case TOPIC_RESTRICTION -> "guardrail.content.topic-restrictions";
            };
        }
    }
}
