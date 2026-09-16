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
package com.dvarahq.core.apikey;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyScopeTest {

    @Test
    void aKeyWithNoScopesIsUnrestricted() {
        assertThat(ApiKeyScope.permits(null, "/v1/chat/completions")).isTrue();
        assertThat(ApiKeyScope.permits(List.of(), "/v1/embeddings")).isTrue();
    }

    @Test
    void aScopedKeyMayCallOnlyTheFamiliesItNames() {
        List<String> completions = List.of("completions:write");
        assertThat(ApiKeyScope.permits(completions, "/v1/chat/completions")).isTrue();
        assertThat(ApiKeyScope.permits(completions, "/v1/completions")).isTrue();
        assertThat(ApiKeyScope.permits(completions, "/v1/responses")).isTrue();
        assertThat(ApiKeyScope.permits(completions, "/v1/embeddings")).as("embeddings are a family of their own").isFalse();
        assertThat(ApiKeyScope.permits(completions, "/v1/batches")).isFalse();
        assertThat(ApiKeyScope.permits(completions, "/v1/models")).isFalse();
        assertThat(ApiKeyScope.permits(List.of("embeddings:write"), "/v1/embeddings")).isTrue();
        assertThat(ApiKeyScope.permits(List.of("batches:write"), "/v1/batches/abc")).isTrue();
        assertThat(ApiKeyScope.permits(List.of("models:read"), "/v1/models")).isTrue();
    }

    @Test
    void aPathNoScopeGovernsIsNotRefused() {
        // A /v1 path outside every family, expressed as a path rather than a route: the rule is
        // about the scope vocabulary, not about which routes happen to be served.
        assertThat(ApiKeyScope.forPath("/v1/no-family-covers-this")).isEmpty();
        assertThat(ApiKeyScope.permits(List.of("completions:write"), "/v1/no-family-covers-this")).isTrue();
        assertThat(ApiKeyScope.permits(List.of("completions:write"), "/mcp/servers")).isTrue();
    }

    @Test
    void theBudgetEstimate_takesTheCompletionsScope_asAWholeFamily() {
        // Every /v1 path is scoped here, whether or not a route serves it, because the LLM plane's
        // request filter is the enforcement point for all of them.
        assertThat(ApiKeyScope.permits(List.of("completions:write"), "/v1/budget/estimate")).isTrue();
        assertThat(ApiKeyScope.permits(List.of("models:read"), "/v1/budget/estimate"))
                .as("a key narrowed to listing models has no business reading the workspace's "
                        + "remaining budget")
                .isFalse();
        assertThat(ApiKeyScope.permits(List.of("embeddings:write"), "/v1/budget/estimate")).isFalse();
        assertThat(ApiKeyScope.permits(List.of(), "/v1/budget/estimate"))
                .as("an unscoped key is unrestricted").isTrue();

        // The family, not the one path: a budget endpoint added later is governed by default.
        assertThat(ApiKeyScope.forPath("/v1/budget")).contains(ApiKeyScope.COMPLETIONS_WRITE);
        assertThat(ApiKeyScope.forPath("/v1/budget/anything-added-later"))
                .contains(ApiKeyScope.COMPLETIONS_WRITE);
        assertThat(ApiKeyScope.forPath("/v1/budgeting")).as("a prefix is not a family").isEmpty();
    }

    @Test
    void aKeyScopedToAnotherPlanesOperations_isRefusedOnEveryFamilyHere() {
        // The two vocabularies compose without either knowing the other's values. A plane scope
        // carries no value from this enum, so every family here refuses it — which is what makes
        // "a key may do only what its scopes name" true across planes with no shared enum.
        List<String> planeOnly = List.of("mcp:call", "a2a:send");
        assertThat(ApiKeyScope.permits(planeOnly, "/v1/chat/completions")).isFalse();
        assertThat(ApiKeyScope.permits(planeOnly, "/v1/embeddings")).isFalse();
        assertThat(ApiKeyScope.permits(planeOnly, "/v1/models")).isFalse();
        assertThat(ApiKeyScope.permits(planeOnly, "/v1/batches")).isFalse();
        assertThat(ApiKeyScope.permits(planeOnly, "/v1/budget/estimate")).isFalse();
    }

    @Test
    void anUnknownScopeGrantsNothing() {
        assertThat(ApiKeyScope.isKnown("completion:write")).isFalse();
        assertThat(ApiKeyScope.permits(List.of("completion:write"), "/v1/chat/completions"))
                .as("a typo narrows the key; it never widens it").isFalse();
    }

    @Test
    void batchFileUpload_belongsToTheBatchScope_andMatchingIsOnSegmentBoundaries() {
        assertThat(ApiKeyScope.permits(List.of("batches:write"), "/v1/files")).isTrue();
        assertThat(ApiKeyScope.permits(List.of("batches:write"), "/v1/files/file_123")).isTrue();
        assertThat(ApiKeyScope.permits(List.of("completions:write"), "/v1/files")).isFalse();
        assertThat(ApiKeyScope.permits(List.of("models:read"), "/v1/files")).isFalse();
        assertThat(ApiKeyScope.permits(List.of(), "/v1/files")).as("an unscoped key is unrestricted").isTrue();
        assertThat(ApiKeyScope.forPath("/v1/filesystem")).as("a prefix is not a family").isEmpty();
        assertThat(ApiKeyScope.forPath("/v1/modelsx")).isEmpty();
        assertThat(ApiKeyScope.forPath("/v1/batchesque")).isEmpty();
    }
}
