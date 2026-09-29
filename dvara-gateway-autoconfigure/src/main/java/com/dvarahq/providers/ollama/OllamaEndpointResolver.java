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
package com.dvarahq.providers.ollama;

import java.util.Optional;

/**
 * Where a workspace's own Ollama is (#30, UC-105 A6): on DVARA Cloud a tenant registers its Ollama, usually
 * behind an authenticating reverse proxy, and an {@code ollama/…} call from that workspace goes there and
 * nowhere else. The platform-wide {@code OLLAMA_BASE_URL} would be DVARA's own machine, not the tenant's.
 *
 * <p>An implementation returns only an endpoint that passed egress validation: {@code https}, and no
 * private, loopback, link-local or cloud-metadata address. It never returns another workspace's endpoint,
 * and never a platform-wide one.
 */
@FunctionalInterface
public interface OllamaEndpointResolver {

    /** The workspace's own Ollama, or empty when it registered none (UC-105 A7). */
    Optional<Endpoint> resolve(String workspaceId);

    /**
     * A tenant's Ollama: its base URL ({@code https://ollama.example.com}, with or without {@code /v1}),
     * and the credential sent as {@code Authorization: Bearer}, or null for none.
     */
    record Endpoint(String baseUrl, String credential) {
        @Override
        public String toString() {
            return "Endpoint[" + baseUrl + ", credential=" + (credential == null ? "none" : "***") + "]";
        }
    }
}
