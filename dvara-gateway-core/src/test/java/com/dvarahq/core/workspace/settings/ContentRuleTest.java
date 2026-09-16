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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContentRuleTest {

    @Test
    void itCarriesOnlyTheKindsThisBuildActsOn() {
        // The rule kinds are exactly the two the content filter reads, so a workspace cannot
        // configure a kind nothing acts on.
        assertThat(ContentRule.Kind.values())
                .containsExactly(ContentRule.Kind.COMPETITOR_KEYWORD, ContentRule.Kind.TOPIC_RESTRICTION);
    }

    @Test
    void theMetadataKeysAreUnchangedFromTheWiderType() {
        // These are the two lists an operator writes into a configuration file, so the literals are
        // the whole assertion: what must not change is the string an operator typed.
        assertThat(ContentRule.Kind.COMPETITOR_KEYWORD.metadataKey())
                .isEqualTo("guardrail.content.competitor.keywords");
        assertThat(ContentRule.Kind.TOPIC_RESTRICTION.metadataKey())
                .isEqualTo("guardrail.content.topic-restrictions");
    }

    @Test
    void aValueIsTrimmed_andNullSurvives() {
        assertThat(new ContentRule("t1", ContentRule.Kind.TOPIC_RESTRICTION, "  politics  ").value())
                .isEqualTo("politics");
        assertThat(new ContentRule("t1", ContentRule.Kind.TOPIC_RESTRICTION, null).value()).isNull();
    }
}
