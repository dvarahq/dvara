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

/**
 * A workspace save was refused because the workspace changed, or was deleted, after the caller read
 * it. Saving anyway would put back the values the caller read over whatever was written since.
 */
public class ConcurrentWorkspaceChangeException extends RuntimeException {

    private final String workspaceId;

    public ConcurrentWorkspaceChangeException(String workspaceId) {
        super("Workspace " + workspaceId + " was changed by someone else after it was read. "
                + "Reload it and apply the change again.");
        this.workspaceId = workspaceId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }
}
