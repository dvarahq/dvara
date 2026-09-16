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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKey {
    private String id;
    private String workspaceId;
    private String name;
    private String keyPrefix;
    private String keyHash;
    private List<String> scopes;
    private ApiKeyStatus status;
    private Instant expiresAt;
    private Instant revokedAt;
    private Instant createdAt;
    private Instant updatedAt;

    /**
     * An opaque grouping label carried on the key row, persisted and hydrated verbatim.
     *
     * <p>Nothing in this build reads it: no filter, no limiter and no part of the request path
     * consults it, and setting it changes no behaviour here. It exists because a store may persist
     * a column of this name and round-trip it, so removing the field would silently drop that value
     * on every read and write.
     */
    private String tier;
}