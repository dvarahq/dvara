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

import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;

/**
 * The one place that answers "what is this workspace's PII posture".
 *
 * <p><b>Typed store first, metadata map second.</b> Config imports and bundles may still carry the
 * map, and a reader that saw only the typed store would silently drop a workspace's stated posture.
 *
 * <p><b>A row wins wholesale: it is the workspace's whole posture, not a set of overrides.</b> When a
 * row exists the map is not read, which is why {@link PiiSettings#unset} must never be a write
 * baseline: a writer that seeds from it and fills in part of a form stores nulls for the rest, and
 * this read then serves those nulls. A writer carries the rest forward with
 * {@link PiiSettings#withFallback}; making the read merge instead would put a workspace lookup on
 * every scanned request. The rate-limit resolver resolves the same way.
 *
 * <p><b>Absent is not the same as empty.</b> A repository that has no row falls through to the map;
 * a repository that is not present at all falls through too. Neither is an error, and neither may
 * be read as "this workspace turned everything off".
 */
public class PiiSettingsResolver {

    private final PiiSettingsRepository settings;
    private final WorkspaceRepository workspaces;

    public PiiSettingsResolver(PiiSettingsRepository settings, WorkspaceRepository workspaces) {
        this.settings = settings;
        this.workspaces = workspaces;
    }

    /**
     * The workspace's stored posture, or a fully-unset one meaning "inherit everything".
     *
     * <p>Never throws and never returns null: this is on the request path of every scanned request,
     * and a configuration lookup that can fail a request is a worse problem than the one being
     * solved.
     */
    public PiiSettings resolve(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return PiiSettings.unset(workspaceId);
        }
        if (settings != null) {
            PiiSettings stored = settings.findByWorkspaceId(workspaceId).orElse(null);
            if (stored != null) {
                return stored;
            }
        }
        if (workspaces == null) {
            return PiiSettings.unset(workspaceId);
        }
        Workspace workspace = workspaces.findById(workspaceId).orElse(null);
        return workspace == null
                ? PiiSettings.unset(workspaceId)
                : PiiSettings.fromMetadata(workspaceId, workspace.getMetadata());
    }
}