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
package com.dvarahq.core.workspace.settings;

import java.util.List;
import java.util.Optional;

/**
 * Where a workspace's own rate-limit ceilings are stored, when anything stores them.
 *
 * <h2>Not implemented in this build</h2>
 *
 * <p>Nothing in this repository implements it. The consumer takes this as an
 * {@code ObjectProvider} and falls back to reading {@link WorkspaceRateLimits#fromMetadata} off the
 * workspace's metadata map, which is where a file-configured gateway keeps them. Another module or
 * the application may register a typed settings store.
 */
public interface WorkspaceRateLimitsRepository {

    Optional<WorkspaceRateLimits> findByWorkspaceId(String workspaceId);

    List<WorkspaceRateLimits> findAll();

    WorkspaceRateLimits save(WorkspaceRateLimits limits);
}
