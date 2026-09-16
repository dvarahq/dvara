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

/**
 * Which governed plane a request path belongs to.
 *
 * <p>The three planes share one process, so an unmatched path can belong to any of them, or to
 * none, and the error a client gets back has to say which.</p>
 *
 * <p><b>The planes render deliberately different error envelopes</b> — the LLM plane's
 * {@code {"error": {"code": …}}}, the MCP shape, and A2A's JSON-RPC. A client that speaks one
 * protocol should be able to parse the response even when it gets the URL wrong, which is precisely
 * when a machine-readable error matters most.</p>
 *
 * <p><b>{@link #resolve} answers "which plane owns this path", not "is that plane available".</b>
 * Those are different questions and conflating them is the trap: a request to a plane that is
 * present-but-disabled, or enabled-but-unavailable, must not be reported as <em>not found</em>.
 * Collapsing "this plane is not enabled on this process" into a 404 makes a misconfigured
 * deployment look like an application bug, and the operator debugs the wrong thing.</p>
 */
public enum GatewayPlane {

    LLM("/v1/"),
    MCP("/mcp/"),
    A2A("/a2a/");

    private final String pathPrefix;

    GatewayPlane(String pathPrefix) {
        this.pathPrefix = pathPrefix;
    }

    public String pathPrefix() {
        return pathPrefix;
    }

    /**
     * Whether this plane owns {@code path}.
     *
     * <p>Every filter on a plane's path asks this here rather than with its own
     * {@code startsWith}, so the mapping has one place to change and a 404's envelope cannot
     * disagree with where the request went.
     *
     * <p>Matching is a raw prefix test. That is safe on the LLM plane because a non-canonical
     * {@code /v1} path is refused with {@code invalid_path} before routing.
     */
    public boolean owns(String path) {
        return path != null && path.startsWith(pathPrefix);
    }

    /**
     * The plane owning this request path, or {@code null} when none does.
     *
     * <p>{@code null} is the honest answer for {@code /foo}, {@code /actuator/health} or a typo'd
     * prefix: there is no plane-native envelope for a path no plane claims, and inventing one would
     * tell the caller something untrue about where their request went.</p>
     */
    public static GatewayPlane resolve(String path) {
        if (path == null) {
            return null;
        }
        for (GatewayPlane plane : values()) {
            if (plane.owns(path)) {
                return plane;
            }
        }
        return null;
    }
}