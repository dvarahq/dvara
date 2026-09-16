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

import com.dvarahq.core.guardrail.OutputSchemaConfig;
import com.dvarahq.core.guardrail.OutputSchemaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Output schemas, served from {@code gateway.yaml}.
 *
 * <p>The consumer is {@code DefaultOutputSchemaValidator} in {@code dvara-gateway-policy}, so a
 * schema written into the file is enforced: a response that does not satisfy it is refused. It is
 * validated once, with no retry.
 *
 * <p>Both lookups filter on {@code enabled}, because a disabled schema that still matched would
 * enforce a contract the operator has switched off, and the flag exists so an entry can be switched
 * off without being deleted.
 */
public class YamlOutputSchemaRepository implements OutputSchemaRepository {

    private final YamlConfigStore store;

    public YamlOutputSchemaRepository(YamlConfigStore store) {
        this.store = store;
    }

    @Override
    public Optional<OutputSchemaConfig> findById(String id) {
        return Optional.ofNullable(store.schemasById().get(id));
    }

    @Override
    public List<OutputSchemaConfig> findAll() {
        return store.schemas();
    }

    @Override
    public List<OutputSchemaConfig> findByRouteId(String routeId) {
        if (routeId == null) {
            return List.of();
        }
        return store.schemas().stream()
                .filter(OutputSchemaConfig::isEnabled)
                .filter(s -> routeId.equals(s.getRouteId()))
                .toList();
    }

    @Override
    public List<OutputSchemaConfig> findByModelPattern(String modelPattern) {
        if (modelPattern == null) {
            return List.of();
        }
        return store.schemas().stream()
                .filter(OutputSchemaConfig::isEnabled)
                .filter(s -> modelPattern.equals(s.getModelPattern()))
                .toList();
    }

    @Override
    public OutputSchemaConfig save(OutputSchemaConfig config) {
        throw YamlReadOnly.on("OutputSchemaRepository.save");
    }

    @Override
    public boolean deleteById(String id) {
        throw YamlReadOnly.on("OutputSchemaRepository.deleteById");
    }
}