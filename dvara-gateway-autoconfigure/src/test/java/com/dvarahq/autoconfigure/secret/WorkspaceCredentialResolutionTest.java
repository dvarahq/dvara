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
package com.dvarahq.autoconfigure.secret;

import com.dvarahq.autoconfigure.GatewayEncryptionProperties;
import com.dvarahq.autoconfigure.GatewayProperties;
import com.dvarahq.core.secret.WorkspaceCredentialSource;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A workspace's own credential is preferred over the installation-wide one, and its absence falls
 * back rather than refusing.
 */
class WorkspaceCredentialResolutionTest {

    private static final String KEY = "provider.openai.api-key";

    private static GatewayProperties platformKey(String value) {
        GatewayProperties p = new GatewayProperties();
        p.getProviders().getOpenai().setApiKey(value);
        return p;
    }

    private static WorkspaceCredentialSource source(Map<String, String> byWorkspace) {
        return (workspaceId, secretKey) -> workspaceId == null || !KEY.equals(secretKey)
                ? Optional.empty()
                : Optional.ofNullable(byWorkspace.get(workspaceId));
    }

    private static PropertySecretProvider provider(String platform, Map<String, String> byWorkspace) {
        return new PropertySecretProvider(platformKey(platform), new GatewayEncryptionProperties(),
                source(byWorkspace));
    }

    @Test
    void aWorkspaceWithItsOwnKeyUsesIt() {
        var secrets = provider("platform-key", Map.of("acme", "acme-key"));

        assertThat(secrets.getSecret(KEY, "acme")).contains("acme-key");
    }

    @Test
    void aWorkspaceWithoutOneFallsBack_ratherThanBeingRefused() {
        // Absence means "use what the installation configured", which is what makes adding one
        // workspace's key a change to that workspace alone. Forcing a workspace to bring its own is
        // a different question and a different switch.
        var secrets = provider("platform-key", Map.of("acme", "acme-key"));

        assertThat(secrets.getSecret(KEY, "globex")).contains("platform-key");
    }

    @Test
    void twoWorkspacesDoNotShareAKey() {
        // One gateway serving two teams on one provider key would leave neither the provider's
        // invoice nor an abuse report able to tell them apart.
        var secrets = provider("platform-key", Map.of("acme", "acme-key", "globex", "globex-key"));

        assertThat(secrets.getSecret(KEY, "acme")).contains("acme-key");
        assertThat(secrets.getSecret(KEY, "globex")).contains("globex-key");
    }

    @Test
    void aCallWithNoWorkspaceGetsTheInstallationKey() {
        // An installation that does not require API keys serves anonymous calls. They have no
        // workspace, so by definition no workspace credential.
        var secrets = provider("platform-key", Map.of("acme", "acme-key"));

        assertThat(secrets.getSecret(KEY, null)).contains("platform-key");
    }

    @Test
    void withNoSourceAtAll_theWorkspaceArgumentIsIgnored_asBefore() {
        // With no source registered the workspace argument is ignored and the installation-wide
        // value is returned.
        var secrets = new PropertySecretProvider(platformKey("platform-key"),
                new GatewayEncryptionProperties(), null);

        assertThat(secrets.getSecret(KEY, "acme")).contains("platform-key");
        assertThat(secrets.getSecret(KEY)).contains("platform-key");
    }

    @Test
    void theSingleArgLookupIsUnaffectedByWorkspaceCredentials() {
        // Anything asking without a workspace must still get the installation-wide value.
        var secrets = provider("platform-key", Map.of("acme", "acme-key"));

        assertThat(secrets.getSecret(KEY)).contains("platform-key");
    }
}
