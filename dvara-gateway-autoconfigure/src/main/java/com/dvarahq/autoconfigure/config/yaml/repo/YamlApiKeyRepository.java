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
package com.dvarahq.autoconfigure.config.yaml.repo;

import com.dvarahq.core.apikey.ApiKey;
import com.dvarahq.core.apikey.ApiKeyRepository;

import java.util.List;
import java.util.Optional;

/**
 * API keys, served from {@code gateway.yaml}.
 *
 * <p>The file carries each key's SHA-256 fingerprint ({@code key_hash}), never the key: a
 * {@code key:} entry refuses the boot. {@link YamlConfigStore} holds that hash, so the lookup
 * {@code ApiKeyAuthFilter} performs is the same for this store as for any other.
 *
 * <p>Revocation means editing the file and restarting, because the file is the source of truth
 * rather than a cache of one.
 */
public class YamlApiKeyRepository implements ApiKeyRepository {

    private final YamlConfigStore store;

    public YamlApiKeyRepository(YamlConfigStore store) {
        this.store = store;
    }

    @Override
    public Optional<ApiKey> findByKeyHash(String keyHash) {
        return Optional.ofNullable(store.keysByHash().get(keyHash));
    }

    @Override
    public Optional<ApiKey> findById(String id) {
        return store.keys().stream().filter(k -> k.getId().equals(id)).findFirst();
    }

    @Override
    public List<ApiKey> findByWorkspaceId(String workspaceId) {
        if (workspaceId == null) {
            return List.of();
        }
        return store.keys().stream().filter(k -> workspaceId.equals(k.getWorkspaceId())).toList();
    }

    @Override
    public List<ApiKey> findAll() {
        return store.keys();
    }

    @Override
    public ApiKey save(ApiKey apiKey) {
        throw YamlReadOnly.on("ApiKeyRepository.save");
    }

    @Override
    public boolean revokeById(String id) {
        throw YamlReadOnly.on("ApiKeyRepository.revokeById");
    }

    @Override
    public boolean deleteById(String id) {
        throw YamlReadOnly.on("ApiKeyRepository.deleteById");
    }
}