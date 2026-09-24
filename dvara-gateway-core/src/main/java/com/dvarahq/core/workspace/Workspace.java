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
package com.dvarahq.core.workspace;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Workspace {
    private String id;
    private String name;
    /**
     * The owning team. Nullable: every production path sets it, but many tests build a
     * {@code Workspace} directly, and the column is not NOT NULL.
     */
    private String teamId;
    private WorkspaceStatus status;
    private String region;
    private Map<String, Object> metadata;
    /**
     * The workspace's own governance settings, grouped by area ({@code pii}, {@code guardrail}, and
     * so on), for a store that keeps them apart from {@link #metadata}. Nullable: the file store does
     * not set it, and readers that find nothing here fall back to {@link #metadata}.
     */
    private Map<String, Object> settings;
    private Instant createdAt;
    private Instant updatedAt;
}