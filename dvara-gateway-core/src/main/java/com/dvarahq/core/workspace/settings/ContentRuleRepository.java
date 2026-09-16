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

/**
 * Where a workspace's competitor and topic lists are stored, when anything stores them.
 *
 * <h2>Not implemented in this build</h2>
 *
 * <p>Nothing in this repository implements it. The content filter takes it as an
 * {@code ObjectProvider} and falls back to the workspace's metadata map, which is where a
 * file-configured gateway keeps these. Another module or the application may register a typed
 * settings store.
 *
 * <p>It answers questions about one workspace only. A fleet-wide lookup is a question for
 * something that can act on the answer, and nothing here does.
 */
public interface ContentRuleRepository {

    /** The values of one kind for one workspace, empty when none are configured. */
    List<String> valuesOf(String workspaceId, ContentRule.Kind kind);

    List<ContentRule> findByWorkspaceId(String workspaceId);

    void replace(String workspaceId, ContentRule.Kind kind, List<String> values);
}
