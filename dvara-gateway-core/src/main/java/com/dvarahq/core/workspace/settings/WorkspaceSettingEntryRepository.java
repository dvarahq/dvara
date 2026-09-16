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
import java.util.Map;

/** The typed store behind the workspace settings that are nested maps in the metadata map. */
public interface WorkspaceSettingEntryRepository {

    /**
     * One label → one value, for the kinds where a label is unique.
     *
     * <p>Returns the {@code Map<String, String>} the callers work with; the values are stored
     * validated and individually. Empty when nothing is stored.
     */
    Map<String, String> mapOf(String workspaceId, WorkspaceSettingEntry.Kind kind);

    /** One label → several values, for {@link WorkspaceSettingEntry.Kind#DENY_INTENT}. */
    Map<String, List<String>> multiMapOf(String workspaceId, WorkspaceSettingEntry.Kind kind);

    /** Replaces one kind wholesale — a merge would make deletion inexpressible. */
    void replace(String workspaceId, WorkspaceSettingEntry.Kind kind, Map<String, List<String>> entries);

    /** "Which workspaces override this category's action" — a question the map could not answer. */
    List<String> workspaceIdsWithLabel(WorkspaceSettingEntry.Kind kind, String label);
}