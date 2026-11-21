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
package com.dvarahq.providers.vertexai;

import com.dvarahq.core.exception.GatewayException;
import com.dvarahq.core.secret.SecretProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which credential a call uses: the secret, the configured file, then application default credentials. */
class VertexAiCredentialsTest {

    static final TestKeys KEYS = new TestKeys();

    @TempDir
    Path dir;

    private final Map<String, String> env = new HashMap<>();
    /** Secrets by workspace; the null key is the installation's own. */
    private final Map<String, String> secrets = new HashMap<>();

    private final SecretProvider secretProvider = new SecretProvider() {
        @Override
        public Optional<String> getSecret(String key) {
            return getSecret(key, null);
        }

        @Override
        public Optional<String> getSecret(String key, String workspaceId) {
            if (!VertexAiCredentials.SECRET.equals(key)) {
                return Optional.empty();
            }
            String own = workspaceId == null ? null : secrets.get(workspaceId);
            return Optional.ofNullable(own != null ? own : secrets.get(null));
        }
    };

    private VertexAiCredentials credentials(String credentialsFile) {
        return new VertexAiCredentials(secretProvider, credentialsFile, env::get, dir.resolve("home"));
    }

    @Test
    void theSecretComesFirst_andEachWorkspaceKeyIsKeptApart() throws IOException {
        Path file = write("configured.json", KEYS.serviceAccountJson());
        secrets.put(null, KEYS.serviceAccountJson());
        TestKeys other = new TestKeys();
        secrets.put("ws-own", other.serviceAccountJson());
        VertexAiCredentials credentials = credentials(file.toString());

        VertexAiCredentials.Resolved platform = credentials.resolve("ws-plain");
        VertexAiCredentials.Resolved own = credentials.resolve("ws-own");

        assertThat(platform.cacheKey()).startsWith("json:");
        assertThat(own.cacheKey()).startsWith("json:").isNotEqualTo(platform.cacheKey());
        assertThat(((GoogleCredential.ServiceAccount) own.credential().get()).privateKey().getEncoded())
                .isEqualTo(other.pair.getPrivate().getEncoded());
        assertThat(own.cacheKey()).as("a hash, not the key").doesNotContain("PRIVATE");
    }

    @Test
    void theConfiguredFileComesNext_andIsReadWhenATokenIsMinted() throws IOException {
        Path file = write("configured.json", "{\"type\":\"external_account\"}");
        env.put("GOOGLE_APPLICATION_CREDENTIALS", write("adc.json", KEYS.serviceAccountJson()).toString());

        VertexAiCredentials.Resolved resolved = credentials(file.toString()).resolve(null);

        assertThat(resolved.cacheKey()).isEqualTo("file:" + file.toAbsolutePath());
        // Rotated in place: the next mint reads the new content.
        Files.writeString(file, KEYS.serviceAccountJson());
        assertThat(resolved.credential().get()).isInstanceOf(GoogleCredential.ServiceAccount.class);
    }

    @Test
    void thenGoogleApplicationCredentials() throws IOException {
        Path adc = write("adc.json", KEYS.serviceAccountJson());
        env.put("GOOGLE_APPLICATION_CREDENTIALS", adc.toString());

        assertThat(credentials(null).resolve(null).cacheKey()).isEqualTo("file:" + adc.toAbsolutePath());
    }

    @Test
    void thenGcloudsOwnFile() throws IOException {
        Path gcloud = dir.resolve("home/.config/gcloud");
        Files.createDirectories(gcloud);
        Path wellKnown = Files.writeString(gcloud.resolve("application_default_credentials.json"),
                "{\"type\":\"authorized_user\",\"client_id\":\"c\",\"client_secret\":\"s\",\"refresh_token\":\"r\"}");

        VertexAiCredentials.Resolved resolved = credentials(" ").resolve(null);

        assertThat(resolved.cacheKey()).isEqualTo("file:" + wellKnown.toAbsolutePath());
        assertThat(resolved.credential().get()).isInstanceOf(GoogleCredential.AuthorizedUser.class);
    }

    @Test
    void cloudSdkConfigMovesGcloudsFile() throws IOException {
        Path config = Files.createDirectories(dir.resolve("sdk"));
        Path wellKnown = Files.writeString(config.resolve("application_default_credentials.json"), KEYS.serviceAccountJson());
        env.put("CLOUDSDK_CONFIG", config.toString());

        assertThat(credentials(null).resolve(null).cacheKey()).isEqualTo("file:" + wellKnown.toAbsolutePath());
    }

    @Test
    void elseTheMetadataServer_atTheHostTheEnvironmentNames() {
        assertThat(credentials(null).resolve(null).credential().get())
                .isEqualTo(new GoogleCredential.MetadataServer("http://metadata.google.internal"));

        env.put("GCE_METADATA_HOST", "127.0.0.1:8089");
        assertThat(credentials(null).resolve(null).credential().get())
                .isEqualTo(new GoogleCredential.MetadataServer("http://127.0.0.1:8089"));
    }

    @Test
    void aFileThatCannotBeReadOrUsedSaysSo_withoutItsContent() throws IOException {
        Path missing = dir.resolve("missing.json");
        assertThatThrownBy(() -> credentials(missing.toString()).resolve(null).credential().get())
                .isInstanceOfSatisfying(GatewayException.class, e -> assertThat(e.getCode()).isEqualTo("CREDENTIAL_NOT_FOUND"))
                .hasMessageContaining("cannot read the Google credentials file");

        Path bad = write("bad.json", "{\"type\":\"service_account\",\"client_email\":\"a@b\",\"private_key\":\"secret-ish\"}");
        assertThatThrownBy(() -> credentials(bad.toString()).resolve(null).credential().get())
                .hasMessageContaining("cannot be used. The service account's private_key is not a PKCS#8 RSA key")
                .hasMessageNotContaining("secret-ish");
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }
}
