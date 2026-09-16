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
package com.dvarahq.policy.streaming;

import com.dvarahq.core.enforcement.StreamingPosture;
import com.dvarahq.core.enforcement.StreamingPostureProvider;
import com.dvarahq.core.guardrail.GroundingConfig;
import com.dvarahq.core.workspace.Workspace;
import com.dvarahq.core.workspace.WorkspaceRepository;
import com.dvarahq.core.workspace.settings.GuardrailSettingsRepository;
import com.dvarahq.core.workspace.settings.PiiSettingsRepository;
import com.dvarahq.core.workspace.settings.PiiSettingsResolver;
import com.dvarahq.policy.guardrail.GuardrailProperties;
import com.dvarahq.pii.PiiProperties;

import java.util.List;
import java.util.Map;

/** The single runtime bridge from stored settings and global properties to the shared resolver. */
public final class DefaultStreamingPostureProvider implements StreamingPostureProvider {

    private final WorkspaceRepository workspaces;
    private final PiiSettingsResolver piiSettings;
    private final GuardrailSettingsRepository guardrailSettings;
    private final StreamingDefaults defaults;

    public DefaultStreamingPostureProvider(WorkspaceRepository workspaces,
                                           PiiSettingsRepository piiSettings,
                                           GuardrailSettingsRepository guardrailSettings,
                                           PiiProperties piiProperties,
                                           GuardrailProperties guardrailProperties,
                                           GroundingConfig groundingConfig) {
        this.workspaces = workspaces;
        this.piiSettings = new PiiSettingsResolver(piiSettings, workspaces);
        this.guardrailSettings = guardrailSettings;
        this.defaults = new StreamingDefaults(
                piiProperties.isEnabled(), piiProperties.isScanStreamingResponses(),
                piiProperties.getDefaultAction(),
                guardrailProperties.isEnabled(), guardrailProperties.isScanStreamingResponses(),
                guardrailProperties.getDefaultAction(), guardrailProperties.getRiskScoreThreshold(),
                groundingConfig.enabled(), groundingConfig.action(),
                groundingConfig.maxSources(), groundingConfig.maxSourceLength());
    }

    @Override
    public StreamingPosture resolve(String workspaceId, List<String> groundingSources) {
        Workspace workspace = workspaceId == null || workspaces == null
                ? null : workspaces.findById(workspaceId).orElse(null);
        Map<String, Object> metadata = workspace == null || workspace.getMetadata() == null
                ? Map.of() : workspace.getMetadata();
        return StreamingPostureResolver.resolve(defaults,
                workspaceId == null ? null : piiSettings.resolve(workspaceId),
                guardrailSettings == null || workspaceId == null
                        ? null : guardrailSettings.findByWorkspaceId(workspaceId).orElse(null),
                metadata, groundingSources, workspaceId);
    }
}
