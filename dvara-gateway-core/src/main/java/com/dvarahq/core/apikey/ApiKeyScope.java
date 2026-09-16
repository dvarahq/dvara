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

import java.util.List;
import java.util.Optional;

/**
 * What an API key's {@code scopes} may contain, and which requests each scope covers. This is the
 * vocabulary the request filter and the {@code gateway.yaml} validator use.
 *
 * <p>Two rules:
 * <ul>
 *   <li><b>A key with no scopes is unrestricted.</b></li>
 *   <li><b>A path with no scope defined is not governed by scopes.</b> Only the {@code /v1}
 *       families below are.</li>
 * </ul>
 * An unknown scope string is refused by the {@code gateway.yaml} validator and grants nothing
 * where a request is checked, so a typo narrows a key rather than widening it.
 *
 * <p>Another module may serve other request planes with scope values of its own, checked by its
 * own filters. A key scoped to one plane carries no value from another, so it is refused there;
 * empty scopes remain unrestricted everywhere.
 */
public enum ApiKeyScope {

    /**
     * {@code /v1/chat/completions}, {@code /v1/completions}, {@code /v1/responses}, and
     * {@code /v1/budget} — the pre-flight estimate for one of them.
     */
    COMPLETIONS_WRITE("completions:write"),
    /** {@code /v1/embeddings}. Not covered by completions, so an embeddings-only key can be issued. */
    EMBEDDINGS_WRITE("embeddings:write"),
    /** {@code /v1/batches} and everything under it, and {@code /v1/files} — the batch input upload. */
    BATCHES_WRITE("batches:write"),
    /** {@code /v1/models}. */
    MODELS_READ("models:read");

    private final String value;

    ApiKeyScope(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static boolean isKnown(String value) {
        return fromValue(value).isPresent();
    }

    public static Optional<ApiKeyScope> fromValue(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (ApiKeyScope s : values()) {
            if (s.value.equals(value)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** The scope that governs a request path, or empty when scopes say nothing about it. */
    public static Optional<ApiKeyScope> forPath(String path) {
        if (path == null) {
            return Optional.empty();
        }
        if (under(path, "/v1/chat/completions") || under(path, "/v1/completions") || under(path, "/v1/responses")) {
            return Optional.of(COMPLETIONS_WRITE);
        }
        // /v1/budget prices a request the caller intends to make and reports the workspace's
        // remaining budget, so it belongs to the keys that may make that request. The whole family
        // is governed, not one path, because everything under it is budget state. This build does
        // not serve the endpoint; its scope is stated here because this method is what the request
        // filter consults for every URI, whichever module mapped it.
        if (under(path, "/v1/budget")) {
            return Optional.of(COMPLETIONS_WRITE);
        }
        if (under(path, "/v1/embeddings")) {
            return Optional.of(EMBEDDINGS_WRITE);
        }
        // Uploading a batch input file is the first step of the batch workflow, so it is governed
        // by the batch scope too.
        if (under(path, "/v1/batches") || under(path, "/v1/files")) {
            return Optional.of(BATCHES_WRITE);
        }
        if (under(path, "/v1/models")) {
            return Optional.of(MODELS_READ);
        }
        return Optional.empty();
    }

    /** Segment-boundary match: {@code /v1/files} and {@code /v1/files/abc}, never {@code /v1/filesystem}. */
    private static boolean under(String path, String family) {
        return path.equals(family) || path.startsWith(family + "/");
    }

    /**
     * Whether a key carrying {@code scopes} may call {@code path}. Empty scopes: yes. A path no scope
     * governs: yes. Otherwise only when the governing scope's exact value is present.
     */
    public static boolean permits(List<String> scopes, String path) {
        if (scopes == null || scopes.isEmpty()) {
            return true;
        }
        Optional<ApiKeyScope> required = forPath(path);
        return required.isEmpty() || scopes.contains(required.get().value);
    }
}
