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
package com.dvarahq.core.plane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which plane owns a path.
 *
 * <p>The three {@code @ControllerAdvice} handlers route 404s through {@code resolve} so an unknown
 * {@code /mcp/} path returns an MCP-shaped error rather than the LLM plane's. A client that speaks
 * one envelope and receives another cannot parse the failure it was given.
 *
 * <p>The {@code null} cases matter most: {@code null} is the answer for a path no plane claims, and a
 * default plane would satisfy every positive case here while telling callers their request went
 * somewhere it did not.
 */
class GatewayPlaneTest {

    @ParameterizedTest
    @CsvSource({
            "/v1/chat/completions, LLM",
            "/v1/, LLM",
            "/mcp/stub-a/tools/call, MCP",
            "/mcp/, MCP",
            "/a2a/peer-a/message:send, A2A",
            "/a2a/, A2A",
    })
    void resolvesAPathToThePlaneThatOwnsIt(String path, GatewayPlane expected) {
        assertThat(GatewayPlane.resolve(path)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",     // operator surface — belongs to no plane
            "/portal/keys",         // a UI path, not a plane
            "/foo",
            "",
            "/",
            "/v1",                  // no trailing slash: NOT the LLM prefix
            "/mcp",                 // likewise — and this one is real: the native MCP servlet is
                                    // mounted at exactly /mcp, so a change here would reclassify a
                                    // live endpoint
            "/a2a",
            "v1/chat/completions",  // no leading slash
            "/V1/chat/completions", // prefix matching is case-SENSITIVE
    })
    void answersNullWhenNoPlaneOwnsThePath(String path) {
        assertThat(GatewayPlane.resolve(path))
                .as("null is the honest answer; inventing a plane would tell the caller something "
                        + "untrue about where their request went")
                .isNull();
    }

    @Test
    void answersNullForANullPath() {
        assertThat(GatewayPlane.resolve(null)).isNull();
    }

    @Test
    void ownsIsTheSameQuestionResolveAsksAndIsNullSafe() {
        // Every filter on a plane's path asks this, so the mapping has one place to change.
        assertThat(GatewayPlane.LLM.owns("/v1/chat/completions")).isTrue();
        assertThat(GatewayPlane.LLM.owns("/mcp/tools/call")).isFalse();
        assertThat(GatewayPlane.MCP.owns("/mcp/tools/call")).isTrue();
        assertThat(GatewayPlane.LLM.owns("/v1")).as("pinned: the bare prefix is not the plane's").isFalse();
        assertThat(GatewayPlane.LLM.owns(null)).as("a filter asks before anything has validated").isFalse();
    }

    @Test
    void everyPlaneHasADistinctPrefixAndNoneIsAPrefixOfAnother() {
        // resolve() returns the FIRST match in declaration order, so overlapping prefixes would make
        // the mapping depend on enum ordering rather than on the path. Pinning it here means adding a
        // plane whose prefix nests inside another fails at build time rather than by misrouting.
        for (GatewayPlane a : GatewayPlane.values()) {
            assertThat(a.pathPrefix()).startsWith("/").endsWith("/");
            for (GatewayPlane b : GatewayPlane.values()) {
                if (a != b) {
                    assertThat(a.pathPrefix())
                            .as("%s and %s must not nest", a, b)
                            .doesNotStartWith(b.pathPrefix());
                }
            }
        }
    }
}