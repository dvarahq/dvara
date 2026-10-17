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
package com.dvarahq.core.call;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalCallTest {

    @Test
    void namesTheRunningCallOnlyWhileItsScopeIsOpen() {
        InternalCall call = new InternalCall("eval", "ws-1", "internal-eval", "trace-1");
        assertThat(InternalCall.current()).isNull();
        try (InternalCall.Scope ignored = call.open()) {
            assertThat(InternalCall.current()).isEqualTo(call);
        }
        assertThat(InternalCall.current()).isNull();
    }

    @Test
    void closingAnInnerScopeRestoresTheOuterOne() {
        InternalCall outer = new InternalCall("routing", "ws-1", "internal-routing", null);
        InternalCall inner = new InternalCall("eval", "ws-1", "internal-eval", null);
        try (InternalCall.Scope o = outer.open()) {
            try (InternalCall.Scope i = inner.open()) {
                assertThat(InternalCall.current()).isEqualTo(inner);
            }
            assertThat(InternalCall.current()).isEqualTo(outer);
        }
        assertThat(InternalCall.current()).isNull();
    }

    @Test
    void needsAKindAWorkspaceAndAKey() {
        assertThatThrownBy(() -> new InternalCall(" ", "ws-1", "k", null)).hasMessageContaining("kind");
        assertThatThrownBy(() -> new InternalCall("eval", null, "k", null)).hasMessageContaining("workspaceId");
        assertThatThrownBy(() -> new InternalCall("eval", "ws-1", "", null)).hasMessageContaining("apiKeyId");
    }
}
