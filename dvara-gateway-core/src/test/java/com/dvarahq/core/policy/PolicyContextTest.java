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
package com.dvarahq.core.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The component is specified as the key's opaque id. This is the second line of defence for a
 * caller that passes something else.
 */
class PolicyContextTest {

    @Test
    @DisplayName("toString never prints the apiKey component, whatever a caller put in it")
    void toStringDoesNotPrintTheApiKey() {
        var context = new PolicyContext("t1", "u1", "dvara_sk_live_supersecretvalue", Map.of("region", "eu"));

        assertThat(context.toString())
                .doesNotContain("dvara_sk_live_supersecretvalue")
                .contains("<redacted>");
    }

    @Test
    @DisplayName("an absent key is still visible as absent, rather than blanked like a present one")
    void toStringDistinguishesAbsentFromPresent() {
        assertThat(PolicyContext.empty().toString()).contains("apiKey=null");
    }

    @Test
    @DisplayName("the rest of the context is still readable, which is what toString is for")
    void toStringKeepsEverythingElse() {
        var context = new PolicyContext("t1", "u1", "id-1", Map.of("region", "eu"));

        assertThat(context.toString())
                .contains("workspaceId=t1")
                .contains("userId=u1")
                .contains("region=eu");
    }

    @Test
    @DisplayName("the component itself is unchanged — it is printing that is guarded, not reading")
    void theComponentIsStillReadable() {
        assertThat(new PolicyContext("t1", null, "id-1", Map.of()).apiKey()).isEqualTo("id-1");
    }
}
