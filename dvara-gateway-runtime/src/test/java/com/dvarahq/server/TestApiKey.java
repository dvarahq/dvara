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
package com.dvarahq.server;

import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyGenerator;
import com.dvarahq.core.apikey.ApiKeyRepository;
import com.dvarahq.core.apikey.ApiKeyStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The one API key this module's tests call the gateway with.
 *
 * <p>Every request under {@code /v1} is authenticated before it is served, so a test that exercises
 * a controller or a filter behind {@code ApiKeyAuthFilter} has to present a key the store knows.
 * This is that key: {@link #KEY} is the bearer value, {@link #ID} is what the access log, the usage
 * rows and the rate limiter see, and {@link #WORKSPACE} is the workspace every such request belongs
 * to. {@link #repository()} is a store that knows exactly this key and nothing else.
 *
 * <p>A test that wants a different workspace, a revoked key or no key at all builds its own request
 * or its own filter; nothing here stops it.
 */
public final class TestApiKey {

    public static final String KEY = "gw_test000000000000000000000000000000000001";
    public static final String HASH = ApiKeyGenerator.hash(KEY);
    public static final String ID = "test-key";
    public static final String WORKSPACE = "test-workspace";
    public static final String BEARER = "Bearer " + KEY;

    private TestApiKey() {
    }

    public static ApiKey apiKey() {
        return ApiKey.builder()
                .id(ID).name(ID).workspaceId(WORKSPACE)
                .keyHash(HASH).keyPrefix("")
                .status(ApiKeyStatus.ACTIVE)
                .createdAt(Instant.EPOCH).updatedAt(Instant.EPOCH)
                .build();
    }

    /** A store holding {@link #apiKey()} and nothing else; writes are refused. */
    public static ApiKeyRepository repository() {
        return new ApiKeyRepository() {
            @Override public Optional<ApiKey> findById(String id) {
                return ID.equals(id) ? Optional.of(apiKey()) : Optional.empty();
            }
            @Override public Optional<ApiKey> findByKeyHash(String keyHash) {
                return HASH.equals(keyHash) ? Optional.of(apiKey()) : Optional.empty();
            }
            @Override public List<ApiKey> findByWorkspaceId(String workspaceId) {
                return WORKSPACE.equals(workspaceId) ? List.of(apiKey()) : List.of();
            }
            @Override public List<ApiKey> findAll() {
                return List.of(apiKey());
            }
            @Override public ApiKey save(ApiKey apiKey) {
                throw new UnsupportedOperationException("the test key store holds one fixed key");
            }
            @Override public boolean revokeById(String id) {
                throw new UnsupportedOperationException("the test key store holds one fixed key");
            }
            @Override public boolean deleteById(String id) {
                throw new UnsupportedOperationException("the test key store holds one fixed key");
            }
        };
    }
}
