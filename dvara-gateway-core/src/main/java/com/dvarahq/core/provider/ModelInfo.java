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
package com.dvarahq.core.provider;

/**
 * Describes a model available from a provider.
 *
 * @param id      model identifier (e.g. "gpt-4o", "claude-sonnet-4-5")
 * @param ownedBy owner/organization (e.g. "openai", "anthropic")
 * @param created epoch seconds when the model was created (0 if unknown)
 */
public record ModelInfo(String id, String ownedBy, long created) {

    public ModelInfo(String id, String ownedBy) {
        this(id, ownedBy, 0L);
    }
}