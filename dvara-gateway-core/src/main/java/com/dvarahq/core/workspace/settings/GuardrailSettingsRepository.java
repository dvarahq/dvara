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

/** The typed store behind a workspace's guardrail posture. */
public interface GuardrailSettingsRepository {

    Optional<GuardrailSettings> findByWorkspaceId(String workspaceId);

    List<GuardrailSettings> findAll();

    /** Upsert; a fully-unset object removes the row rather than storing an empty one. */
    GuardrailSettings save(GuardrailSettings settings);

    /**
     * Every workspace whose guardrails are explicitly disabled: the question a security review asks,
     * which a JSONB map cannot answer without a scan.
     */
    List<String> workspaceIdsWithGuardrailsDisabled();
}