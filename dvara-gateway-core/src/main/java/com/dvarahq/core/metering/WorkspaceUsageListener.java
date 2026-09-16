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
package com.dvarahq.core.metering;

/**
 * Told when a usage row has been persisted for a workspace.
 *
 * <p>The execution path records usage and moves on; what a deployment does with that fact — a
 * threshold cascade, an email, a meter — is its own business, registered as a bean of this type.
 *
 * <p><b>There is no default bean and no no-op constant.</b> The runtime collects these into a list,
 * so an empty list is the no-op, and a module registering one joins the others rather than
 * replacing them.
 */
@FunctionalInterface
public interface WorkspaceUsageListener {

    void usageRecorded(String workspaceId);
}
