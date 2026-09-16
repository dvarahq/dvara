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
package com.dvarahq.core.secret;

import java.util.Optional;

/**
 * Where the gateway finds the credential a particular workspace brought, as distinct from the one
 * the installation configured for everybody.
 *
 * <p>The workspace-aware overloads on {@link SecretProvider} default to the installation-wide
 * value; this is what gives the workspace argument a meaning. Without it one gateway serving three
 * teams sends all of their traffic on one provider key, so neither the provider's invoice nor an
 * abuse report can tell them apart, and
 * {@link com.dvarahq.core.credential.CredentialFingerprint} has one answer however many workspaces
 * are configured.
 *
 * <h2>What it does not promise</h2>
 *
 * <p>A lookup, not a lifecycle. There is no rotation, no grace window, no status, no vault and no
 * audit of a credential changing; a source may be a file read once at startup. An application that
 * manages credentials across a fleet supplies its own {@link SecretProvider} and does not use this.
 *
 * <p><b>Absence means fall back, never refuse.</b> A workspace with no credential of its own gets
 * the installation-wide one. Forcing a workspace to bring its own is a separate decision and a
 * separate switch; it is not expressed by returning empty here.
 */
@FunctionalInterface
public interface WorkspaceCredentialSource {

    /**
     * The credential this workspace brought for the given key, or empty to fall back.
     *
     * @param workspaceId the resolved workspace, which may be {@code null} for a call with none —
     *                    an installation not requiring API keys serves those, and they have no
     *                    workspace credential by definition
     * @param secretKey   the logical key, e.g. {@code provider.openai.api-key}
     */
    Optional<String> credentialFor(String workspaceId, String secretKey);
}
