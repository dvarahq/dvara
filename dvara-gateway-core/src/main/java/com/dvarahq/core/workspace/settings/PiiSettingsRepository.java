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

import com.dvarahq.core.pii.PiiAction;

import java.util.List;
import java.util.Optional;

/**
 * The typed store behind a workspace's PII posture.
 *
 * <p>{@link #workspaceIdsByAction} is the reason for a typed store: "which workspaces have
 * {@code pii.action=BLOCK}" has to be a plain query rather than a JSONB scan over every workspace.
 */
public interface PiiSettingsRepository {

    /** Empty when the workspace has expressed no PII opinion — inherit the install-wide default. */
    Optional<PiiSettings> findByWorkspaceId(String workspaceId);

    /** Every stored row, for the config-bundle materializer. */
    List<PiiSettings> findAll();

    /**
     * Upsert. Storing a fully-unset settings object deletes the row rather than writing an empty
     * one — "no opinion" and "an opinion consisting of nothing" must not be two states.
     */
    PiiSettings save(PiiSettings settings);

    /** The query the map could not answer. */
    List<String> workspaceIdsByAction(PiiAction action);
}